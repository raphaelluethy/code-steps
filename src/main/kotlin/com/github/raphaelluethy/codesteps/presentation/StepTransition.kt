package com.github.raphaelluethy.codesteps.presentation

import com.github.raphaelluethy.codesteps.diff.StepDiff
import com.github.raphaelluethy.codesteps.diff.TextChange
import com.github.raphaelluethy.codesteps.settings.InsertMode
import com.github.raphaelluethy.codesteps.settings.TransitionStyle
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.LineMarkerRendererEx
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.ui.ColorUtil
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Rectangle
import javax.swing.Timer
import kotlin.math.max
import kotlin.math.min

/**
 * Turns the current text of [document] into [newText], optionally animated:
 * removed text fades out and is deleted, then inserted text fades or is typed in.
 * Hunk styles treat each block of changed lines as one unit; word styles animate changed words.
 * Everything works on plain document text, so it behaves the same for every file type.
 *
 * Final change highlights go to [highlights], which the caller owns, so highlights can outlive
 * the transition (for example all hunks of a step stay marked). All document edits of one
 * transition share an undo group.
 */
class StepTransition(
    private val project: Project,
    private val document: Document,
    private val newText: String,
    private val options: Options,
    private val highlights: MutableList<RangeHighlighter> = mutableListOf(),
    private val onProgress: (offset: Int) -> Unit = {},
    private val onFinished: () -> Unit = {},
) : Disposable {

    data class Options(
        val style: TransitionStyle,
        val highlightChanges: Boolean,
        val fadeDurationMs: Int,
        val typingCharsPerSecond: Int,
        val maxTypingDurationMs: Int,
        val highlightColor: Color = Color(0xFFE600),
    )

    private val style = options.style
    private val highlight = when (style) {
        TransitionStyle.HIGHLIGHT -> true
        TransitionStyle.INSTANT -> false
        else -> options.highlightChanges
    }

    private val changes: List<TextChange> =
        if (style.wholeHunks) StepDiff.computeHunks(document.immutableCharSequence, newText)
        else StepDiff.compute(document.immutableCharSequence, newText)
    private val typingChanges: List<TextChange> =
        changes.mapIndexed { i, change -> openLineFirst(change, if (i == 0) 0 else changes[i - 1].newEnd) }
    private val removedChars = changes.sumOf { it.removedLength }
    private val insertedChars = changes.sumOf { it.insertedLength }

    private val fadeOutMs: Long = if (!style.fadesOut || removedChars == 0) 0 else options.fadeDurationMs.toLong()
    private val insertMs: Long = when {
        insertedChars == 0 -> 0
        style.insertMode == InsertMode.FADE -> options.fadeDurationMs.toLong()
        style.insertMode == InsertMode.TYPE ->
            min(insertedChars * 1000L / options.typingCharsPerSecond.coerceAtLeast(1), options.maxTypingDurationMs.toLong())
        else -> 0
    }

    /** Total animation time; 0 means the change is applied at once. */
    val durationMs: Long get() = fadeOutMs + insertMs

    /** Where the change starts (the same offset before and after) and how many lines it spans at most. */
    val focus: Pair<Int, Int>? = changes.firstOrNull()?.let { first ->
        val last = changes.last()
        val oldText = document.immutableCharSequence
        val oldLines = oldText.subSequence(first.oldStart, last.oldEnd).count { it == '\n' }
        val newLines = newText.subSequence(first.newStart, last.newEnd).count { it == '\n' }
        first.newStart to max(oldLines, newLines) + 1
    }

    /** The first changed offset in the new text, or null when nothing changes. */
    val firstChangeOffset: Int? get() = changes.firstOrNull()?.newStart

    private val markup = DocumentMarkupModel.forDocument(document, project, true)
    private val transientHighlighters = mutableListOf<RangeHighlighter>()
    private val typingSpans = mutableListOf<RangeHighlighter>()
    private val groupId = Any()

    private var deleted = false
    private var fadeInserted = false
    private var typedChars = 0
    private var typingChange = 0
    private var typingOffset = 0
    private var expectedStamp = document.modificationStamp
    private var timer: Timer? = null

    var isFinished = false
        private set

    /** Starts the animation on a Swing timer, or applies the change immediately when there is nothing to animate. */
    fun start() {
        if (durationMs == 0L) {
            finish()
            return
        }
        firstChangeOffset?.let(onProgress)
        if (fadeOutMs > 0) {
            for (change in changes) {
                if (change.removedLength > 0) transientHighlighters += addSpans(change.oldStart, change.oldEnd, fadeOutAttributes(0.0))
            }
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
            setTransientAttributes(fadeOutAttributes(elapsedMs.toDouble() / fadeOutMs))
            return false
        }
        val insertElapsed = elapsedMs - fadeOutMs
        if (insertElapsed >= insertMs) {
            finish()
            return true
        }
        deleteRemovedText()
        when (style.insertMode) {
            InsertMode.FADE -> {
                insertAll()
                setTransientAttributes(fadeInAttributes(insertElapsed.toDouble() / insertMs))
            }
            InsertMode.TYPE -> typeUpTo((insertElapsed * insertedChars / insertMs).toInt())
            InsertMode.NONE -> Unit
        }
        return false
    }

    /** Completes the transition immediately and shows the final highlights. Safe to call more than once. */
    fun finish() {
        if (isFinished) return
        isFinished = true
        timer?.stop()
        timer = null
        if (document.modificationStamp == expectedStamp) {
            deleteRemovedText()
            if (style.insertMode == InsertMode.TYPE) typeUpTo(insertedChars) else insertAll()
        }
        if (document.text != newText) edit { document.replaceString(0, document.textLength, newText) }
        removeTransientHighlighters()
        if (highlight && document.text == newText) addResultHighlighters()
        onFinished()
    }

    override fun dispose() {
        timer?.stop()
        timer = null
        isFinished = true
        removeTransientHighlighters()
    }

    private fun setTransientAttributes(attributes: TextAttributes) {
        transientHighlighters.forEach { (it as? RangeHighlighterEx)?.setTextAttributes(attributes) }
    }

    private fun deleteRemovedText() {
        if (deleted) return
        deleted = true
        removeTransientHighlighters()
        if (removedChars == 0) return
        edit {
            for (change in changes.asReversed()) {
                if (change.removedLength > 0) document.deleteString(change.oldStart, change.oldEnd)
            }
        }
    }

    /**
     * After deletion, inserting the changes in order at their new-text offsets is exact:
     * the text before change k already equals the new text up to `newStart`.
     */
    private fun insertAll() {
        if (fadeInserted) return
        fadeInserted = true
        if (insertedChars == 0) return
        edit {
            for (change in changes) {
                if (change.insertedLength > 0) {
                    document.insertString(change.newStart, newText.substring(change.newStart, change.newEnd))
                }
            }
        }
        for (change in changes) {
            if (change.insertedLength > 0) transientHighlighters += addSpans(change.newStart, change.newEnd, fadeInAttributes(0.0))
        }
        onProgress(changes.first { it.insertedLength > 0 }.newStart)
    }

    /** Types inserted text in document order until [target] characters are typed in total. */
    private fun typeUpTo(target: Int) {
        if (typedChars >= target) return
        var caret = -1
        edit {
            while (typedChars < target && typingChange < typingChanges.size) {
                val change = typingChanges[typingChange]
                val count = min(change.insertedLength - typingOffset, target - typedChars)
                if (count > 0) {
                    val offset = change.newStart + typingOffset
                    document.insertString(offset, newText.substring(offset, offset + count))
                    typingOffset += count
                    typedChars += count
                    caret = offset + count
                }
                if (typingOffset == change.insertedLength) {
                    typingChange++
                    typingOffset = 0
                    // Keep the finished change marked while the next one is typed.
                    if (highlight) {
                        typingSpans.forEach { it.dispose() }
                        typingSpans.clear()
                        transientHighlighters += addSpans(change.newStart, change.newEnd, markerAttributes())
                    }
                }
            }
        }
        if (highlight) {
            typingSpans.forEach { it.dispose() }
            typingSpans.clear()
            typingChanges.getOrNull(typingChange)?.takeIf { typingOffset > 0 }?.let { change ->
                typingSpans += addSpans(change.newStart, change.newStart + typingOffset, markerAttributes())
            }
        }
        if (caret >= 0) onProgress(caret)
    }

    /**
     * Line diffs report an inserted line as "text, line break, next line's indent". Typed in that order, the
     * following line hangs at the end of the half-typed line. Rotating the insertion left through the
     * identical text before it (for example to "line break, indent, text") opens the new line first.
     * Any rotation is equivalent, since the skipped text is unchanged in both versions.
     */
    private fun openLineFirst(change: TextChange, minStart: Int): TextChange {
        if (change.insertedLength == 0 || newText.indexOf('\n', change.newStart) !in change.newStart until change.newEnd) {
            return change
        }
        var start = change.newStart
        var end = change.newEnd
        while (newText[start] != '\n' && start > minStart && newText[start - 1] == newText[end - 1]) {
            start--
            end--
        }
        if (newText[start] != '\n') return change
        val shift = change.newStart - start
        return TextChange(change.oldStart - shift, change.oldEnd - shift, start, end)
    }

    private fun edit(block: () -> Unit) {
        WriteCommandAction.writeCommandAction(project)
            .withName("Code Steps: Change Step")
            .withGroupId(groupId.toString())
            .run<RuntimeException> { block() }
        expectedStamp = document.modificationStamp
    }

    private fun addResultHighlighters() {
        val stripe = Palette.stripe(options.highlightColor, scheme.defaultBackground)
        for (change in changes) {
            if (change.insertedLength == 0) {
                val mark = markup.addRangeHighlighter(
                    change.newStart, change.newStart, HIGHLIGHT_LAYER, null, HighlighterTargetArea.EXACT_RANGE,
                )
                mark.customRenderer = DeletionMark(Palette.REMOVED)
                mark.lineMarkerRenderer = GutterMarker(Palette.REMOVED, deletion = true)
                highlights += mark
                continue
            }
            highlights += addSpans(change.newStart, change.newEnd, markerAttributes())
            val end = if (newText[change.newEnd - 1] == '\n') change.newEnd - 1 else change.newEnd
            val line = markup.addRangeHighlighter(
                change.newStart, max(change.newStart, end), HIGHLIGHT_LAYER, null, HighlighterTargetArea.LINES_IN_RANGE,
            )
            line.lineMarkerRenderer = GutterMarker(stripe, deletion = false)
            highlights += line
        }
    }

    private fun removeTransientHighlighters() {
        transientHighlighters.forEach { it.dispose() }
        transientHighlighters.clear()
        typingSpans.forEach { it.dispose() }
        typingSpans.clear()
    }

    /** Highlights the text of every line in `[start, end)`, skipping indentation and line breaks, like a marker pen. */
    private fun addSpans(start: Int, end: Int, attributes: TextAttributes): List<RangeHighlighter> {
        val text = document.immutableCharSequence
        val result = mutableListOf<RangeHighlighter>()
        if (start >= end) return result
        for (line in document.getLineNumber(start)..document.getLineNumber(end)) {
            val lineStart = document.getLineStartOffset(line)
            val lineEnd = document.getLineEndOffset(line)
            var textStart = lineStart
            while (textStart < lineEnd && (text[textStart] == ' ' || text[textStart] == '\t')) textStart++
            val s = max(start, textStart)
            val e = min(end, lineEnd)
            if (s < e) result += markup.addRangeHighlighter(s, e, HIGHLIGHT_LAYER, attributes, HighlighterTargetArea.EXACT_RANGE)
        }
        return result
    }

    private val scheme get() = EditorColorsManager.getInstance().globalScheme

    private fun markerAttributes() = TextAttributes().apply {
        backgroundColor = options.highlightColor
        foregroundColor = Palette.textOn(options.highlightColor)
        fontType = Font.BOLD
    }

    private fun fadeOutAttributes(progress: Double) = TextAttributes().apply {
        val editorBackground = scheme.defaultBackground
        foregroundColor = ColorUtil.mix(Palette.textOn(Palette.REMOVED), editorBackground, progress)
        backgroundColor = ColorUtil.mix(Palette.REMOVED, editorBackground, progress)
        effectType = EffectType.STRIKEOUT
        effectColor = foregroundColor
    }

    private fun fadeInAttributes(progress: Double) = TextAttributes().apply {
        val editorBackground = scheme.defaultBackground
        val finalBackground = if (highlight) options.highlightColor else editorBackground
        val finalForeground = if (highlight) Palette.textOn(options.highlightColor) else scheme.defaultForeground
        foregroundColor = ColorUtil.mix(editorBackground, finalForeground, progress)
        backgroundColor = ColorUtil.mix(editorBackground, finalBackground, progress)
        if (highlight) fontType = Font.BOLD
    }

    /** Marks changed lines in the gutter with a wide bar; deletions get a wedge. */
    private class GutterMarker(private val color: Color, private val deletion: Boolean) : LineMarkerRendererEx {
        override fun paint(editor: Editor, g: Graphics, r: Rectangle) {
            g.color = color
            val width = JBUI.scale(5)
            if (deletion) {
                val size = JBUI.scale(6)
                g.fillPolygon(intArrayOf(r.x, r.x + size + width, r.x), intArrayOf(r.y - size, r.y, r.y + size), 3)
            } else {
                g.fillRect(r.x, r.y, width, r.height)
            }
        }

        override fun getPosition() = LineMarkerRendererEx.Position.LEFT
    }

    /** A thick bar where text was removed: between lines for removed lines, otherwise a vertical bar in the line. */
    private class DeletionMark(private val color: Color) : CustomHighlighterRenderer {
        override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
            val document = editor.document
            val offset = highlighter.startOffset
            if (!highlighter.isValid || offset > document.textLength) return
            val line = document.getLineNumber(offset)
            val point = editor.offsetToXY(offset)
            val thickness = JBUI.scale(3)
            g.color = color
            if (offset == document.getLineStartOffset(line)) {
                val visible = editor.scrollingModel.visibleArea
                g.fillRect(point.x, point.y - thickness / 2, visible.x + visible.width - point.x, thickness)
            } else {
                g.fillRect(point.x - thickness / 2, point.y, thickness, editor.lineHeight)
            }
        }
    }

    private companion object {
        const val FRAME_MS = 16
        const val HIGHLIGHT_LAYER = HighlighterLayer.SELECTION - 1
    }
}

/** Saturated colors that survive washed-out projectors on light and dark editor themes. */
object Palette {
    val REMOVED = Color(0xFF3B3B)

    /** Black or white, whichever contrasts more with [background]. */
    fun textOn(background: Color): Color = if (luminance(background) > 0.179) Color.BLACK else Color.WHITE

    /** On light editors a pale marker color is hard to see in the gutter, so the bar gets darker. */
    fun stripe(color: Color, editorBackground: Color): Color =
        if (luminance(editorBackground) > 0.5) ColorUtil.mix(color, Color.BLACK, 0.35) else color

    private fun luminance(color: Color): Double {
        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }
}
