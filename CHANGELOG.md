<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Bean Copy Companion Changelog

## [Unreleased]

## [0.1.1]

### Added

- Review/star CTA: after 5 successful uses of Generate Bean Copy Method
  (a real file written, not a cancelled dialog or a no-op), a one-time
  notification asks whether to rate the plugin on Marketplace, with a
  permanent "Don't ask again" option. Standard mechanism used
  catalog-wide since 2026-08-24, adapted here for an action-based
  plugin: threshold is 5 (not 10), since an explicit invocation is a
  stronger signal than a passive finding.

## [0.1.0]

### Added

- **Generate Bean Copy Method** action (Project view, 2-file selection):
  generates a field-by-field copy method between two Java or Kotlin
  classes, fields matched by name and assignment-compatible type.
- Real Kotlin support (both source and target), including `data class`
  targets and Kotlin's `is`-prefixed Boolean getter/setter naming.
- Java 17+ `record` target support via canonical-constructor detection.
- Lombok `@Builder` target support, detected from the plain annotation
  text (no Lombok/Kotlin compiler dependency), with a graceful fallback
  to setters/constructor if the generated builder class isn't visible
  in the PSI yet.
- Primitive/boxed type equivalence (`int` ↔ `Integer`, etc.).
- In-memory PSI validation before any file is written to disk -- a
  field this plugin can't safely map is left as an honest
  `// TODO(bean-copy): ...` comment, never a guess and never a crash.
- Generated file language always matches the target class's language.

[Unreleased]: https://github.com/GapHunterLabs/bean-copy-companion/compare/0.1.1...HEAD
[0.1.1]: https://github.com/GapHunterLabs/bean-copy-companion/compare/0.1.0...0.1.1
[0.1.0]: https://github.com/GapHunterLabs/bean-copy-companion/commits/0.1.0
