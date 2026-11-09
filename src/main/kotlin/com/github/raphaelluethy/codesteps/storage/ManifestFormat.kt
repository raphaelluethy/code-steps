package com.github.raphaelluethy.codesteps.storage

/** One step of a presentation: a snapshot file inside the presentation folder and its title. */
data class Step(val file: String, val title: String)

/** Contents of a `presentation.yaml` file. [target] is relative to the repository root and uses `/`. */
data class Manifest(val title: String, val target: String, val steps: List<Step>)

class ManifestException(message: String) : Exception(message)

/**
 * Reads and writes `presentation.yaml`.
 *
 * The format is a small, line-based subset of YAML, so the file stays readable, diffs line by line,
 * and still opens in any YAML tooling:
 *
 * ```yaml
 * codesteps: 1
 * title: "Building a tokenizer"
 * target: "src/Tokenizer.kt"
 * steps:
 *   - file: "01-skeleton.kt"
 *     title: "Skeleton"
 * ```
 *
 * Values may be double-quoted (with JSON-style escapes), single-quoted, or plain. Unknown keys are ignored.
 */
object ManifestFormat {
    const val FILE_NAME = "presentation.yaml"
    const val FORMAT_VERSION = 1

    fun write(manifest: Manifest): String = buildString {
        append("# Code Steps presentation. Each step file is a complete snapshot of the target file;\n")
        append("# steps are shown in the order listed below.\n")
        append("codesteps: ").append(FORMAT_VERSION).append('\n')
        append("title: ").append(quote(manifest.title)).append('\n')
        append("target: ").append(quote(manifest.target)).append('\n')
        append("steps:")
        if (manifest.steps.isEmpty()) append(" []")
        append('\n')
        for (step in manifest.steps) {
            append("  - file: ").append(quote(step.file)).append('\n')
            append("    title: ").append(quote(step.title)).append('\n')
        }
    }

    fun parse(text: String): Manifest {
        var title = ""
        var target: String? = null
        var inSteps = false
        val steps = mutableListOf<Step>()
        var stepFile: String? = null
        var stepTitle: String? = null
        var stepLine = 0

        fun flushStep() {
            if (stepFile == null && stepTitle == null) return
            val file = stepFile ?: throw ManifestException("line $stepLine: step has no 'file'")
            steps += Step(validateStepFile(file, stepLine), stepTitle ?: file)
            stepFile = null
            stepTitle = null
        }

        for ((index, raw) in text.lineSequence().withIndex()) {
            val lineNumber = index + 1
            val line = raw.trimEnd()
            var entry = line.trimStart()
            if (entry.isEmpty() || entry.startsWith("#")) continue
            val indented = entry.length != line.length
            val isItem = entry == "-" || entry.startsWith("- ")

            if (isItem) {
                if (!inSteps) throw ManifestException("line $lineNumber: list item outside of 'steps'")
                flushStep()
                stepLine = lineNumber
                entry = entry.removePrefix("-").trimStart()
                if (entry.isEmpty()) continue
            }

            val colon = entry.indexOf(':')
            if (colon <= 0) throw ManifestException("line $lineNumber: expected 'key: value'")
            val key = entry.substring(0, colon).trim()
            val value = unquote(entry.substring(colon + 1).trim(), lineNumber)

            if (!indented && !isItem) {
                flushStep()
                inSteps = false
                when (key) {
                    "title" -> title = value
                    "target" -> target = value
                    "steps" -> {
                        if (value.isNotEmpty() && value != "[]") {
                            throw ManifestException("line $lineNumber: 'steps' must be followed by a list")
                        }
                        inSteps = true
                    }
                }
            } else if (inSteps) {
                when (key) {
                    "file" -> stepFile = value
                    "title" -> stepTitle = value
                }
            }
        }
        flushStep()

        val targetPath = target?.takeIf { it.isNotBlank() }?.replace('\\', '/')?.removePrefix("/")
            ?: throw ManifestException("missing 'target'")
        // Presenting overwrites the target's content, so it must stay inside the project like the step files.
        if (".." in targetPath.split('/')) throw ManifestException("'target' must be inside the project, got '$targetPath'")
        return Manifest(title, targetPath, steps)
    }

    /** Step files must stay inside the presentation folder. */
    private fun validateStepFile(file: String, line: Int): String {
        if (file.isBlank() || file == "." || file == ".." || file.contains('/') || file.contains('\\')) {
            throw ManifestException("line $line: step file must be a plain file name, got '$file'")
        }
        return file
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (c in value) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' || c == '\u007f' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    private fun unquote(value: String, line: Int): String = when {
        value.startsWith("\"") -> unquoteDouble(value, line)
        value.startsWith("'") -> unquoteSingle(value, line)
        else -> {
            val comment = value.indexOf(" #")
            (if (comment >= 0) value.substring(0, comment) else value).trim()
        }
    }

    private fun unquoteDouble(value: String, line: Int): String {
        val out = StringBuilder()
        var i = 1
        while (i < value.length) {
            val c = value[i]
            when (c) {
                '"' -> {
                    requireOnlyComment(value.substring(i + 1), line)
                    return out.toString()
                }
                '\\' -> {
                    if (i + 1 >= value.length) break
                    when (val e = value[i + 1]) {
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        '"', '\\', '/' -> out.append(e)
                        'u' -> {
                            val hex = value.substring(i + 2, minOf(i + 6, value.length))
                            val code = hex.takeIf { it.length == 4 }?.toIntOrNull(16)
                                ?: throw ManifestException("line $line: invalid \\u escape")
                            out.append(code.toChar())
                            i += 4
                        }
                        else -> throw ManifestException("line $line: unsupported escape '\\$e'")
                    }
                    i += 2
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        throw ManifestException("line $line: unterminated string")
    }

    private fun unquoteSingle(value: String, line: Int): String {
        val out = StringBuilder()
        var i = 1
        while (i < value.length) {
            val c = value[i]
            if (c == '\'') {
                if (i + 1 < value.length && value[i + 1] == '\'') {
                    out.append('\'')
                    i += 2
                    continue
                }
                requireOnlyComment(value.substring(i + 1), line)
                return out.toString()
            }
            out.append(c)
            i++
        }
        throw ManifestException("line $line: unterminated string")
    }

    private fun requireOnlyComment(rest: String, line: Int) {
        val trimmed = rest.trim()
        if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
            throw ManifestException("line $line: unexpected text after closing quote")
        }
    }
}
