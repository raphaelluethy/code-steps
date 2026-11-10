package com.github.raphaelluethy.codesteps.docs

import com.github.raphaelluethy.codesteps.presentation.PresentationSession
import com.github.raphaelluethy.codesteps.settings.CodeStepsConfigurable
import com.github.raphaelluethy.codesteps.settings.CodeStepsSettings
import com.github.raphaelluethy.codesteps.storage.PresentationStorage
import com.github.raphaelluethy.codesteps.ui.PresentationNotificationProvider
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Color
import java.awt.Container
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JComponent

/**
 * Renders the images of the README from the running plugin: the frames of the demo animation, a still of
 * the diff colors, and the settings page. Nothing is mocked up; the editor, the banner and the settings
 * panel paint themselves. Does nothing in a normal test run, see `docs/images/README.md`.
 */
class DocImages : BasePlatformTestCase() {
    private val settings get() = CodeStepsSettings.getInstance().state
    private val session get() = PresentationSession.getInstance(project)

    private val steps = listOf(
        "Guess once" to """
            import java.util.Random;

            int randomNumber() {
                return new Random().nextInt(1, 101);
            }

            int askUserForGuess() {
                return Integer.parseInt(IO.readln("Your guess? "));
            }

            void main() {
                int secret = randomNumber();
                int guess = askUserForGuess();
                IO.println(guess == secret ? "Correct!" : "Wrong.");
            }
        """,
        "Name the magic numbers" to """
            import java.util.Random;

            int randomNumber() {
                int LOWER_BOUND = 1;
                int UPPER_BOUND = 101;
                return new Random().nextInt(LOWER_BOUND, UPPER_BOUND);
            }

            int askUserForGuess() {
                return Integer.parseInt(IO.readln("Your guess? "));
            }

            void main() {
                int secret = randomNumber();
                int guess = askUserForGuess();
                IO.println(guess == secret ? "Correct!" : "Wrong.");
            }
        """,
        "Ask until the guess is right" to """
            import java.util.Random;

            int randomNumber() {
                int LOWER_BOUND = 1;
                int UPPER_BOUND = 101;
                return new Random().nextInt(LOWER_BOUND, UPPER_BOUND);
            }

            int askUserForGuess() {
                return Integer.parseInt(IO.readln("Your guess? "));
            }

            void main() {
                int secret = randomNumber();
                int guess = askUserForGuess();
                while (guess != secret) {
                    IO.println(guess < secret ? "Too low." : "Too high.");
                    guess = askUserForGuess();
                }
                IO.println("Correct!");
            }
        """,
    ).map { (title, text) -> title to text.trimIndent() + "\n" }

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

    fun `test render the images of the README`() {
        val out = System.getProperty("codesteps.docs.dir")?.let(::File) ?: return
        val frames = File(out, "frames").apply { deleteRecursively(); mkdirs() }
        val colors = EditorColorsManager.getInstance()
        val previousScheme = colors.globalScheme
        colors.setGlobalScheme(colors.getScheme("Darcula"))
        try {
            ImageIO.write(renderSettings(), "png", File(out, "settings.png"))
            startPresentation()
            val editorImage = renderEditor()
            val fileEditor = FileEditorManager.getInstance(project).getSelectedEditor(session.active!!.target)!!
            val banner = PresentationNotificationProvider().collectNotificationData(project, session.active!!.target)!!.apply(fileEditor)!!
            ImageIO.write(editorImage.getSubimage(0, 0, editorImage.width, banner.preferredSize.height * SCALE), "png", File(out, "banner.png"))
            recordDemo(frames)
            session.stop()

            settings.useDiffColors = true
            settings.animate = false
            startPresentation()
            session.nextStep()
            session.nextStep()
            ImageIO.write(renderEditor(), "png", File(out, "diff-colors.png"))
            session.stop()

            settings.useDiffColors = false
            startPresentation()
            session.setProjectorMode(true)
            ImageIO.write(renderEditor(), "png", File(out, "projector.png"))
            session.setProjectorMode(false)
        } finally {
            colors.setGlobalScheme(previousScheme)
        }
    }

