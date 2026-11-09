package com.github.raphaelluethy.codesteps.recording

import com.github.raphaelluethy.codesteps.storage.ManifestFormat
import com.github.raphaelluethy.codesteps.storage.PresentationStorage
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class RecordingSessionTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            RecordingSession.getInstance(project).stop()
        } finally {
            super.tearDown()
        }
    }

    fun `test records the start, captures, commits, and the final state as steps`() {
        val file = myFixture.addFileToProject("src/Greeter.txt", "hello\n").virtualFile
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        fun edit(text: String) = WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
        val recording = RecordingSession.getInstance(project)
        val root = myFixture.findFileInTempDir(".")

        recording.start(root, file, "Greeter refactor")
        assertFalse("nothing changed yet", recording.capture("Too early"))

        edit("hello\nworld\n")
        assertTrue(recording.capture("Add world"))

        edit("hello, world\n")
        recording.onCommit("Join the lines\n\nLonger description", listOf(myFixture.findFileInTempDir("src")))
        recording.onCommit("Join the lines\n\nLonger description", listOf(file))

        edit("Hello, world!\n")
        val presentation = recording.stop()!!
        assertFalse(recording.isRecording)

        val dir = myFixture.findFileInTempDir(".codesteps/greeter")
        val manifest = ManifestFormat.parse(String(dir.findChild(ManifestFormat.FILE_NAME)!!.contentsToByteArray()))
        assertEquals("Greeter refactor", manifest.title)
        assertEquals("src/Greeter.txt", manifest.target)
        assertEquals(listOf("Start", "Add world", "Join the lines", "Step 4"), manifest.steps.map { it.title })
        val texts = manifest.steps.indices.map { PresentationStorage.stepText(presentation, it) }
        assertEquals(listOf("hello\n", "hello\nworld\n", "hello, world\n", "Hello, world!\n"), texts)
        assertTrue(manifest.steps.all { it.file.endsWith(".txt") })
    }
}
