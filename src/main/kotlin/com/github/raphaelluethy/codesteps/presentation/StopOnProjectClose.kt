package com.github.raphaelluethy.codesteps.presentation

import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener

/** Restores the presented file before the project closes, so a step snapshot is not left in the working tree. */
class StopOnProjectClose : ProjectCloseListener {
    override fun projectClosingBeforeSave(project: Project) {
        project.serviceIfCreated<PresentationSession>()?.stop()
    }
}
