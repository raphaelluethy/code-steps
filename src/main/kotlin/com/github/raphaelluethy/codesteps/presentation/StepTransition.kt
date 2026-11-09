package com.github.raphaelluethy.codesteps.presentation

import com.github.raphaelluethy.codesteps.diff.StepDiff
import com.github.raphaelluethy.codesteps.diff.TextChange
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.CustomHighlighterOrder
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColorUtil
import java.awt.Color
import java.awt.Graphics
import javax.swing.Timer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Turns the current text of [document] into [newText], animated like an edit made by hand, minus the
 * typing: each block of changed lines is selected and fades out, the space it took slides to the size
 * of the new lines, and the new lines fade in. Text fades with its own syntax colors, and in [editor]
 * the code around a change slides instead of jumping. With a [fadeDurationMs] of 0 the change is applied at once.
 * Everything works on plain document text, so it behaves the same for every file type.
 *
 * Changed lines keep a soft tint of [highlightColor]; null leaves no mark. The tints go to [highlights],
 * which the caller owns, so they can outlive the transition (for example all hunks of a step stay
 * marked). With [diffColors], and unless [highlightColor] is null, a hunk is marked in the diff colors of the
 * editor color scheme instead: old code fades out in the deleted or modified color, new code keeps the
 * inserted or modified color. All document edits of one transition share an undo group.
 */
