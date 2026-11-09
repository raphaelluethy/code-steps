package com.github.raphaelluethy.codesteps.presentation

import com.github.raphaelluethy.codesteps.settings.CodeStepsSettings
import com.github.raphaelluethy.codesteps.settings.TransitionStyle
import com.github.raphaelluethy.codesteps.storage.ManifestFormat
import com.github.raphaelluethy.codesteps.storage.PresentationStorage
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Color

class PresentationSessionTest : BasePlatformTestCase() {
    private val step1 = "Shopping list\n- milk\n- bread\n"
    private val step2 = "Shopping list\n- oat milk\n- bread\n- apples\n"
    private val step3 = "Shopping list\n- apples\n"
    private val original = "work in progress\n"

    private val settings get() = CodeStepsSettings.getInstance().state
    private val session get() = PresentationSession.getInstance(project)

    override fun setUp() {
        super.setUp()
        CodeStepsSettings.getInstance().loadState(CodeStepsSettings.State())
    }

    override fun tearDown() {
        try {
            session.stop()
            CodeStepsSettings.getInstance().loadState(CodeStepsSettings.State())
        } finally {
            super.tearDown()
        }
    }

    /** A plain-text presentation, so nothing depends on a language plugin. */
    private fun createPresentation() = myFixture.run {
        addFileToProject("notes/list.txt", original)
        addFileToProject(
            ".codesteps/list/presentation.yaml",
            "codesteps: 1\ntitle: \"List\"\ntarget: \"notes/list.txt\"\nsteps:\n" +
                "  - file: \"01.txt\"\n    title: \"One\"\n" +
                "  - file: \"02.txt\"\n    title: \"Two\"\n" +
                "  - file: \"03.txt\"\n    title: \"Three\"\n",
        )
        addFileToProject(".codesteps/list/01.txt", step1)
        addFileToProject(".codesteps/list/02.txt", step2)
        addFileToProject(".codesteps/list/03.txt", step3)
        PresentationStorage.load(findFileInTempDir(".codesteps/list"))
    }

