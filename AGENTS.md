# Repository Guidelines

## Project Structure & Module Organization

WFTools-PC is a Kotlin/JVM Compose Desktop WatchMaker editor. Default modules:

- `watch-core/` — watch XML, archives, sessions, and shared domain models.
- `watch-renderer/` — rendering, media playback, previews, and capture/export.
- `agent-core/` — provider/session integration and agent contracts.
- `desktopApp/` — Compose Desktop UI, wiring, resources, and packaging.

The upstream Android implementation is not part of this repository; the WFTools-PC tree only builds the four JVM modules listed above.

Tests are under each module's `src/test/kotlin`; documentation is in `docs/`, with release scripts and assets in `scripts/` and `packaging/`.

## Build, Test, and Development Commands

Run from `D:\WFTools-PC\wftools` in native Windows PowerShell:

```powershell
.\gradlew.bat build                           # compile all configured modules
.\gradlew.bat test                            # run all JVM unit and UI tests
.\gradlew.bat :watch-core:test                # run one module's tests
.\gradlew.bat :desktopApp:createDistributable # create the desktop app image
.\scripts\build.ps1                           # project verification plus distributable
```

Use `wftools.buildPlatform` for isolated `.build/<platform>/` output. Do not run concurrent Gradle builds against one output or cache. Run `git diff --check`; keep dependency versions in `gradle/libs.versions.toml`.

## Coding Style & Naming Conventions

Use four-space indentation, Kotlin formatting, and `com.ailife.wftools...` packages. Classes/composables use `PascalCase`; functions, properties, and test methods use `camelCase`; constants use `UPPER_SNAKE_CASE`; tests end in `Test.kt`. Keep JVM business logic free of Android `Context`/`Uri`; use `Path`/`File`, add KDoc for public APIs, preserve unknown archive/XML data, and save atomically.

## Testing Guidelines

Use JUnit 4 and Compose Desktop UI tests already configured in the module build files. Name tests after the behavior or class under test (for example, `RenderSessionTimeMachineTest`). Every new desktop business rule should include a focused regression test; UI changes should update the relevant UI test or provide screenshots in the PR description.

## Packaging & Runtime Dependencies

Create the desktop image with `:desktopApp:createDistributable`, then use `scripts/package-windows.ps1` for Windows ZIP/MSI or `scripts/package-linux.sh` for Linux x64. VLC/FFmpeg runtime dependencies are documented in `packaging/README_WINDOWS.md` and `packaging/README_LINUX.md`.

## Commit & Pull Request Guidelines

Follow the short imperative style: concise Chinese subjects or Conventional Commit scopes such as `feat(desktop): ...` and `fix: ...`. Keep commits focused. PRs should explain behavior, list verification commands, link issues, and include UI screenshots. Stage only reviewed source/configuration; exclude builds, logs, and local artifacts.
