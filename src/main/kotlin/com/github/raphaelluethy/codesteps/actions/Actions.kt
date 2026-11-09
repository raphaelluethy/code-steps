package com.github.raphaelluethy.codesteps.actions

import com.github.raphaelluethy.codesteps.presentation.PresentationSession
import com.github.raphaelluethy.codesteps.recording.RecordingSession
import com.github.raphaelluethy.codesteps.settings.CodeStepsSettings
import com.github.raphaelluethy.codesteps.storage.ManifestException
import com.github.raphaelluethy.codesteps.storage.Presentation
import com.github.raphaelluethy.codesteps.storage.PresentationStorage
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer

private fun notify(
    project: Project,
    message: String,
    type: NotificationType = NotificationType.INFORMATION,
    action: NotificationAction? = null,
) {
    NotificationGroupManager.getInstance().getNotificationGroup("Code Steps")
        .createNotification(message, type)
        .apply { if (action != null) addAction(action) }
        .notify(project)
}

/** Runs [block] and reports broken or missing presentation files as a notification instead of an exception. */
private fun reportingErrors(project: Project, block: () -> Unit) {
    try {
        block()
    } catch (e: ManifestException) {
        notify(project, e.message ?: "Invalid presentation", NotificationType.ERROR)
    }
}

private fun choosePresentation(project: Project, presentations: List<Presentation>, title: String, onChosen: (Presentation) -> Unit) {
    when (presentations.size) {
        0 -> notify(
            project,
            "No presentations found in ${PresentationStorage.ROOT_DIR}/. Open a file and use Tools | Code Steps | " +
                "Start Recording or Capture Step to create one.",
            NotificationType.WARNING,
        )
        1 -> onChosen(presentations.single())
        else -> JBPopupFactory.getInstance()
            .createPopupChooserBuilder(presentations)
            .setTitle(title)
            .setRenderer(textListCellRenderer { p -> "${p.title}  —  ${p.manifest.target} (${p.manifest.steps.size} steps)" })
            .setItemChosenCallback(onChosen)
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }
}

private fun startPresentationAction(project: Project, presentation: Presentation) =
    NotificationAction.createSimpleExpiring("Start presentation") {
        reportingErrors(project) { PresentationSession.getInstance(project).start(presentation) }
    }

private fun AnActionEvent.session(): PresentationSession? = project?.serviceIfCreated<PresentationSession>()

private fun AnActionEvent.recording(): RecordingSession? = project?.serviceIfCreated<RecordingSession>()

/** Base for actions that only make sense while a presentation runs; they hide themselves from toolbars otherwise. */
abstract class SessionAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    protected open fun isEnabled(session: PresentationSession.Active): Boolean = true

    protected abstract fun perform(project: Project, session: PresentationSession)

    override fun update(e: AnActionEvent) {
        val active = e.session()?.active
        e.presentation.isEnabled = active != null && isEnabled(active)
        if (e.isFromActionToolbar) e.presentation.isVisible = active != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        reportingErrors(project) { perform(project, PresentationSession.getInstance(project)) }
    }
}

class NextStepAction : SessionAction() {
    override fun isEnabled(session: PresentationSession.Active) = !session.isAtEnd
    override fun perform(project: Project, session: PresentationSession) = session.nextStep()
}

class PreviousStepAction : SessionAction() {
    override fun isEnabled(session: PresentationSession.Active) = !session.isAtStart
    override fun perform(project: Project, session: PresentationSession) = session.previousStep()
}

class NextHunkAction : SessionAction() {
    override fun isEnabled(session: PresentationSession.Active) = !session.isAtEnd
    override fun perform(project: Project, session: PresentationSession) = session.nextHunk()
}

class PreviousHunkAction : SessionAction() {
    override fun isEnabled(session: PresentationSession.Active) = !session.isAtStart
    override fun perform(project: Project, session: PresentationSession) = session.previousHunk()
}

class FirstStepAction : SessionAction() {
    override fun isEnabled(session: PresentationSession.Active) = !session.isAtStart
    override fun perform(project: Project, session: PresentationSession) = session.first()
}

