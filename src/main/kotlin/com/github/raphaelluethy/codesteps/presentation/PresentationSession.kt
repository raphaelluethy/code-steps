package com.github.raphaelluethy.codesteps.presentation

import com.github.raphaelluethy.codesteps.diff.StepDiff
import com.github.raphaelluethy.codesteps.diff.TextChange
import com.github.raphaelluethy.codesteps.settings.CodeStepsSettings
import com.github.raphaelluethy.codesteps.storage.Presentation
import com.github.raphaelluethy.codesteps.storage.PresentationException
import com.github.raphaelluethy.codesteps.storage.PresentationStorage
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.zoomIndicator.ZoomIndicatorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.EditorNotifications
import javax.swing.Timer
import kotlin.math.max

/**
 * The running presentation of a project. Stepping replaces the text of the target file's regular
 * document, so every editor feature (highlighting, navigation, completion, inspections) keeps working.
 *
 * The position is a step plus the number of hunks of the change to the next step that are already shown.
 * Moving forward plays hunks one at a time, top to bottom; moving back jumps with one transition.
 * Must be used on the EDT.
 */
@Service(Service.Level.PROJECT)
class PresentationSession(private val project: Project) : Disposable {

    class Active internal constructor(
        presentation: Presentation,
        val target: VirtualFile,
        val document: Document,
        val originalText: String,
        internal val stepTexts: MutableList<String>,
    ) {
        var presentation: Presentation = presentation
            internal set
        var index: Int = 0
            internal set

        /** How many hunks of the change from step [index] to the next step are shown. */
        var hunk: Int = 0
            internal set

        private val hunkCache = mutableMapOf<Int, List<TextChange>>()

        val stepCount: Int get() = stepTexts.size
        val stepTitle: String get() = presentation.manifest.steps[index].title
        val nextStepTitle: String? get() = presentation.manifest.steps.getOrNull(index + 1)?.title

        /** Hunks of the change from step [step] to the next one, top to bottom. */
        fun hunks(step: Int = index): List<TextChange> =
            if (step + 1 >= stepCount) emptyList()
            else hunkCache.getOrPut(step) { StepDiff.computeHunks(stepTexts[step], stepTexts[step + 1]) }

        val isAtStart: Boolean get() = index == 0 && hunk == 0
        val isAtEnd: Boolean get() = index == stepCount - 1

        internal fun text(step: Int, hunks: Int): String =
            if (hunks == 0) stepTexts[step] else StepDiff.applyFirst(stepTexts[step], stepTexts[step + 1], hunks(step), hunks)

        internal fun invalidateHunks() = hunkCache.clear()
    }

    var active: Active? = null
        private set

    /** True while hunks play on their own; see [togglePlay]. */
    var isPlaying: Boolean = false
        private set

    val isActive: Boolean get() = active != null

    private var transition: StepTransition? = null
    private val highlights = mutableListOf<RangeHighlighter>()
    private var pauseTimer: Timer? = null

    /** The step at which playback stops, or null to play to the end. */
    private var playUntilStep: Int? = null

    private val settings get() = CodeStepsSettings.getInstance().state

    /** Opens the target file and shows step [startIndex] without animation. */
    fun start(presentation: Presentation, startIndex: Int = 0) {
        stop()
        if (presentation.manifest.steps.isEmpty()) throw PresentationException("'${presentation.title}' has no steps yet")
        val target = presentation.findTarget()
            ?: throw PresentationException("Target file '${presentation.manifest.target}' does not exist")
        val document = FileDocumentManager.getInstance().getDocument(target)
            ?: throw PresentationException("'${presentation.manifest.target}' cannot be opened as text")
        val texts = presentation.manifest.steps.indices.map { PresentationStorage.stepText(presentation, it) }
        val session = Active(presentation, target, document, document.text, texts.toMutableList())
        session.index = startIndex.coerceIn(0, texts.lastIndex)
        active = session
        val editor = openEditor(session)
        runTransition(session.stepTexts[session.index], animate = false, highlight = false)
        editor?.caretModel?.moveToOffset(0)
        editor?.scrollingModel?.scrollToCaret(ScrollType.MAKE_VISIBLE)
    }

    /** Shows the next hunk; at the end of a step's hunks this reaches the next step. */
    fun nextHunk() {
        stopPlaying()
        advanceHunk()
    }

    /** Takes back the last shown hunk, crossing into the previous step when needed. */
    fun previousHunk() {
        val session = active ?: return
        stopPlaying()
        when {
            session.hunk > 0 -> moveTo(session.index, session.hunk - 1)
            session.index > 0 -> moveTo(session.index - 1, max(0, session.hunks(session.index - 1).size - 1))
        }
    }

    /**
     * Goes to the next step. Animated changes play the step's hunks one at a time; pressing again while
     * they play completes the step at once, like clicking through a slide animation.
     */
    fun nextStep() {
        val session = active ?: return
        val until = playUntilStep
        if (isPlaying && until != null) {
            stopPlaying()
            fastForwardTo(until)
            return
        }
        stopPlaying()
        if (session.isAtEnd) return
        if (settings.animate) {
            play(untilStep = session.index + 1)
        } else {
            moveTo(session.index + 1, 0, clearHighlights = session.hunk == 0)
        }
    }

