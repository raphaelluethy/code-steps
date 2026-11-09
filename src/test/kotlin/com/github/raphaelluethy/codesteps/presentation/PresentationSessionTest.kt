package com.github.raphaelluethy.codesteps.presentation

import com.github.raphaelluethy.codesteps.settings.CodeStepsSettings
import com.github.raphaelluethy.codesteps.storage.ManifestFormat
import com.github.raphaelluethy.codesteps.storage.PresentationStorage
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Color
import kotlin.math.abs

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

    /** Text of the highlighters tinted with [color], in document order. A tint is a light wash of the color with the same hue. */
    private fun marked(document: Document, color: Color): List<String> =
        DocumentMarkupModel.forDocument(document, project, true).allHighlighters
            .filter { it.getTextAttributes(null)?.backgroundColor?.let { tint -> abs(hue(tint) - hue(color)) < 0.02 } == true }
            .sortedBy { it.startOffset }
            .map { document.charsSequence.substring(it.startOffset, it.endOffset) }

    private fun hue(color: Color): Float = Color.RGBtoHSB(color.red, color.green, color.blue, null)[0]

    fun `test steps forward and back in the target document and restores it on stop`() {
        settings.animate = false
        session.start(createPresentation())
        assertEquals(step1, targetText)

        session.nextStep()
        assertEquals(step2, targetText)
        session.nextStep()
        assertEquals(step3, targetText)
        session.nextStep() // already at the last step
        assertEquals(step3, targetText)
        session.previousStep()
        assertEquals(step2, targetText)
        session.previousStep()
        assertEquals(step1, targetText)

        session.stop()
        assertFalse(session.isActive)
        assertEquals(original, targetText)
    }

    fun `test hunks play one at a time, top to bottom, and step back across steps`() {
        settings.animate = false
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

        session.nextStep()
        session.nextStep()
        session.previousHunk() // crosses back into the change from step 2 to step 3
        assertEquals(1, active.index)
        assertEquals(active.hunks().size - 1, active.hunk)
    }

    fun `test the changes of every hunk of a step stay tinted and the next step starts clean`() {
        settings.animate = false
        val yellow = CodeStepsSettings.parseColor(settings.highlightColor)
        session.start(createPresentation())
        val document = session.active!!.document

        session.nextHunk()
        assertEquals(listOf(" oat"), marked(document, yellow)) // only the added word, not its line
        session.nextHunk()
        assertEquals(listOf(" oat", "- apples"), marked(document, yellow))

        session.nextStep() // step 3 only removes lines: nothing inserted to mark, old marks gone
        assertEquals(step3, targetText)
        assertEmpty(marked(document, yellow))
    }

    fun `test the tint color comes from the settings and the tint can be turned off`() {
        settings.animate = false
        settings.highlightColor = "00FFFF"
        session.start(createPresentation())
        session.nextStep()
        assertEquals(listOf(" oat", "- apples"), marked(session.active!!.document, Color(0x00FFFF)))

        settings.highlightChanges = false
        session.previousStep()
        session.nextStep()
        assertEquals(step2, targetText)
        assertEmpty(marked(session.active!!.document, Color(0x00FFFF)))
    }

    fun `test diff colors mark removed and new text instead of the tint`() {
        settings.animate = false
        settings.useDiffColors = true
        session.start(createPresentation())
        val document = session.active!!.document
        val scheme = EditorColorsManager.getInstance().globalScheme
        fun colored(key: TextAttributesKey): List<String> {
            val color = scheme.getAttributes(key).backgroundColor
            return DocumentMarkupModel.forDocument(document, project, true).allHighlighters
                .filter { it.getTextAttributes(scheme)?.backgroundColor == color }
                .sortedBy { it.startOffset }
                .map { document.text.substring(it.startOffset, it.endOffset) }
        }

        session.nextStep()
        assertEquals(listOf(" oat", "- apples"), colored(DiffColors.DIFF_INSERTED))
        assertEmpty(marked(document, CodeStepsSettings.parseColor(settings.highlightColor)))

        // A replaced word fades out as deleted and comes back as inserted, never both in one color.
        val replaced = step2.replace("bread", "rye")
        val transition = StepTransition(
            project, document, replaced, fadeDurationMs = 100, highlightColor = Color.YELLOW, diffColors = true,
        )
        transition.start()
        assertFalse(transition.advanceTo(50))
        assertEquals(listOf("bread"), colored(DiffColors.DIFF_DELETED))
        assertFalse(transition.advanceTo(150))
        assertEquals(listOf(" oat", "rye", "- apples"), colored(DiffColors.DIFF_INSERTED))
        assertEmpty(colored(DiffColors.DIFF_DELETED))
        transition.finish()
        Disposer.dispose(transition)
    }

    fun `test play stops after one step unless it may continue into the next steps`() {
        settings.animate = false
        settings.hunkPauseMs = 5
        session.start(createPresentation())

        session.togglePlay()
        waitWhilePlaying()
        assertEquals(step2, targetText)
        assertEquals(1, session.active!!.index)

        settings.playAcrossSteps = true
        session.previousStep()
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
        settings.animate = false
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

    fun `test a rewritten block fades out as a whole, not word by word`() {
        val old = "fun total(items) {\n  var sum = 0\n  for (item in items) sum += item.price\n  return sum\n}\n"
        val new = "fun total(items) {\n  return items.sumOf { item -> item.price }\n}\n"
        val document = myFixture.configureByText("total.txt", old).fileDocument
        val transition = StepTransition(project, document, new, fadeDurationMs = 100, highlightColor = Color.YELLOW)
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

    fun `test code below a rewritten block slides to its new place instead of jumping`() {
        val old = "fun total(items) {\n  var sum = 0\n  for (item in items) sum += item.price\n  return sum\n}\n"
        val new = "fun total(items) {\n  return items.sumOf { item -> item.price }\n}\n"
        myFixture.configureByText("total.txt", old)
        val editor = myFixture.editor
        val document = editor.document
        val lineHeight = editor.lineHeight
        fun closingBraceY() = editor.offsetToXY(document.text.lastIndexOf('}')).y
        val transition = StepTransition(
            project, document, new, fadeDurationMs = 100, highlightColor = Color.YELLOW, editor = editor,
        )
        // Fading out and in take 100 ms each, closing the space of two lines half of that.
        assertEquals(250L, transition.durationMs)
        transition.start()
        assertEquals(4 * lineHeight, closingBraceY())
        assertFalse(transition.advanceTo(100))
        assertEquals("fun total(items) {\n}\n", document.text)
        assertEquals(4 * lineHeight, closingBraceY()) // the three old lines are gone, their space is still there
        assertFalse(transition.advanceTo(125))
        assertEquals(3 * lineHeight, closingBraceY())
        assertFalse(transition.advanceTo(150))
        assertEquals(new, document.text)
        assertEquals(2 * lineHeight, closingBraceY()) // the new line fades in where the space was
        assertTrue(transition.advanceTo(250))
        assertEquals(2 * lineHeight, closingBraceY())
        Disposer.dispose(transition)
    }

    fun `test fade animation removes text before inserting and survives external edits`() {
        session.start(createPresentation())
        val document = session.active!!.document
        val transition = StepTransition(project, document, step3, fadeDurationMs = 200, highlightColor = Color.YELLOW)
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
