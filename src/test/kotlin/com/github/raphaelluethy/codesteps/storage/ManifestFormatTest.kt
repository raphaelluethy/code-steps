package com.github.raphaelluethy.codesteps.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ManifestFormatTest {
    @Test
    fun `round trips titles with characters that need escaping`() {
        val manifest = Manifest(
            title = "Quotes \" and \\ back: slash # not a comment\nsecond line\tand ünïcödé",
            target = "src/main/Tokenizer.kt",
            steps = listOf(Step("01-a.kt", "key: value"), Step("02-b.kt", "it's \"done\"")),
        )
        assertEquals(manifest, ManifestFormat.parse(ManifestFormat.write(manifest)))
    }

    @Test
    fun `writes one line per field so diffs stay small`() {
        val text = ManifestFormat.write(Manifest("Demo", "a/b.txt", listOf(Step("01-x.txt", "X"), Step("02-y.txt", "Y"))))
        assertEquals(
            """
            codesteps: 1
            title: "Demo"
            target: "a/b.txt"
            steps:
              - file: "01-x.txt"
                title: "X"
              - file: "02-y.txt"
                title: "Y"
            """.trimIndent() + "\n",
            text.lines().filterNot { it.startsWith("#") }.joinToString("\n"),
        )
    }

    @Test
    fun `parses hand-written YAML with plain and single-quoted values`() {
        val manifest = ManifestFormat.parse(
            """
            # comment
            codesteps: 1
            title: My talk   # trailing comment
            target: 'docs/it''s.md'
            author: ignored
            steps:
            - file: 01-start.md
              title: 'Start: here'
            -
              title: no file first
              file: 02-end.md
            """.trimIndent(),
        )
        assertEquals("My talk", manifest.title)
        assertEquals("docs/it's.md", manifest.target)
        assertEquals(listOf(Step("01-start.md", "Start: here"), Step("02-end.md", "no file first")), manifest.steps)
    }

    @Test
    fun `step title defaults to the file name and empty step lists are allowed`() {
        assertEquals(listOf(Step("01.txt", "01.txt")), ManifestFormat.parse("target: a.txt\nsteps:\n  - file: 01.txt\n").steps)
        assertEquals(emptyList<Step>(), ManifestFormat.parse(ManifestFormat.write(Manifest("", "a.txt", emptyList()))).steps)
    }

    @Test
    fun `rejects invalid manifests`() {
        assertThrows(PresentationException::class.java) { ManifestFormat.parse("title: x\nsteps:\n") }
        assertThrows(PresentationException::class.java) { ManifestFormat.parse("target: a\nsteps:\n  - file: ../../etc/passwd\n") }
        assertThrows(PresentationException::class.java) { ManifestFormat.parse("target: src/../../outside.txt\nsteps:\n") }
        assertThrows(PresentationException::class.java) { ManifestFormat.parse("target: a\nsteps:\n  - title: missing file\n") }
        assertThrows(PresentationException::class.java) { ManifestFormat.parse("target: a\n- file: x\n") }
        assertThrows(PresentationException::class.java) { ManifestFormat.parse("target: \"unterminated\n") }
        assertThrows(PresentationException::class.java) { ManifestFormat.parse("codesteps: 2\ntarget: a\nsteps:\n") }
        assertThrows(PresentationException::class.java) { ManifestFormat.parse("codesteps: one\ntarget: a\nsteps:\n") }
    }
}