    private val targetText: String
        get() = FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir("notes/list.txt"))!!.text

    /** Text covered by highlighters painted in [color], in document order. */
    private fun marked(document: Document, color: Color): List<String> =
        DocumentMarkupModel.forDocument(document, project, true).allHighlighters
            .filter { it.getTextAttributes(null)?.backgroundColor == color }
            .sortedBy { it.startOffset }
            .map { document.charsSequence.substring(it.startOffset, it.endOffset) }

    fun `test steps forward and back in the target document and restores it on stop`() {
        settings.transition = TransitionStyle.INSTANT
        session.start(createPresentation())
        assertEquals(step1, targetText)

        session.nextStep()
        assertEquals(step2, targetText)
        session.last()
        assertEquals(step3, targetText)
        session.nextStep() // already at the last step
        assertEquals(step3, targetText)
        session.previousStep()
        assertEquals(step2, targetText)
        session.first()
        assertEquals(step1, targetText)

        session.stop()
        assertFalse(session.isActive)
        assertEquals(original, targetText)
    }

    fun `test hunks play one at a time, top to bottom, and step back across steps`() {
        settings.transition = TransitionStyle.INSTANT
        session.start(createPresentation())
        val active = session.active!!
        assertEquals(2, active.hunks().size)

        session.nextHunk()
        assertEquals("Shopping list\n- oat milk\n- bread\n", targetText)
        assertEquals(0 to 1, active.index to active.hunk)
        session.nextHunk() // the last hunk of a change reaches the next step
        assertEquals(step2, targetText)
        assertEquals(1 to 0, active.index to active.hunk)

        session.previousHunk()
        assertEquals("Shopping list\n- oat milk\n- bread\n", targetText)
        session.previousHunk()
        assertEquals(step1, targetText)
        session.previousHunk() // already at the start
        assertEquals(step1, targetText)
        assertTrue(active.isAtStart)

        session.last()
        session.previousHunk() // crosses back into the change from step 2 to step 3
        assertEquals(1, active.index)
        assertEquals(active.hunks().size - 1, active.hunk)
    }

    fun `test highlight mode marks every hunk of a step and starts clean on the next step`() {
        settings.transition = TransitionStyle.HIGHLIGHT
        settings.highlightChanges = false // the highlight style marks changes regardless
        val yellow = CodeStepsSettings.parseColor(settings.highlightColor)
        session.start(createPresentation())
        val document = session.active!!.document

        session.nextHunk()
        assertEquals(listOf("- oat milk"), marked(document, yellow))
        session.nextHunk()
        assertEquals(listOf("- oat milk", "- apples"), marked(document, yellow))

        session.nextStep() // step 3 only removes lines: nothing inserted to mark, old marks gone
        assertEquals(step3, targetText)
        assertEmpty(marked(document, yellow))
    }

    fun `test the highlight color comes from the settings`() {
        settings.transition = TransitionStyle.HIGHLIGHT
        settings.highlightColor = "00FFFF"
        session.start(createPresentation())
        session.nextStep()
        assertEquals(listOf("- oat milk", "- apples"), marked(session.active!!.document, Color(0x00FFFF)))
    }

    fun `test play stops after one step unless it may continue into the next steps`() {
        settings.transition = TransitionStyle.HIGHLIGHT
        settings.hunkPauseMs = 5
        session.start(createPresentation())

        session.togglePlay()
        waitWhilePlaying()
        assertEquals(step2, targetText)
        assertEquals(1, session.active!!.index)

        settings.playAcrossSteps = true
        session.first()
        session.togglePlay()
        waitWhilePlaying()
        assertEquals(step3, targetText)
        assertTrue(session.active!!.isAtEnd)
    }

    private fun waitWhilePlaying() {
        val deadline = System.currentTimeMillis() + 10_000
        while (session.isPlaying && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(5)
        }
        assertFalse("still playing", session.isPlaying)
    }

    fun `test capturing while presenting inserts a step after the current one`() {
        settings.transition = TransitionStyle.INSTANT
        session.start(createPresentation())
        val document = session.active!!.document
        WriteCommandAction.runWriteCommandAction(project) { document.setText("Shopping list\n- milk\n- bread\n- eggs\n") }

        session.insertEditorAsStepAfterCurrent("Eggs")
        val manifest = ManifestFormat.parse(
            String(myFixture.findFileInTempDir(".codesteps/list/presentation.yaml").contentsToByteArray()),
        )
        assertEquals(listOf("One", "Eggs", "Two", "Three"), manifest.steps.map { it.title })
        assertEquals("04-eggs.txt", manifest.steps[1].file)
        assertEquals(1, session.active!!.index)

        session.previousStep()
        session.nextStep()
        assertEquals("Shopping list\n- milk\n- bread\n- eggs\n", targetText)
        session.nextStep()
        assertEquals(step2, targetText)
    }

    fun `test hunk style fades out a rewritten block as a whole, not word by word`() {
        val old = "fun total(items) {\n  var sum = 0\n  for (item in items) sum += item.price\n  return sum\n}\n"
        val new = "fun total(items) {\n  return items.sumOf { item -> item.price }\n}\n"
        val document = myFixture.configureByText("total.txt", old).fileDocument
        val transition = StepTransition(
            project, document, new,
            StepTransition.Options(TransitionStyle.HUNKS, true, 100, 10, 60_000),
        )
        transition.start()
        assertFalse(transition.advanceTo(50))
        val markup = DocumentMarkupModel.forDocument(document, project, true)
        val covered = BooleanArray(old.length)
        markup.allHighlighters.filter { it.getTextAttributes(null)?.backgroundColor != null }
            .forEach { for (i in it.startOffset until it.endOffset) covered[i] = true }
        // Every visible character of the three rewritten lines fades, including words both versions share.
        val bodyStart = old.indexOf("  var")
        val bodyEnd = old.indexOf("}\n", old.indexOf("return"))
        for (i in bodyStart until bodyEnd) {
            if (!old[i].isWhitespace()) assertTrue("'${old[i]}' at $i is not fading", covered[i])
        }
        assertFalse(transition.advanceTo(150))
        assertEquals(new, document.text) // the new block appears as one unit
        assertTrue(transition.advanceTo(200))
        Disposer.dispose(transition)
    }

    fun `test typewriter animation types in order and ends on the step text`() {
        session.start(createPresentation())
        val document = session.active!!.document
        val transition = StepTransition(
            project, document, step2,
            StepTransition.Options(TransitionStyle.TYPEWRITER, true, 100, 10, 60_000),
        )
        // Nothing is removed, so there is no fade-out; "oat " and "- apples\n" are 13 characters at 10 per second.
        assertEquals(1300L, transition.durationMs)
        transition.start()

        assertFalse(transition.advanceTo(400))
        assertEquals("Shopping list\n- oat milk\n- bread\n", document.text)
        // A new line opens first, so the text after it keeps its own line while typing.
        assertFalse(transition.advanceTo(800))
        assertEquals("Shopping list\n- oat milk\n- bread\n- a\n", document.text)
        assertTrue(transition.advanceTo(1300))
        assertEquals(step2, document.text)
        Disposer.dispose(transition)
    }

    fun `test fade animation removes text before inserting and survives external edits`() {
        session.start(createPresentation())
        val document = session.active!!.document
        val transition = StepTransition(
            project, document, step3,
            StepTransition.Options(TransitionStyle.FADE, true, 200, 10, 1000),
        )
        transition.start()
        assertFalse(transition.advanceTo(100))
        assertEquals(step1, document.text) // removed text still fading out
        assertFalse(transition.advanceTo(250))
        assertEquals(step3, document.text) // inserted text present while fading in

        WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "typed by hand ") }
        assertTrue(transition.advanceTo(300))
        assertEquals(step3, document.text)
        Disposer.dispose(transition)
    }
}
