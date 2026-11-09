package com.github.raphaelluethy.codesteps.diff

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.comparison.DiffTooBigException
import com.intellij.diff.fragments.LineFragment
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
    /** Changed words inside changed lines. */
    fun compute(old: CharSequence, new: CharSequence): List<TextChange> = diff(old, new) { fragment ->
        val inner = fragment.innerFragments ?: return@diff listOf(fragment.toChange())
        inner.map { f ->
            TextChange(
                fragment.startOffset1 + f.startOffset1,
                fragment.startOffset1 + f.endOffset1,
                fragment.startOffset2 + f.startOffset2,
                fragment.startOffset2 + f.endOffset2,
            )
        }
    }

    /** Hunks: each block of contiguous changed lines is one change, top to bottom. */
    fun computeHunks(old: CharSequence, new: CharSequence): List<TextChange> = diff(old, new, inner = false) {
        listOf(it.toChange())
    }

    /** The text after applying the first [count] of [hunks] (computed from [old] to [new]) to [old]. */
    fun applyFirst(old: CharSequence, new: CharSequence, hunks: List<TextChange>, count: Int): String {
        if (count <= 0) return old.toString()
        if (count >= hunks.size) return new.toString()
        val last = hunks[count - 1]
        return new.substring(0, last.newEnd) + old.substring(last.oldEnd)
    }

    private fun LineFragment.toChange() = TextChange(startOffset1, endOffset1, startOffset2, endOffset2)

    private fun diff(
        old: CharSequence,
        new: CharSequence,
        inner: Boolean = true,
        split: (LineFragment) -> List<TextChange>,
    ): List<TextChange> {
        if (old.contentEquals(new)) return emptyList()
        val fragments = try {
            val comparison = ComparisonManager.getInstance()
            if (inner) {
                comparison.compareLinesInner(old, new, ComparisonPolicy.DEFAULT, DumbProgressIndicator.INSTANCE)
            } else {
                comparison.compareLines(old, new, ComparisonPolicy.DEFAULT, DumbProgressIndicator.INSTANCE)
            }
        } catch (_: DiffTooBigException) {
            return listOf(TextChange(0, old.length, 0, new.length))
        }
        return withRepairedGaps(old, new, fragments.flatMap(split))
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
