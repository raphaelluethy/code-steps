package com.github.raphaelluethy.codesteps.recording

import com.github.raphaelluethy.codesteps.storage.Presentation
import com.github.raphaelluethy.codesteps.storage.PresentationStorage
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotifications

/**
 * Records a refactoring as a presentation: the file's content when recording starts is the first step,
 * and every capture (explicit, on each IDE commit that includes the file, and on stop) adds a snapshot.
 * The result is an ordinary `.codesteps/` presentation; playback splits each step into hunks.
 * Must be used on the EDT.
 */
@Service(Service.Level.PROJECT)
class RecordingSession(private val project: Project) {

    class Recording internal constructor(presentation: Presentation, val file: VirtualFile, val document: Document) {
        var presentation: Presentation = presentation
            internal set
        internal var lastText: String = document.text
        val stepCount: Int get() = presentation.manifest.steps.size
    }

    var recording: Recording? = null
        private set

    val isRecording: Boolean get() = recording != null

    /** Creates a new presentation for [file] under [root] and captures its current content as the first step. */
    fun start(root: VirtualFile, file: VirtualFile, title: String): Recording {
        stop()
        val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file)) { "${file.path} is not a text file" }
        val presentation = write("Code Steps: Start Recording") {
            val created = PresentationStorage.create(root, file, title)
            PresentationStorage.addStep(created, "Start", document.text)
        }
        return Recording(presentation, file, document).also {
            recording = it
            refreshBanner()
        }
    }

    /** Adds the file's current content as a step unless it equals the last one. Returns false when nothing changed. */
    fun capture(title: String? = null): Boolean {
        val current = recording ?: return false
        val text = current.document.text
        if (text == current.lastText) return false
        val stepTitle = title?.trim()?.ifEmpty { null } ?: "Step ${current.stepCount + 1}"
        current.presentation = write("Code Steps: Capture Step") {
            PresentationStorage.addStep(current.presentation, stepTitle, text)
        }
        current.lastText = text
        refreshBanner()
        return true
    }

    /** Called after an IDE commit; captures a step named after the commit when the commit includes the file. */
    fun onCommit(message: String, committedFiles: Collection<VirtualFile>) {
        val current = recording ?: return
        if (current.file !in committedFiles) return
        capture(message.lineSequence().firstOrNull { it.isNotBlank() })
    }

    /** Captures the final content and ends the recording. Returns the recorded presentation. */
    fun stop(): Presentation? {
        val current = recording ?: return null
        capture()
        recording = null
        EditorNotifications.getInstance(project).updateNotifications(current.file)
        return current.presentation
    }

    private fun <T> write(name: String, block: () -> T): T =
        WriteCommandAction.writeCommandAction(project).withName(name).compute<T, RuntimeException> { block() }

    private fun refreshBanner() {
        recording?.let { EditorNotifications.getInstance(project).updateNotifications(it.file) }
    }

    companion object {
        fun getInstance(project: Project): RecordingSession = project.service()
    }
}
