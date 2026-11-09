# Code Steps

An IntelliJ Platform plugin for presenting code step by step, like slides, or for playing a recorded refactoring
back chunk by chunk, as if someone were editing live in front of the class. You save several versions of a file
as steps. Each change between two steps is split into diff hunks (blocks of contiguous changed lines), and the
hunks play one at a time, top to bottom, in the normal editor.

- **Any file type.** Stepping, diffing, highlighting, and animation work on the document text only, so Kotlin,
  Markdown, YAML, and plain text all behave the same. No language plugin or PSI is needed.
- **A real editor.** Each step replaces the content of the file's regular editor, so syntax highlighting,
  navigation, completion, and inspections keep working while you present.
- **Plain files in Git.** Presentations live in `.codesteps/` inside the project. Commit them, review them in
  pull requests, and share them by cloning the repository.

## Recording a refactoring

1. Open the file and run **Tools | Code Steps | Start Recording** (also in the editor context menu). Its current
   content becomes the first step, and a red banner shows that recording is on.
2. Do the refactoring as usual. Whenever you reach a point the audience should see, run **Capture Step** or commit
   in the IDE: each commit that includes the file adds a step named after the commit message.
3. Run **Stop Recording**. It captures the final content and offers to start the presentation.

A step can hold many separate edits; playback splits it into hunks, so capturing coarse steps is fine.
You can also build a presentation by hand with **Capture Step**, or write the files yourself (see below).

## Presenting

Run **Tools | Code Steps | Start Presentation**. The file shows step 1, and a banner above the editor shows the
position (step, and hunk within the change to the next step) with the controls.

| Action | Windows / Linux | What it does |
| --- | --- | --- |
| Play / Pause | `Ctrl+Alt+Shift+Space` | Plays the hunks one at a time with a pause between them. Pause lets the current hunk finish. |
| Next hunk | `Ctrl+Alt+Shift+]` | Shows the next hunk: the old block goes, the new block comes. |
| Previous hunk | `Ctrl+Alt+Shift+[` | Takes back the last hunk. |
| Next step | `Ctrl+Alt+Shift+.` | Plays the rest of the step hunk by hunk; press again to complete it at once. |
| Previous step | `Ctrl+Alt+Shift+,` | Goes back to the start of the step, or to the previous step. |
| First / last step | `Ctrl+Alt+Shift+Home` / `End` | |
| Faster / slower | `Ctrl+Alt+Shift+=` / `-` | Playback speed from 0.5× to 3×; also the Speed link in the banner. |

On macOS, Next and Previous step are `⌘⌥⇧.` and `⌘⌥⇧,`. Change or add shortcuts under
**Settings | Keymap | Plugins | Code Steps**. The controls also appear in the main toolbar while a presentation
runs. **Stop Presentation** puts back the content the file had before you started.

The editor keeps the active hunk centered. While presenting you can type freely; **Save Editor to Current Step**
overwrites the current step, and **Capture Step** inserts the editor content as a new step after it.

### Transition styles

- **Hunks: fade out old block, fade in new block** (default). Each hunk changes as one unit, so rewritten code
  never looks fragmented.
- **Hunks: fade out old block, type new block.** The new block is typed character by character.
- **Highlight: no motion.** The new text appears at once, and the changed lines get a solid marker-pen highlight.
  Removed lines are marked with a red bar.
- **Words: fade / type changed words.** The earlier, fine-grained styles, which animate only the changed words.
- **Instant.**

Changed text is highlighted in yellow (configurable) with bold black text, and removed text fades out in red. The
colors are saturated so they stay visible on washed-out projectors, on light and dark themes.

### Projector mode

**Projector Mode** (toolbar or banner link) enlarges the presented editor (28 pt by default), hides the tool windows,
hides the folding outline and intention bulbs, and enlarges the banner so it reads from the back of the room.
Turning it off or stopping the presentation restores everything.

## Settings

**Settings | Tools | Code Steps** holds personal preferences, stored in the IDE configuration and not in the project:
transition style, fade duration (900 ms), typing speed (35 characters per second), the longest typing animation per
hunk, highlight on or off and its color, the pause between hunks (800 ms, doubled after a finished step), the speed,
whether Play continues into the next step, whether Stop restores the file, and the projector font size.

## File format

```
.codesteps/
  tokenizer/
    presentation.yaml
    01-skeleton.kt
    02-read-numbers.kt
    03-read-operators.kt
```

`presentation.yaml` lists the steps in order. `target` is relative to the project root:

```yaml
# Code Steps presentation. Each step file is a complete snapshot of the target file;
# steps are shown in the order listed below.
codesteps: 1
title: "Tokenizer.kt"
target: "src/main/kotlin/Tokenizer.kt"
steps:
  - file: "01-skeleton.kt"
    title: "Skeleton"
  - file: "02-read-numbers.kt"
    title: "Read numbers"
```

Each step file is a complete copy of the target file at that step and keeps the target's extension, so it opens with
syntax highlighting and a pull request shows each step as an ordinary file. To see what one step changes, diff two
step files. You can write or edit everything by hand: reorder steps by moving their entries, and rename step files as
long as the manifest matches. The manifest is a small YAML subset: one `key: value` per line, values plain or quoted.

The plugin excludes `.codesteps/` from indexing, so step files do not show up as duplicate classes in navigation or
search. They are still regular files in version control.

## Building

Requires JDK 25 (IntelliJ IDEA 2026.3 runs on Java 25).

```bash
./gradlew test          # unit and platform tests
./gradlew buildPlugin   # build/distributions/code-steps-<version>.zip
./gradlew runIde        # start a sandbox IDE with the plugin installed
./gradlew verifyPlugin  # check compatibility with IntelliJ Platform Plugin Verifier
```

Install the ZIP with **Settings | Plugins | ⚙ | Install Plugin from Disk**.