class LastStepAction : SessionAction() {
    override fun isEnabled(session: PresentationSession.Active) = !session.isAtEnd
    override fun perform(project: Project, session: PresentationSession) = session.last()
}

class PlayPauseAction : SessionAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        val session = e.session()
        val playing = session?.isPlaying == true
        e.presentation.text = if (playing) "Pause" else "Play"
        e.presentation.description = if (playing) "Pause after the current hunk" else "Play the hunks one at a time"
        e.presentation.icon = if (playing) AllIcons.Actions.Pause else AllIcons.Actions.Execute
        if (!playing) e.presentation.isEnabled = session?.active?.isAtEnd == false
    }

    override fun perform(project: Project, session: PresentationSession) = session.togglePlay()
}

class FasterAction : SessionAction() {
    override fun isEnabled(session: PresentationSession.Active) =
        CodeStepsSettings.getInstance().state.speedPercent < CodeStepsSettings.SPEEDS.last()
    override fun perform(project: Project, session: PresentationSession) = session.changeSpeed(+1)
}

class SlowerAction : SessionAction() {
    override fun isEnabled(session: PresentationSession.Active) =
        CodeStepsSettings.getInstance().state.speedPercent > CodeStepsSettings.SPEEDS.first()
    override fun perform(project: Project, session: PresentationSession) = session.changeSpeed(-1)
}

/** Picks the playback speed from a list; used by the banner. */
class ChooseSpeedAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
        e.presentation.text = "Speed ${CodeStepsSettings.formatSpeed(CodeStepsSettings.getInstance().state.speedPercent)}"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(CodeStepsSettings.SPEEDS)
            .setTitle("Playback Speed")
            .setRenderer(textListCellRenderer { CodeStepsSettings.formatSpeed(it) })
            .setSelectedValue(CodeStepsSettings.getInstance().state.speedPercent, true)
            .setItemChosenCallback { PresentationSession.getInstance(project).setSpeed(it) }
            .createPopup()
            .showInBestPositionFor(e.dataContext)
    }
}

class StopPresentationAction : SessionAction() {
    override fun perform(project: Project, session: PresentationSession) = session.stop()
}

class UpdateStepAction : SessionAction() {
    override fun isEnabled(session: PresentationSession.Active) = session.hunk == 0
    override fun perform(project: Project, session: PresentationSession) {
        session.saveEditorToCurrentStep()
        val active = session.active ?: return
        notify(project, "Saved the editor content to step ${active.index + 1}: ${active.stepTitle}")
    }
}

/** Large editor font, hidden tool windows, and a large banner, for presenting on a projector. */
class ToggleProjectorModeAction : DumbAwareToggleAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        super.update(e)
        if (e.isFromActionToolbar) e.presentation.isVisible = e.session()?.isActive == true
    }

    override fun isSelected(e: AnActionEvent) = CodeStepsSettings.getInstance().state.projectorMode

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project
        if (project == null) {
            CodeStepsSettings.getInstance().state.projectorMode = state
        } else {
            PresentationSession.getInstance(project).setProjectorMode(state)
        }
    }
}

/**
 * Starts the presentation of the current file. From a manifest or step file, starts that presentation
 * (at that step); otherwise offers every presentation in the project.
 */
class StartPresentationAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && e.recording()?.isRecording != true
        if (e.isFromActionToolbar) {
            // Keep the main toolbar clean in projects without presentations.
            e.presentation.isVisible = project != null &&
                e.session()?.isActive != true && e.recording()?.isRecording != true &&
                PresentationStorage.projectRoot(project)?.findChild(PresentationStorage.ROOT_DIR) != null
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val root = PresentationStorage.projectRoot(project) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val owner = file?.let(PresentationStorage::containing)
        if (owner != null) {
            val index = owner.manifest.steps.indexOfFirst { it.file == file.name }.coerceAtLeast(0)
            reportingErrors(project) { PresentationSession.getInstance(project).start(owner, index) }
            return
        }
        val candidates = file?.let { PresentationStorage.forTarget(root, it) }.orEmpty()
            .ifEmpty { PresentationStorage.list(root) }
        choosePresentation(project, candidates, "Start Presentation") { presentation ->
            reportingErrors(project) { PresentationSession.getInstance(project).start(presentation) }
        }
    }
}