    fun previousStep() {
        val session = active ?: return
        stopPlaying()
        if (session.hunk > 0) moveTo(session.index, 0) else if (session.index > 0) moveTo(session.index - 1, 0)
    }

    /** Starts or pauses playback. A pause lets the current hunk finish. */
    fun togglePlay() {
        val session = active ?: return
        if (isPlaying) {
            stopPlaying()
            refreshBanner()
        } else if (!session.isAtEnd) {
            play(untilStep = if (settings.playAcrossSteps) null else session.index + 1)
        }
    }

    /** Ends the presentation and restores the content the file had before it started. */
    fun stop() {
        val session = active ?: return
        stopPlaying()
        disposeTransition()
        clearHighlights()
        releaseProjectorMode()
        active = null
        if (session.document.text != session.originalText) {
            WriteCommandAction.writeCommandAction(project)
                .withName("Code Steps: Stop Presentation")
                .run<RuntimeException> { session.document.setText(session.originalText) }
        }
        EditorNotifications.getInstance(project).updateNotifications(session.target)
    }

    /** Replaces the current step's snapshot with the editor content. Only possible at the start of a step. */
    fun saveEditorToCurrentStep() {
        val session = active ?: return
        stopPlaying()
        transition?.finish()
        if (session.hunk != 0) throw PresentationException("Finish or rewind the current step before saving the editor to it")
        val text = session.document.text
        WriteCommandAction.writeCommandAction(project)
            .withName("Code Steps: Update Step")
            .run<RuntimeException> { PresentationStorage.updateStep(session.presentation, session.index, text) }
        session.stepTexts[session.index] = text
        session.invalidateHunks()
    }

    /** Inserts the editor content as a new step after the current position and moves to it. */
    fun insertEditorAsStepAfterCurrent(title: String) {
        val session = active ?: return
        stopPlaying()
        transition?.finish()
        val text = session.document.text
        session.presentation = WriteCommandAction.writeCommandAction(project)
            .withName("Code Steps: Add Step")
            .compute<Presentation, RuntimeException> {
                PresentationStorage.addStep(session.presentation, title, text, session.index + 1)
            }
        session.stepTexts.add(session.index + 1, text)
        session.invalidateHunks()
        session.index++
        session.hunk = 0
        disposeTransition()
        refreshBanner()
    }

    private fun clearHighlights() {
        highlights.forEach { it.dispose() }
        highlights.clear()
    }

    // Playback

    private fun play(untilStep: Int?) {
        isPlaying = true
        playUntilStep = untilStep
        advanceHunk()
    }

    private fun stopPlaying() {
        isPlaying = false
        playUntilStep = null
        pauseTimer?.stop()
        pauseTimer = null
    }

    private fun advanceHunk() {
        val session = active ?: return
        if (session.isAtEnd) {
            stopPlaying()
            refreshBanner()
            return
        }
        val hunks = session.hunks()
        val startsStep = session.hunk == 0
        val nextHunk = session.hunk + 1
        if (nextHunk >= hunks.size) {
            session.index++
            session.hunk = 0
        } else {
            session.hunk = nextHunk
        }
        val target = session.text(session.index, session.hunk)
        // A new step starts with a clean slate; hunks of the same step stay marked.
        runTransition(target, clearHighlights = startsStep) { onHunkShown() }
    }

    private fun onHunkShown() {
        val session = active ?: return
        if (!isPlaying) return
        val until = playUntilStep
        if (session.isAtEnd || (until != null && session.hunk == 0 && session.index >= until)) {
            stopPlaying()
            refreshBanner()
            return
        }
        // Give the audience a longer look at a finished step before the next one starts.
        val pause = settings.hunkPauseMs * (if (session.hunk == 0) 2 else 1)
        pauseTimer = Timer(pause) {
            ApplicationManager.getApplication().invokeLater({
                if (isPlaying && active === session) advanceHunk()
            }, ModalityState.defaultModalityState())
        }.apply {
            isRepeats = false
            start()
        }
    }

    /** Completes the remaining hunks up to step [step] without animation. */
    private fun fastForwardTo(step: Int) {
        val session = active ?: return
        disposeTransition()
        session.index = step.coerceAtMost(session.stepCount - 1)
        session.hunk = 0
        runTransition(session.stepTexts[session.index], animate = false, clearHighlights = false)
    }

    private fun moveTo(index: Int, hunk: Int, clearHighlights: Boolean = true) {
        val session = active ?: return
        if (index !in 0 until session.stepCount) return
        session.index = index
        session.hunk = hunk
        runTransition(session.text(index, hunk), clearHighlights = clearHighlights)
    }

    // Transitions

