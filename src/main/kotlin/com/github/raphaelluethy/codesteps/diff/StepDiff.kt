package com.github.raphaelluethy.codesteps.diff

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.comparison.DiffTooBigException
import com.intellij.openapi.progress.DumbProgressIndicator

/**
 * Replaces `old[oldStart, oldEnd)` with `new[newStart, newEnd)`.
 * A list of changes is sorted and non-overlapping in both texts.
 */
data class TextChange(val oldStart: Int, val oldEnd: Int, val newStart: Int, val newEnd: Int) {
    val removedLength: Int get() = oldEnd - oldStart
    val insertedLength: Int get() = newEnd - newStart
}

/** Text-only diff between two snapshots; works for any file type because it never looks at PSI. */
object StepDiff {
    /** Hunks: each block of contiguous changed lines is one change, top to bottom. */
    fun computeHunks(old: CharSequence, new: CharSequence): List<TextChange> {
        if (old.contentEquals(new)) return emptyList()
        val fragments = try {
            ComparisonManager.getInstance()
                .compareLines(old, new, ComparisonPolicy.DEFAULT, DumbProgressIndicator.INSTANCE)
        } catch (_: DiffTooBigException) {
            return listOf(TextChange(0, old.length, 0, new.length))
        }
        return withRepairedGaps(old, new, fragments.map { TextChange(it.startOffset1, it.endOffset1, it.startOffset2, it.endOffset2) })
    }

    /**
     * The edits that turn [old] into [new], as small as they read well: inside a block of changed lines only
     * the words that differ are changes, so a renamed call or an added argument does not replace its line.
     * A block that needs many word edits and keeps little of its text is one change, because a rewrite shown
     * as scattered word edits is harder to follow than the old lines going and the new lines coming.
     */
    fun computeEdits(old: CharSequence, new: CharSequence): List<TextChange> {
        if (old.contentEquals(new)) return emptyList()
        val fragments = try {
            ComparisonManager.getInstance()
                .compareLinesInner(old, new, ComparisonPolicy.DEFAULT, DumbProgressIndicator.INSTANCE)
        } catch (_: DiffTooBigException) {
            return listOf(TextChange(0, old.length, 0, new.length))
        }
        return withRepairedGaps(old, new, fragments.flatMap { fragment ->
            val block = TextChange(fragment.startOffset1, fragment.endOffset1, fragment.startOffset2, fragment.endOffset2)
            val words = fragment.innerFragments?.map {
                TextChange(
                    block.oldStart + it.startOffset1, block.oldStart + it.endOffset1,
                    block.newStart + it.startOffset2, block.newStart + it.endOffset2,
                )
            }
            if (words != null && keepsEnough(old, new, block, words)) words else listOf(block)
        })
    }

    /**
     * Whether [words] read as edits of [block]: they leave some text untouched, and are either few or leave
     * at least [KEPT_SHARE] of the longer side untouched.
     */
    private fun keepsEnough(old: CharSequence, new: CharSequence, block: TextChange, words: List<TextChange>): Boolean {
        val oldSize = visibleChars(old, block.oldStart, block.oldEnd)
        val newSize = visibleChars(new, block.newStart, block.newEnd)
        val kept = oldSize - words.sumOf { visibleChars(old, it.oldStart, it.oldEnd) }
        return kept > 0 && (words.size <= FEW_EDITS || kept >= KEPT_SHARE * maxOf(oldSize, newSize))
    }

    private fun visibleChars(text: CharSequence, start: Int, end: Int): Int =
        (start until end).count { !text[it].isWhitespace() }

    private const val KEPT_SHARE = 0.5
    private const val FEW_EDITS = 2

    /** The text after applying the first [count] of [hunks] (computed from [old] to [new]) to [old]. */
    fun applyFirst(old: CharSequence, new: CharSequence, hunks: List<TextChange>, count: Int): String {
        if (count <= 0) return old.toString()
        if (count >= hunks.size) return new.toString()
        val last = hunks[count - 1]
        return new.substring(0, last.newEnd) + old.substring(last.oldEnd)
    }

    /**
     * Line fragments do not always cover line separators, for example when the old text has no trailing
     * newline. Turns every unchanged gap whose old and new text differ into a change, so applying the
     * changes always reproduces the new text exactly. Changes that touch are merged.
     */
    private fun withRepairedGaps(old: CharSequence, new: CharSequence, changes: List<TextChange>): List<TextChange> {
        val result = mutableListOf<TextChange>()
        fun add(change: TextChange) {
            val previous = result.lastOrNull()
            if (previous != null && previous.oldEnd == change.oldStart && previous.newEnd == change.newStart) {
                result[result.lastIndex] = TextChange(previous.oldStart, change.oldEnd, previous.newStart, change.newEnd)
            } else {
                result += change
            }
        }
        var oldPos = 0
        var newPos = 0
        fun gap(oldEnd: Int, newEnd: Int) {
            var start1 = oldPos
            var start2 = newPos
            var end1 = oldEnd
            var end2 = newEnd
            while (start1 < end1 && start2 < end2 && old[start1] == new[start2]) { start1++; start2++ }
            while (end1 > start1 && end2 > start2 && old[end1 - 1] == new[end2 - 1]) { end1--; end2-- }
            if (start1 < end1 || start2 < end2) add(TextChange(start1, end1, start2, end2))
        }
        for (change in changes) {
            gap(change.oldStart, change.newStart)
            if (change.removedLength > 0 || change.insertedLength > 0) add(change)
            oldPos = change.oldEnd
            newPos = change.newEnd
        }
        gap(old.length, new.length)
        return result
    }
}
