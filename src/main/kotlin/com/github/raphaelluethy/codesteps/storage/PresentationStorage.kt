package com.github.raphaelluethy.codesteps.storage

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/** A presentation folder, such as `.codesteps/tokenizer/`, and its parsed manifest. */
data class Presentation(val dir: VirtualFile, val manifest: Manifest) {
    /** The folder that contains `.codesteps/`; [Manifest.target] is relative to it. */
    val root: VirtualFile get() = dir.parent.parent

    val title: String get() = manifest.title.ifBlank { dir.name }

    fun findTarget(): VirtualFile? = root.findFileByRelativePath(manifest.target)
}

/**
 * A presentation cannot be loaded, started or changed, for example because its manifest is broken or a file is
 * missing. The message is shown to the user.
 */
class PresentationException(message: String) : Exception(message)

/**
 * Stores presentations as plain files under `<project>/.codesteps/<name>/`:
 * a `presentation.yaml` manifest plus one complete snapshot file per step.
 *
 * Methods that write need a write action.
 */
object PresentationStorage {
    const val ROOT_DIR = ".codesteps"

    private val LOG = logger<PresentationStorage>()

    fun projectRoot(project: Project): VirtualFile? = project.guessProjectDir()

    fun list(root: VirtualFile): List<Presentation> {
        val codesteps = root.findChild(ROOT_DIR) ?: return emptyList()
        return codesteps.children
            .filter { it.isDirectory && it.findChild(ManifestFormat.FILE_NAME) != null }
            .sortedBy { it.name }
            .mapNotNull { dir ->
                try {
                    load(dir)
                } catch (e: PresentationException) {
                    LOG.warn("Skipping ${dir.path}: ${e.message}")
                    null
                }
            }
    }

    fun forTarget(root: VirtualFile, file: VirtualFile): List<Presentation> {
        val path = VfsUtilCore.getRelativePath(file, root) ?: return emptyList()
        return list(root).filter { it.manifest.target == path }
    }

    /** The presentation that owns [file], if [file] is a manifest or step file. */
    fun containing(file: VirtualFile): Presentation? {
        val dir = file.parent ?: return null
        if (dir.parent?.name != ROOT_DIR || dir.findChild(ManifestFormat.FILE_NAME) == null) return null
        return try {
            load(dir)
        } catch (_: PresentationException) {
            null
        }
    }

    fun load(dir: VirtualFile): Presentation {
        val manifestFile = dir.findChild(ManifestFormat.FILE_NAME)
            ?: throw PresentationException("${dir.path} has no ${ManifestFormat.FILE_NAME}")
        return Presentation(dir, ManifestFormat.parse(readText(manifestFile)))
    }

    fun stepText(presentation: Presentation, index: Int): String {
        val step = presentation.manifest.steps[index]
        val file = presentation.dir.findChild(step.file)
            ?: throw PresentationException("Step file '${step.file}' is missing in ${presentation.dir.path}")
        return readText(file)
    }

    /** Creates `.codesteps/<name>/` for [target], which must be inside [root]. */
    fun create(root: VirtualFile, target: VirtualFile, title: String): Presentation {
        val targetPath = requireNotNull(VfsUtilCore.getRelativePath(target, root)) {
            "${target.path} is not inside ${root.path}"
        }
        val codesteps = root.findChild(ROOT_DIR) ?: root.createChildDirectory(this, ROOT_DIR)
        val name = uniqueName(codesteps, slug(target.nameWithoutExtension).ifEmpty { "presentation" }, "")
        val dir = codesteps.createChildDirectory(this, name)
        val manifest = Manifest(title, targetPath, emptyList())
        writeText(dir.createChildData(this, ManifestFormat.FILE_NAME), ManifestFormat.write(manifest))
        return Presentation(dir, manifest)
    }

    /** Saves [text] as a new step at [index] (appends by default) and returns the updated presentation. */
    fun addStep(presentation: Presentation, title: String, text: String, index: Int = presentation.manifest.steps.size): Presentation {
        val steps = presentation.manifest.steps
        val number = steps.mapNotNull { it.file.substringBefore('-').toIntOrNull() }.maxOrNull()?.plus(1) ?: (steps.size + 1)
        val extension = presentation.manifest.target.substringAfterLast('/').substringAfterLast('.', "")
        val suffix = if (extension.isEmpty()) "" else ".$extension"
        val base = "%02d-%s".format(number, slug(title).ifEmpty { "step" })
        val fileName = uniqueName(presentation.dir, base, suffix) + suffix
        writeText(presentation.dir.createChildData(this, fileName), text)
        val newSteps = steps.toMutableList().apply { add(index.coerceIn(0, size), Step(fileName, title)) }
        return saveManifest(presentation, presentation.manifest.copy(steps = newSteps))
    }

    fun updateStep(presentation: Presentation, index: Int, text: String) {
        val step = presentation.manifest.steps[index]
        val file = presentation.dir.findChild(step.file) ?: presentation.dir.createChildData(this, step.file)
        writeText(file, text)
    }

    private fun saveManifest(presentation: Presentation, manifest: Manifest): Presentation {
        writeText(presentation.dir.findOrCreateChildData(this, ManifestFormat.FILE_NAME), ManifestFormat.write(manifest))
        return presentation.copy(manifest = manifest)
    }

    /** Reads through an open document, if any, so unsaved edits to step files are used. */
    private fun readText(file: VirtualFile): String {
        val document = FileDocumentManager.getInstance().getCachedDocument(file)
        return document?.text ?: StringUtil.convertLineSeparators(VfsUtilCore.loadText(file))
    }

    private fun writeText(file: VirtualFile, text: String) {
        val documents = FileDocumentManager.getInstance()
        val document = documents.getCachedDocument(file)
        if (document != null) {
            document.setText(text)
            documents.saveDocument(document)
        } else {
            VfsUtil.saveText(file, text)
        }
    }

    private fun uniqueName(dir: VirtualFile, base: String, suffix: String): String {
        var name = base
        var counter = 2
        while (dir.findChild(name + suffix) != null) name = "$base-${counter++}"
        return name
    }

    private fun slug(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trimEnd('-')
}
