package com.github.raphaelluethy.codesteps.diff

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class StepDiffTest : BasePlatformTestCase() {
    private fun apply(old: String, new: String, changes: List<TextChange>): String = buildString {
        var position = 0
        for (change in changes) {
            assertTrue("changes overlap or are unsorted: $changes", change.oldStart >= position)
            append(old, position, change.oldStart)
            append(new, change.newStart, change.newEnd)
            position = change.oldEnd
        }
        append(old, position, old.length)
    }

    private fun check(old: String, new: String): List<TextChange> {
        val hunks = StepDiff.computeHunks(old, new)
        assertEquals(new, apply(old, new, hunks))
        hunks.zipWithNext().forEach { (a, b) -> assertTrue(a.newEnd <= b.newStart) }

        // Playing hunks one by one passes through exactly these intermediate texts and ends on the new text.
        for (count in 0..hunks.size) {
            assertEquals(apply(old, new, hunks.take(count)), StepDiff.applyFirst(old, new, hunks, count))
        }
        return hunks
    }

    fun `test reconstructs the new text`() {
        check("", "fun main() {}\n")
        check("fun main() {}\n", "")
        check("a\nb\nc\n", "a\nc\nd\ne\n")
        check("line without newline", "line without newline\nplus more")
        check("x = 1\ny = 2\nz = 3\n", "z = 3\nx = 10\ny = 2\n")
        check("\t indented\n\n\nblank lines\n", "indented\n\nblank  lines\n\n")
    }

    fun `test reconstructs random edits including missing trailing newlines`() {
        val random = java.util.Random(42)
        val words = listOf("a", "bb", "x = 1", " ", "\t", "\n", "\n", "fun", "{", "}", "ü")
        fun text() = buildString { repeat(random.nextInt(30)) { append(words[random.nextInt(words.size)]) } }
        repeat(500) {
            val old = text()
            // Edit `old` so most lines survive, like real steps do.
            val new = old.lines().filter { random.nextInt(4) != 0 }.joinToString("\n") { line ->
                if (random.nextInt(3) == 0) line + words[random.nextInt(words.size)] else line
            } + if (random.nextBoolean()) text() else ""
            check(old, new)
        }
    }

    fun `test a rewritten block is one hunk and separate edits are separate hunks, top to bottom`() {
        val old = "fun total(items) {\n  var sum = 0\n  for (item in items) sum += item.price\n  return sum\n}\n\nfun main() {}\n"
        val new = "fun total(items) {\n  return items.sumOf { item -> item.price }\n}\n\nfun main() = println(total(listOf()))\n"
        val hunks = StepDiff.computeHunks(old, new)
        assertEquals(2, hunks.size)
        assertEquals("  return items.sumOf { item -> item.price }\n", new.substring(hunks[0].newStart, hunks[0].newEnd))
        assertEquals("  var sum = 0\n  for (item in items) sum += item.price\n  return sum\n", old.substring(hunks[0].oldStart, hunks[0].oldEnd))
        assertTrue(hunks[0].newEnd <= hunks[1].newStart)
        assertEquals(
            "fun total(items) {\n  return items.sumOf { item -> item.price }\n}\n\nfun main() {}\n",
            StepDiff.applyFirst(old, new, hunks, 1),
        )
    }

    fun `test identical texts have no changes`() {
        assertEmpty(check("same\n", "same\n"))
    }
}