    private fun startPresentation() {
        if (myFixture.tempDirFixture.getFile("src/Guess.java") == null) {
            myFixture.addFileToProject("src/Guess.java", steps.last().second)
        }
        val manifest = "codesteps: 1\ntitle: \"Guessing game\"\ntarget: \"src/Guess.java\"\nsteps:\n" +
            steps.indices.joinToString("") { "  - file: \"%02d.java\"\n    title: \"${steps[it].first}\"\n".format(it + 1) }
        if (myFixture.tempDirFixture.getFile(".codesteps/guess/presentation.yaml") == null) {
            myFixture.addFileToProject(".codesteps/guess/presentation.yaml", manifest)
            steps.forEachIndexed { i, step -> myFixture.addFileToProject(".codesteps/guess/%02d.java".format(i + 1), step.second) }
        }
        session.start(PresentationStorage.load(myFixture.findFileInTempDir(".codesteps/guess")))
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    /**
     * Plays every hunk and writes a frame every [TICK_MS] of animation time. The animation runs [SLOWDOWN]
     * times slower than it is shown, so rendering a frame never makes it skip.
     */
    private fun recordDemo(frames: File) {
        settings.fadeDurationMs = 900 * SLOWDOWN
        var frame = 0
        fun write(count: Int = 1) {
            val image = renderEditor()
            repeat(count) { ImageIO.write(image, "png", File(frames, "%04d.png".format(frame++))) }
        }
        write(HOLD_MS / TICK_MS)
        while (!session.active!!.isAtEnd) {
            session.nextHunk()
            val start = System.nanoTime()
            val animationMs = 900 * 5 / 2 + 200
            var written = 0
            while (true) {
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                val elapsed = (System.nanoTime() - start) / 1_000_000 / SLOWDOWN
                val due = (elapsed / TICK_MS).toInt().coerceAtMost(animationMs / TICK_MS)
                if (due > written) {
                    write(due - written)
                    written = due
                }
                if (elapsed >= animationMs) break
                Thread.sleep(5)
            }
            write(HOLD_MS / TICK_MS)
        }
    }

    /** The banner above the editor and the editor content, as the presenter sees them. */
    private fun renderEditor(): BufferedImage {
        val active = session.active!!
        val fileEditor = FileEditorManager.getInstance(project).getSelectedEditor(active.target)!!
        val editor = FileEditorManager.getInstance(project).selectedTextEditor as EditorEx
        if (!settings.projectorMode) editor.setFontSize(FONT_SIZE)
        val width = if (settings.projectorMode) 1400 else WIDTH
        val banner = PresentationNotificationProvider().collectNotificationData(project, active.target)?.apply(fileEditor)
        val bannerHeight = banner?.preferredSize?.height ?: 0
        val content = editor.contentComponent
        val lines = steps.maxOf { it.second.count { c -> c == '\n' } } + 1
        val height = bannerHeight + PADDING + lines * editor.lineHeight + PADDING
        return image(width, height) { g ->
            g.color = editor.backgroundColor
            g.fillRect(0, 0, width, height)
            if (banner != null) {
                banner.setSize(width, bannerHeight)
                layout(banner)
                banner.paint(g)
            }
            g.translate(PADDING, bannerHeight + PADDING)
            content.setSize(width - PADDING, height - bannerHeight - PADDING)
            g.setClip(0, 0, content.width, content.height)
            content.paint(g)
        }
    }

    private fun renderSettings(): BufferedImage {
        val configurable = CodeStepsConfigurable()
        val panel = configurable.createComponent()
        configurable.reset()
        val size = panel.preferredSize
        val width = size.width + 2 * PADDING
        val height = size.height + 2 * PADDING
        panel.setSize(size)
        layout(panel)
        return image(width, height) { g ->
            g.color = panel.background ?: Color.WHITE
            g.fillRect(0, 0, width, height)
            g.translate(PADDING, PADDING)
            panel.paint(g)
        }.also { configurable.disposeUIResources() }
    }

    private fun image(width: Int, height: Int, paint: (java.awt.Graphics2D) -> Unit): BufferedImage {
        val image = BufferedImage(width * SCALE, height * SCALE, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.scale(SCALE.toDouble(), SCALE.toDouble())
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        paint(g)
        g.dispose()
        return image
    }

    /** Components that are not shown on a screen are not laid out on their own. */
    private fun layout(component: JComponent) {
        component.doLayout()
        for (child in component.components) {
            if (child is JComponent) layout(child) else if (child is Container) child.doLayout()
        }
    }

    private companion object {
        const val WIDTH = 760
        const val PADDING = 14
        const val SCALE = 2
        const val FONT_SIZE = 15f
        const val TICK_MS = 50
        const val HOLD_MS = 1200
        const val SLOWDOWN = 4
    }
}
