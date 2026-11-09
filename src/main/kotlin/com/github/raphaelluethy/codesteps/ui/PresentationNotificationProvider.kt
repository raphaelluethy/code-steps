package com.github.raphaelluethy.codesteps.ui

import com.github.raphaelluethy.codesteps.presentation.PresentationSession
import com.github.raphaelluethy.codesteps.recording.RecordingSession
import com.github.raphaelluethy.codesteps.settings.CodeStepsSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.UIUtil
import java.awt.Font
import java.util.function.Function
import javax.swing.JComponent

/** Shows the presentation position and controls, or the recording state, above the file's editor. */
class PresentationNotificationProvider : EditorNotificationProvider, DumbAware {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        project.serviceIfCreated<RecordingSession>()?.recording?.takeIf { it.file == file }?.let { recording ->
            return Function { fileEditor ->
                EditorNotificationPanel(fileEditor, EditorNotificationPanel.Status.Error).apply {
                    icon(AllIcons.Ide.Macro.Recording_1)
                    text = "Recording '${recording.presentation.title}': ${recording.stepCount} " +
                        (if (recording.stepCount == 1) "step" else "steps") + " so far. " +
                        "Capture Step and IDE commits add steps."
                    createActionLabel("Capture step") {
                        val title = Messages.showInputDialog(
                            project, "Step title:", "Capture Step", null, "Step ${recording.stepCount + 1}", null,
                        ) ?: return@createActionLabel
                        RecordingSession.getInstance(project).capture(title)
                    }
                    createActionLabel("Stop recording", "CodeSteps.StopRecording")
                }
            }
        }

        val session = project.serviceIfCreated<PresentationSession>() ?: return null
        val active = session.active ?: return null
        if (active.target != file) return null
        val settings = CodeStepsSettings.getInstance().state
        val projector = settings.projectorMode
        val position = if (active.hunk == 0) {
            "Step ${active.index + 1} of ${active.stepCount}: ${active.stepTitle}"
        } else {
            "Step ${active.index + 1} → ${active.index + 2}: ${active.nextStepTitle} · " +
                "hunk ${active.hunk} of ${active.hunks().size}"
        }
        val playing = if (session.isPlaying) " · playing" else ""
        return Function { fileEditor ->
            EditorNotificationPanel(fileEditor, EditorNotificationPanel.Status.Info).apply {
                icon(if (session.isPlaying) AllIcons.Actions.Execute else AllIcons.Actions.Play_forward)
                text = (if (projector) "" else "${active.presentation.title} · ") + position + playing
                createActionLabel("◀ Step", "CodeSteps.Previous")
                createActionLabel("◀ Hunk", "CodeSteps.PreviousHunk")
                createActionLabel(if (session.isPlaying) "Pause" else "Play", "CodeSteps.PlayPause")
                createActionLabel("Hunk ▶", "CodeSteps.NextHunk")
                createActionLabel("Step ▶", "CodeSteps.Next")
                createActionLabel("Speed ${CodeStepsSettings.formatSpeed(settings.speedPercent)}", "CodeSteps.ChooseSpeed")
                createActionLabel(if (projector) "Exit projector" else "Projector", "CodeSteps.ToggleProjectorMode")
                createActionLabel("Stop", "CodeSteps.Stop")
                if (projector) enlarge(this, settings.projectorFontSize)
            }
        }
    }

    /** Scales every label and link of the banner so it stays readable from the back of a room. */
    private fun enlarge(panel: JComponent, editorFontSize: Int) {
        val size = maxOf(JBFont.label().size2D * 1.5f, editorFontSize * 0.7f)
        for (component in UIUtil.uiTraverser(panel).filter(JComponent::class.java)) {
            component.font = (component.font ?: JBFont.label()).deriveFont(Font.BOLD, size)
        }
    }
}
