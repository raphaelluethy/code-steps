package com.github.raphaelluethy.codesteps.recording

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory

/** While recording, every successful IDE commit that includes the recorded file adds a step named after the commit. */
class RecordOnCommit : CheckinHandlerFactory() {
    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler {
        val project = panel.project
        return object : CheckinHandler() {
            override fun checkinSuccessful() {
                val recording = project.serviceIfCreated<RecordingSession>() ?: return
                val message = panel.commitMessage
                val files = panel.virtualFiles.toList()
                ApplicationManager.getApplication().invokeLater({ recording.onCommit(message, files) }, project.disposed)
            }
        }
    }
}
