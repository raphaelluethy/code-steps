# README images

These images come from the running Code Steps plugin in the IntelliJ Platform test fixture. The real editor,
presentation banner, animation, and settings panel paint themselves into PNGs. They are not mockups of an IDE.
The headless captures omit the surrounding IDE window and tool windows, so the projector image demonstrates
the enlarged editor and banner, not the hiding of tool windows.

## Regenerate without a display

Install JDK 25 and put `java` on `PATH` or set `JAVA_HOME`. You also need ffmpeg for the GIF.
From the repository root, run:

```bash
./gradlew test --tests '*DocImages*' -PdocImages
```

The opt-in `DocImages` test writes these files into `build/doc-images/`:

- `frames/0000.png`, and subsequent numbered frames, for hunk-by-hunk playback at 20 fps.
- `banner.png`, the normal presentation controls.
- `diff-colors.png`, inserted code using the editor color scheme's diff colors.
- `projector.png`, the plugin's projector mode with its default 28 pt font.
- `settings.png`, the settings panel with the default preferences.

All PNGs render at 2x resolution. The editor uses the Darcula syntax color scheme. The sample lesson develops
a Java guessing game through three snapshots. The generator slows the animation clock by four while capturing
to avoid skipping animation states; the GIF plays those frames at the normal speed. It holds each completed
hunk for 1.2 seconds so readers can inspect the change. A normal test run does not generate images.

Create the looping GIF with a shared palette, keeping the 2x frames at their original resolution:

```bash
ffmpeg -y -framerate 20 -i build/doc-images/frames/%04d.png \
  -filter_complex '[0:v]split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=sierra2_4a' \
  -loop 0 docs/images/demo.gif
cp build/doc-images/{banner,diff-colors,projector,settings}.png docs/images/
```

Open the GIF and each PNG before committing. Check that the code is legible, every banner control is visible,
the hunks fade and slide, the diff-colors still shows the inserted tint, and projector mode has larger text.
Commit only the GIF and stills in this directory, not the intermediate frames or build outputs.

## Capture in a sandbox IDE

With a graphical display, `./gradlew runIde` starts an IDE with the plugin installed. Record a short lesson,
present it, and capture the banner, diff colors, projector mode, and **Settings | Tools | Code Steps**.
Use a screen recording for the hunk animation and convert it with ffmpeg. Keep screenshots free of private
project paths, account details, and unrelated windows. The checked-in images use the headless procedure above.
