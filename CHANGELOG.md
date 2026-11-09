# Changelog

All notable changes to Code Steps are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and versions follow [Semantic Versioning](https://semver.org/).

## [0.2.3] - 2026-11-10

### Changed

- A changed line is edited where it differs: only the words that changed fade out and in, and only they keep the
  tint. A block that was rewritten is still replaced as a whole.
- With diff colors, old code always fades out in the deleted color and new code keeps the inserted color.
  Before, both sides of a changed line shared the modified color.
- The plugin does no recurring work while nothing is presented or recorded.

### Removed

- The Start Presentation button in the main toolbar. Start a presentation from **Tools | Code Steps**.

## [0.2.0] - 2026-11-10

### Added

- **Use the diff colors of the editor color scheme** in the settings, to mark changes in the colors of the diff
  viewer instead of the tint color.

## [0.1.0] - 2026-11-09

### Added

- Present the steps of a file hunk by hunk in the normal editor, with animated changes, Play, and a projector mode.
- Record a presentation while editing: capture steps by hand, or let each IDE commit add one.
- Presentations as plain files in `.codesteps/`: a `presentation.yaml` manifest and one snapshot file per step.

[0.2.3]: https://github.com/raphaelluethy/code-steps/releases/tag/v0.2.3
[0.2.0]: https://github.com/raphaelluethy/code-steps/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/raphaelluethy/code-steps/commits/v0.1.0
