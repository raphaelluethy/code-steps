package com.github.raphaelluethy.codesteps.storage

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.impl.DirectoryIndexExcludePolicy
import com.intellij.openapi.vfs.VfsUtilCore

/**
 * Excludes `.codesteps/` from indexing. Step files are copies of real sources, so indexing them would
 * duplicate classes and symbols. Excluded files still open with syntax highlighting and stay under version control.
 */
class CodeStepsExcludePolicy(private val project: Project) : DirectoryIndexExcludePolicy {
    override fun getExcludeUrlsForProject(): Array<String> {
        val base = project.basePath ?: return emptyArray()
        return arrayOf(VfsUtilCore.pathToUrl("$base/${PresentationStorage.ROOT_DIR}"))
    }
}