    private fun runTransition(
        target: String,
        animate: Boolean = settings.animate,
        highlight: Boolean = settings.highlightChanges,
        clearHighlights: Boolean = true,
        onFinished: () -> Unit = {},
    ) {
        val session = active ?: return
        disposeTransition()
        if (clearHighlights) clearHighlights()
        val editor = openEditor(session)
        if (settings.projectorMode) applyProjectorMode()
        val newTransition = StepTransition(
            project, session.document, target,
            fadeDurationMs = if (animate) settings.fadeDurationMs else 0,
            highlightColor = if (highlight) CodeStepsSettings.parseColor(settings.highlightColor) else null,
            highlights, editor,
            onProgress = { offset ->
                if (editor != null && !editor.isDisposed && offset <= editor.document.textLength) {
                    editor.caretModel.moveToOffset(offset)
                    editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
                }
            },
            onFinished = {
                refreshBanner()
                onFinished()
            },
        )
        Disposer.register(this, newTransition)
        transition = newTransition
        if (editor != null) newTransition.focus?.let { (offset, lines) -> center(editor, offset, lines) }
        refreshBanner()
        newTransition.start()
    }

    /** Scrolls so the changing lines sit in the middle of the editor; tall hunks start near the top. */
    private fun center(editor: Editor, offset: Int, lines: Int) {
        val document = editor.document
        val line = document.getLineNumber(offset.coerceIn(0, document.textLength))
        val visibleLines = max(1, editor.scrollingModel.visibleArea.height / max(1, editor.lineHeight))
        val middle = if (lines >= visibleLines * 4 / 5) line + visibleLines / 2 - 2 else line + (lines - 1) / 2
        editor.caretModel.moveToOffset(offset.coerceIn(0, document.textLength))
        editor.scrollingModel.scrollTo(LogicalPosition(middle.coerceAtLeast(0), 0), ScrollType.CENTER)
    }

    private fun openEditor(session: Active): Editor? {
        OpenFileDescriptor(project, session.target).navigate(true)
        return FileEditorManager.getInstance(project).getAllEditors(session.target)
            .firstNotNullOfOrNull { (it as? TextEditor)?.editor }
    }

    /** Jumps a running animation to its end and stops tracking it; its final highlights stay. */
    private fun disposeTransition() {
        transition?.let {
            it.finish()
            Disposer.dispose(it)
        }
        transition = null
    }

    private fun refreshBanner() {
        active?.let { EditorNotifications.getInstance(project).updateNotifications(it.target) }
    }

    // Projector mode

    private class SavedEditor(val fontSize: Float, val foldingOutline: Boolean, val intentionBulb: Boolean)

    private class ProjectorState(val hiddenToolWindows: List<String>) {
        val editors = mutableMapOf<EditorEx, SavedEditor>()
    }

    private var projector: ProjectorState? = null

    /** Turns projector mode on or off and applies it to the running presentation. */
    fun setProjectorMode(enabled: Boolean) {
        settings.projectorMode = enabled
        if (enabled) applyProjectorMode() else releaseProjectorMode()
        refreshBanner()
    }

    /** Enlarges the presented editor and hides tool windows; remembers everything it changes. */
    private fun applyProjectorMode() {
        val session = active ?: return
        val state = projector ?: ProjectorState(hideToolWindows()).also { projector = it }
        for (editor in EditorFactory.getInstance().getEditors(session.document, project)) {
            if (editor !is EditorEx || editor.isDisposed || editor in state.editors) continue
            state.editors[editor] = SavedEditor(
                editor.colorsScheme.editorFontSize2D,
                editor.settings.isFoldingOutlineShown,
                editor.settings.isShowIntentionBulb,
            )
            editor.putUserData(ZoomIndicatorManager.SUPPRESS_ZOOM_INDICATOR_ONCE, true)
            editor.setFontSize(settings.projectorFontSize.toFloat())
            editor.settings.isFoldingOutlineShown = false
            editor.settings.isShowIntentionBulb = false
        }
    }

    private fun releaseProjectorMode() {
        val state = projector ?: return
        projector = null
        for ((editor, saved) in state.editors) {
            if (editor.isDisposed) continue
            editor.putUserData(ZoomIndicatorManager.SUPPRESS_ZOOM_INDICATOR_ONCE, true)
            editor.setFontSize(saved.fontSize)
            editor.settings.isFoldingOutlineShown = saved.foldingOutline
            editor.settings.isShowIntentionBulb = saved.intentionBulb
        }
        val manager = ToolWindowManager.getInstance(project)
        state.hiddenToolWindows.forEach { manager.getToolWindow(it)?.show() }
    }

    private fun hideToolWindows(): List<String> {
        val manager = ToolWindowManager.getInstance(project)
        return manager.toolWindowIds.filter { manager.getToolWindow(it)?.isVisible == true }
            .onEach { manager.getToolWindow(it)?.hide() }
    }

    override fun dispose() {
        stopPlaying()
        transition = null
        active = null
    }

    companion object {
        fun getInstance(project: Project): PresentationSession = project.service()
    }
}