class StepTransition(
    private val project: Project,
    private val document: Document,
    private val newText: String,
    private val fadeDurationMs: Int,
    private val highlightColor: Color?,
    private val highlights: MutableList<RangeHighlighter> = mutableListOf(),
    private val editor: Editor? = null,
    private val onProgress: (offset: Int) -> Unit = {},
    private val onFinished: () -> Unit = {},
    private val diffColors: Boolean = false,
) : Disposable {

    private val oldText: CharSequence = document.immutableCharSequence
    private val changes: List<TextChange> = StepDiff.computeHunks(oldText, newText)
    private val removedChars = changes.sumOf { it.removedLength }
    private val insertedChars = changes.sumOf { it.insertedLength }

    private val gapPlans: List<GapPlan> = planGaps()

    private val fadeOutMs: Long = if (removedChars == 0) 0 else fadeDurationMs.toLong()
    private val moveMs: Long = if (gapPlans.isEmpty()) 0 else fadeDurationMs / 2L
    private val fadeInMs: Long = if (insertedChars == 0) 0 else fadeDurationMs.toLong()

    /** Total animation time; 0 means the change is applied at once. */
    val durationMs: Long get() = fadeOutMs + moveMs + fadeInMs

    /** Where the change starts (the same offset before and after) and how many lines it spans at most. */
    val focus: Pair<Int, Int>? = changes.firstOrNull()?.let { first ->
        val last = changes.last()
        val oldLines = oldText.subSequence(first.oldStart, last.oldEnd).count { it == '\n' }
        val newLines = newText.subSequence(first.newStart, last.newEnd).count { it == '\n' }
        first.newStart to max(oldLines, newLines) + 1
    }

    private val markup = DocumentMarkupModel.forDocument(document, project, true)
    private val transientHighlighters = mutableListOf<RangeHighlighter>()
    private val veils = mutableListOf<RangeHighlighter>()
    private val gaps = mutableListOf<Gap>()
    private val groupId = Any()

    private var deleted = false
    private var inserted = false
    private var expectedStamp = document.modificationStamp
    private var timer: Timer? = null

    private var isFinished = false

    /** Starts the animation on a Swing timer, or applies the change immediately when there is nothing to animate. */
    fun start() {
        if (durationMs == 0L) {
            finish()
            return
        }
        focus?.let { (offset, _) -> onProgress(offset) }
        if (fadeOutMs > 0) {
            // Like a selection, the range includes its line breaks, so removed lines are marked up to the editor's edge.
            for (change in changes) {
                if (change.removedLength > 0) {
                    val selected = TextAttributes().apply {
                        backgroundColor = diffColor(change, removed = true) ?: selectionColor()
                    }
                    veils += markup.addRangeHighlighter(
                        change.oldStart, change.oldEnd, HIGHLIGHT_LAYER, selected, HighlighterTargetArea.EXACT_RANGE,
                    )
                }
            }
            transientHighlighters += veils
        }
        val startNanos = System.nanoTime()
        // Timer events do not hold the write-intent lock that document edits need; invokeLater does.
        var framePending = false
        timer = Timer(FRAME_MS) {
            if (framePending) return@Timer
            framePending = true
            ApplicationManager.getApplication().invokeLater({
                framePending = false
                advanceTo((System.nanoTime() - startNanos) / 1_000_000)
            }, ModalityState.defaultModalityState())
        }.apply { start() }
    }

    /** Moves the animation to [elapsedMs] after its start. Returns true once the transition is finished. */
    fun advanceTo(elapsedMs: Long): Boolean {
        if (isFinished) return true
        if (document.modificationStamp != expectedStamp) {
            // Someone else edited the document mid-animation; offsets are stale, so jump to the end.
            finish()
            return true
        }
        if (elapsedMs < fadeOutMs) {
            // A slow start, so the audience sees what is selected before it goes.
            setVeil((elapsedMs.toDouble() / fadeOutMs).pow(3))
            return false
        }
        val moveElapsed = elapsedMs - fadeOutMs
        val insertElapsed = moveElapsed - moveMs
        if (insertElapsed >= fadeInMs) {
            finish()
            return true
        }
        deleteRemovedText()
        if (moveElapsed < moveMs) {
            resizeGaps(easeInOut(moveElapsed.toDouble() / moveMs))
            return false
        }
        closeGaps()
        insertAll()
        // A fast start, so the new text is readable early and then settles.
        setVeil((1 - insertElapsed.toDouble() / fadeInMs).pow(3))
        return false
    }

    /** Completes the transition immediately and shows the final highlights. Safe to call more than once. */
    fun finish() {
        if (isFinished) return
        isFinished = true
        timer?.stop()
        timer = null
        closeGaps()
        if (document.modificationStamp == expectedStamp) {
            deleteRemovedText()
            insertAll()
        }
        if (document.text != newText) edit { document.replaceString(0, document.textLength, newText) }
        removeTransientHighlighters()
        if (document.text == newText) {
            // Removed text leaves nothing behind.
            for (change in changes) addTint(change, highlights)
        }
        onFinished()
    }

    override fun dispose() {
        timer?.stop()
        timer = null
        isFinished = true
        closeGaps()
        removeTransientHighlighters()
    }

    /** Hides the veiled text by [opacity]; a new renderer makes the editors repaint the range. */
    private fun setVeil(opacity: Double) {
        veils.forEach { it.customRenderer = Veil(opacity) }
    }

    private fun deleteRemovedText() {
        if (deleted) return
        deleted = true
        removeTransientHighlighters()
        if (removedChars > 0) {
            edit {
                for (change in changes.asReversed()) {
                    if (change.removedLength > 0) document.deleteString(change.oldStart, change.oldEnd)
                }
            }
        }
        if (!isFinished) openGaps()
    }

    /**
     * After deletion, inserting the changes in order at their new-text offsets is exact:
     * the text before change k already equals the new text up to `newStart`.
     */
    private fun insertAll() {
        if (inserted) return
        inserted = true
        if (insertedChars == 0) return
        edit {
            for (change in changes) {
                if (change.insertedLength > 0) {
                    document.insertString(change.newStart, newText.substring(change.newStart, change.newEnd))
                }
            }
        }
        if (!isFinished) {
            for (change in changes) {
                if (change.insertedLength == 0) continue
                addTint(change, transientHighlighters)
                veils += markup.addRangeHighlighter(
                    change.newStart, change.newEnd, HIGHLIGHT_LAYER, null, HighlighterTargetArea.EXACT_RANGE,
                )
            }
            transientHighlighters += veils
        }
        onProgress(changes.first { it.insertedLength > 0 }.newStart)
    }

    private fun edit(block: () -> Unit) {
        WriteCommandAction.writeCommandAction(project)
            .withName("Code Steps: Change Step")
            .withGroupId(groupId.toString())
            .run<RuntimeException> { block() }
        expectedStamp = document.modificationStamp
    }

    /** Marks the new lines of [change] as changed with a soft tint, or its diff color, that keeps the syntax colors. */
    private fun addTint(change: TextChange, into: MutableList<RangeHighlighter>) {
        val start = change.newStart
        val end = change.newEnd
        if (highlightColor == null || start >= end) return
        val tinted = TextAttributes().apply {
            backgroundColor = diffColor(change, removed = false)
                ?: ColorUtil.mix(scheme.defaultBackground, highlightColor, TINT)
        }
        val text = document.immutableCharSequence
        // A line break at either end of the range belongs to a line that did not change.
        val first = if (text[start] == '\n' && start > 0 && text[start - 1] != '\n') start + 1 else start
        val last = if (end > first && text[end - 1] == '\n') end - 1 else end
        into += markup.addRangeHighlighter(first, last, HIGHLIGHT_LAYER, tinted, HighlighterTargetArea.LINES_IN_RANGE)
    }

    private fun removeTransientHighlighters() {
        transientHighlighters.forEach { it.dispose() }
        transientHighlighters.clear()
        veils.clear()
    }

    private val scheme get() = editor?.colorsScheme ?: EditorColorsManager.getInstance().globalScheme

    /**
     * The background the diff viewer gives [change]: modified when it replaces text, otherwise deleted for
     * its [removed] text and inserted for its new text. Null when diff colors are off or the scheme has none.
     */
    private fun diffColor(change: TextChange, removed: Boolean): Color? {
        if (!diffColors || highlightColor == null) return null
        val key: TextAttributesKey = when {
            change.removedLength > 0 && change.insertedLength > 0 -> DiffColors.DIFF_MODIFIED
            removed -> DiffColors.DIFF_DELETED
            else -> DiffColors.DIFF_INSERTED
        }
        return scheme.getAttributes(key)?.backgroundColor
    }

    private fun selectionColor(): Color = scheme.getColor(EditorColors.SELECTION_BACKGROUND_COLOR)
        ?: ColorUtil.mix(scheme.defaultBackground, scheme.defaultForeground, 0.25)

    // Gaps

    /** Empty space at [offset] of the document without the removed text, sliding from [from] to [to] pixels. */
    private class GapPlan(val offset: Int, val block: Boolean, val above: Boolean, val from: Int, val to: Int)

    private class Gap(val inlay: Inlay<GapRenderer>, val plan: GapPlan)

    /** Empty space in the editor: [size] pixels of height between lines, or of width inside a line. */
    private class GapRenderer(private val block: Boolean, var size: Int) : EditorCustomElementRenderer {
        // An inlay inside a line must be at least one pixel wide.
        override fun calcWidthInPixels(inlay: Inlay<*>) = if (block) 0 else max(1, size)
        override fun calcHeightInPixels(inlay: Inlay<*>) = if (block) size else inlay.editor.lineHeight
    }

    /**
     * Between deleting the old text and inserting the new text, a gap holds the space of the old text and slides
     * to the space of the new text, so the code after a change moves to its new place instead of jumping there.
     * Changed lines get a gap between lines, a change inside one line gets a gap in that line.
     */
    private fun planGaps(): List<GapPlan> {
        val editor = editor ?: return emptyList()
        if (fadeDurationMs <= 0) return emptyList()
        val plans = mutableListOf<GapPlan>()
        var removedBefore = 0
        for (change in changes) {
            val offset = change.oldStart - removedBefore
            removedBefore += change.removedLength
            val start = editor.offsetToVisualPosition(change.oldStart)
            val end = editor.offsetToVisualPosition(change.oldEnd)
            val inserted = newText.substring(change.newStart, change.newEnd)
            val insertedLines = inserted.count { it == '\n' }
            if (start.line == end.line && insertedLines == 0) {
                val from = editor.visualPositionToXY(end).x - editor.visualPositionToXY(start).x
                val font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
                val to = editor.contentComponent.getFontMetrics(font).stringWidth(inserted)
                if (from != to) plans += GapPlan(offset, block = false, above = false, from, to)
            } else {
                val from = (end.line - start.line) * editor.lineHeight
                val to = insertedLines * editor.lineHeight
                // The removed lines are above the rest of the line they end in, unless they start in the middle of a line.
                val lineStart = oldText.lastIndexOf('\n', change.oldStart - 1) + 1
                val above = oldText.subSequence(lineStart, change.oldStart).isBlank()
                if (from != to) plans += GapPlan(offset, block = true, above, from, to)
            }
        }
        return plans
    }

    private fun openGaps() {
        val editor = editor?.takeUnless { it.isDisposed } ?: return
        for (plan in gapPlans) {
            val renderer = GapRenderer(plan.block, plan.from)
            val inlay = if (plan.block) {
                editor.inlayModel.addBlockElement(plan.offset, !plan.above, plan.above, 0, renderer)
            } else {
                editor.inlayModel.addInlineElement(plan.offset, false, renderer)
            }
            if (inlay != null) gaps += Gap(inlay, plan)
        }
    }

    private fun resizeGaps(progress: Double) {
        for (gap in gaps) {
            if (!gap.inlay.isValid) continue
            gap.inlay.renderer.size = (gap.plan.from + (gap.plan.to - gap.plan.from) * progress).roundToInt()
            gap.inlay.update()
        }
    }

    private fun closeGaps() {
        gaps.forEach { Disposer.dispose(it.inlay) }
        gaps.clear()
    }

    /**
     * Paints the editor background over the highlighted text with [opacity], which fades the text and its
     * background out or in while the text keeps its own colors.
     */
    private class Veil(private val opacity: Double) : CustomHighlighterRenderer {
        override fun getOrder() = CustomHighlighterOrder.AFTER_TEXT

        override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
            if (opacity <= 0.0 || !highlighter.isValid) return
            val start = editor.offsetToVisualPosition(highlighter.startOffset)
            val end = editor.offsetToVisualPosition(highlighter.endOffset)
            val width = editor.contentComponent.width
            val clip = g.clipBounds
            val firstLine = if (clip == null) start.line else max(start.line, editor.yToVisualLine(clip.y))
            val lastLine = if (clip == null) end.line else min(end.line, editor.yToVisualLine(clip.y + clip.height))
            val background = (editor as? EditorEx)?.backgroundColor ?: editor.colorsScheme.defaultBackground
            val caretRow = editor.colorsScheme.getColor(EditorColors.CARET_ROW_COLOR)
                ?.takeIf { editor.settings.isCaretRowShown }
            val caretLine = editor.caretModel.visualPosition.line
            for (line in firstLine..lastLine) {
                val left = if (line == start.line && start.column > 0) editor.visualPositionToXY(start).x else 0
                val right = if (line == end.line) editor.visualPositionToXY(end).x else width
                if (line == end.line && end.column == 0 || right <= left) continue
                // A change inside the caret's line fades into that line's own background.
                val insideLine = left > 0 || right < width
                val color = if (line == caretLine && insideLine && caretRow != null) caretRow else background
                g.color = Color(color.red, color.green, color.blue, (opacity.coerceAtMost(1.0) * 255).roundToInt())
                g.fillRect(left, editor.visualLineToY(line), right - left, editor.lineHeight)
            }
        }
    }

    private companion object {
        const val FRAME_MS = 16
        const val HIGHLIGHT_LAYER = HighlighterLayer.SELECTION - 1

        /** How much of the highlight color is mixed into the editor background behind changed text. */
        const val TINT = 0.12

        fun easeInOut(t: Double): Double = if (t < 0.5) 4 * t * t * t else 1 - (2 - 2 * t).pow(3) / 2
    }
}