/** Starts recording the current file: its content now is step 1, and each capture or commit adds a step. */
class StartRecordingAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabled = e.project != null && e.getData(CommonDataKeys.EDITOR) != null && file != null &&
            PresentationStorage.containing(file) == null && e.recording()?.isRecording != true &&
            e.session()?.isActive != true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val root = PresentationStorage.projectRoot(project) ?: return
        if (!VfsUtilCore.isAncestor(root, file, true)) {
            notify(project, "Only files inside the project directory can be recorded.", NotificationType.WARNING)
            return
        }
        val title = Messages.showInputDialog(
            project,
            "Presentation title. Edit the file as usual; Capture Step and IDE commits add steps, Stop Recording adds the last one.",
            "Start Recording", null, file.name, null,
        )?.trim()?.ifEmpty { file.name } ?: return
        RecordingSession.getInstance(project).start(root, file, title)
    }
}

class StopRecordingAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.recording()?.isRecording == true
        if (e.isFromActionToolbar) e.presentation.isVisible = e.presentation.isEnabled
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val presentation = RecordingSession.getInstance(project).stop() ?: return
        val root = PresentationStorage.projectRoot(project)
        val path = root?.let { VfsUtilCore.getRelativePath(presentation.dir, it) } ?: presentation.dir.path
        notify(
            project,
            "Recorded '${presentation.title}' with ${presentation.manifest.steps.size} steps in $path/",
            action = startPresentationAction(project, presentation),
        )
    }
}

/**
 * Saves the editor content as a new step. While recording, the step is added to the recording; while presenting,
 * it is inserted after the current position; otherwise it is appended to the file's presentation, created on first use.
 */
class CaptureStepAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabled = e.project != null && e.getData(CommonDataKeys.EDITOR) != null &&
            file != null && PresentationStorage.containing(file) == null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val recording = RecordingSession.getInstance(project)
        val recorded = recording.recording
        if (recorded != null && recorded.file == file) {
            val title = askTitle(project, "Step ${recorded.stepCount + 1}") ?: return
            if (!recording.capture(title)) notify(project, "Nothing changed since the last captured step.")
            return
        }

        val session = PresentationSession.getInstance(project)
        val active = session.active
        if (active != null && active.target == file) {
            val title = askTitle(project, "Step ${active.index + 2}") ?: return
            reportingErrors(project) { session.insertEditorAsStepAfterCurrent(title) }
            return
        }

        val root = PresentationStorage.projectRoot(project) ?: return
        if (!VfsUtilCore.isAncestor(root, file, true)) {
            notify(project, "Only files inside the project directory can be presented.", NotificationType.WARNING)
            return
        }
        val existing = PresentationStorage.forTarget(root, file)
        val save = save@{ presentation: Presentation? ->
            val stepCount = presentation?.manifest?.steps?.size ?: 0
            val title = askTitle(project, "Step ${stepCount + 1}") ?: return@save
            val text = editor.document.text
            val updated = WriteCommandAction.writeCommandAction(project)
                .withName("Code Steps: Capture Step")
                .compute<Presentation, RuntimeException> {
                    val target = presentation ?: PresentationStorage.create(root, file, file.name)
                    PresentationStorage.addStep(target, title, text)
                }
            val stepFile = updated.manifest.steps.last().file
            notify(
                project,
                "Added step ${updated.manifest.steps.size} to '${updated.title}' as " +
                    "${VfsUtilCore.getRelativePath(updated.dir, root)}/$stepFile",
            )
        }
        if (existing.isEmpty()) save(null) else choosePresentation(project, existing, "Add Step To") { save(it) }
    }

    private fun askTitle(project: Project, default: String): String? =
        Messages.showInputDialog(project, "Step title:", "Capture Step", null, default, null)
            ?.trim()?.ifEmpty { default }
}

class ToggleHighlightAction : DumbAwareToggleAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent) = CodeStepsSettings.getInstance().state.highlightChanges

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        CodeStepsSettings.getInstance().state.highlightChanges = state
        if (!state) e.session()?.clearHighlights()
    }
}
