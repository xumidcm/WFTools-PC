# CLAUDE.md

This file guides AI coding agents working in this repository. Repository conventions live in [`AGENTS.md`](AGENTS.md); this file adds the architectural context those conventions assume.

## Project overview

**WFTools-PC** is a desktop editor for WatchMaker `.watch` watch faces, written in Kotlin with Compose Desktop. It targets Windows x64 and Linux x64.

- Kotlin `2.3.21`, Compose Desktop `1.10.3`, JDK toolchain `21`, Gradle wrapper `8.13` — versions live in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).
- Only the four JVM modules below are built. The upstream Android app is **not** in this repository; do not look for it, and do not reintroduce Android APIs.

## Build commands

```powershell
.\gradlew.bat build                           # compile all modules
.\gradlew.bat test                            # all JVM unit and UI tests
.\gradlew.bat :watch-core:test                # one module
.\gradlew.bat :desktopApp:run                 # run from sources
.\gradlew.bat :desktopApp:createDistributable # desktop app image
```

Use `-Pwftools.buildPlatform=<name>` to isolate output under `.build/<name>/`. Never run two Gradle builds against the same output or cache.

## Architecture

| Module | Responsibility |
|---|---|
| `watch-core` | `.watch` XML and archive model, document sessions, lossless read/write, undo/redo, crash-safe draft recovery |
| `watch-renderer` | WatchMaker runtime: expression evaluation, Lua, layer rendering, bitmaps, media via libVLC, thumbnails, capture/export |
| `agent-core` | AI provider profiles and HTTP clients, streaming parsers, context budgeting, tool contracts |
| `desktopApp` | Compose UI, platform integration (Windows common dialogs, file association, instance broker), i18n, packaging |

The WatchMaker domain model still lives under `com.ailife.wftools2.domain.editor...` — a legacy package name kept so the ported renderer code stays diffable against upstream. New desktop code uses `com.ailife.wftools.pc...`.

## Invariants that must not regress

- **Lossless round-trip.** Unknown XML, scripts, unreferenced assets and metadata are preserved verbatim. Never normalise or drop what you do not understand.
- **Atomic save.** Write a temporary archive, re-read and verify it, then swap it in. A failed save must leave the original file usable. Unmodified documents are not re-archived at all.
- **Per-document isolation.** Each window owns its own undo/redo stack (50 entries), preview session, media players and drafts. No shared mutable state between documents.
- **Expressions are not values.** Dragging a slider on an `fx` field must not replace the author's expression with the sampled number; canvas moves compose a delta onto the existing expression.
- **Preview never mutates the document.** Device viewport, zoom and simulation (time, sensors, dim) affect rendering only, never the saved `.watch` or exported data.

## Formats and services

- Only `.watch` (WatchMaker) is supported. Protected `watch.pxml` files cannot be opened.
- Layer types the runtime does not implement (for example `gallery_2d`) are preserved byte-for-byte and shown as unsupported.
- Watch face market: `WatchMarket.API_ROOT` = `https://jbwatchface.ai-life.xyz/api`. Network calls must respect the byte limits declared in `WatchMarketApi` and must not log credentials.
- AI providers are user-configured (OpenAI-compatible Chat Completions, OpenAI Responses, Anthropic Messages). API keys live in the platform secret store and must never be written to logs, error messages or documents.

## Localisation

User-facing source text is **Simplified Chinese** and serves as the fallback; English comes from `desktopApp/src/main/resources/i18n/messages_en.properties` through `DesktopI18n`. When adding UI text, add the Chinese string at the call site and the English entry to that properties file.

## Testing

JUnit 4 plus Compose Desktop UI tests. Name tests after the behaviour under test (for example `RenderSessionTimeMachineTest`). Every new business rule needs a focused regression test; UI changes should update the relevant UI test or ship screenshots in the PR.

Renderer tests need libVLC on the machine (bundled on Windows packages; on Linux install `libvlc-dev`). `DesktopGraphicsTest` needs a system CJK font — CI installs `fonts-noto-cjk` for that reason. Compose UI tests need a display server; CI runs them under `xvfb-run`.

## Documentation

`docs/windows/USER_GUIDE.md` (user-facing), `docs/windows/IMPLEMENTATION.md` (dated status log), `docs/windows/MOBILE_PARITY.md` (mapping to the Android app), `docs/superpowers/specs/` (dated decision records), `docs/RELEASING.md` (release process), `PRODUCT.md` and `DESIGN.md` (agreed scope and visual contract).
