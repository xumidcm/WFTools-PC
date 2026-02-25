# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**WFTools** is an Android app (v6.0, min API 26, target API 35) for downloading, decrypting, extracting, and editing smartwatch watchface files. It supports multiple formats: HSC/HiWatch, WatchMaker (`.watch`), Facer (`.face`), Clockology (`.clock2`), and WFF.

## Build Commands

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew assembleRelease        # Build release APK
./gradlew build                  # Full build (all variants)
./gradlew test                   # Unit tests
./gradlew connectedAndroidTest   # Instrumented tests (requires device/emulator)
./gradlew lint                   # Run lint checks
```

No tests are currently written, but JUnit 4 + Espresso are configured.

## Architecture

Clean Architecture with MVVM pattern:

- **`ui/`** — Jetpack Compose screens + ViewModels (StateFlow). Each feature has `Screen.kt` + `ViewModel.kt`.
- **`domain/`** — Business logic: extractors (`WatchfaceExtractor` interface with format-specific impls), editor abstractions (`WatchfaceProject`, `WatchfaceFormatAdapter`).
- **`data/`** — API clients (OkHttp, no Retrofit), `DownloaderRepository` aggregates 7 external sources.

Key patterns:
- **Format Adapter Pattern**: `WatchfaceFormatAdapter` abstracts HSC vs WatchMaker editing differences. Implement this interface when adding new editable formats.
- **Extractor Pattern**: `WatchfaceExtractor` interface with per-format impls. Auto-detection by file extension.
- **Undo/Redo in Editor**: `EditorViewModel` maintains a 50-state stack via sealed `EditorUiState`.
- **Storage Access Framework**: All file I/O uses SAF—never direct filesystem paths.
- **Coroutines**: All async work via `viewModelScope` + `Dispatchers.IO`.

## Supported Watchface Formats

| Format | Extension | Config File | Encryption |
|--------|-----------|-------------|------------|
| HSC/HiWatch | `.zip` | `dial.json` | None |
| WatchMaker | `.watch` | `watch.xml` | Base64 + XOR |
| Facer | `.face` | `watchface.json` | Base64 |
| Clockology | `.clock2` | `watchface.json` | Binary plist (dd-plist) |
| WFF | `.wff` | Varies | Varies |

HSC format decryption and `dial.json` schema are documented in `docs/06-CRYPTO-AND-FILE-FORMATS.md`.

## External APIs

| Source | Base URL | Notes |
|--------|----------|-------|
| HSC/MongoYo | `http://api.mongoyo.com:8081` | Plain HTTP — `usesCleartextTraffic=true` |
| Facer Parse | `https://api.facer.io/parse` | |
| WatchFrenzy | `https://watchfrenzy.herokuapp.com` | WatchMaker Official |
| WatchMaker Community | `https://chujalt.com/jb.html` | HTML scraping |

API details (endpoints, headers, pagination) are in `docs/05-API-REFERENCE.md`.

## Key Files

- `app/src/main/java/com/ailife/wftools2/` — All Kotlin source (41 files)
- `app/src/main/AndroidManifest.xml` — Intent filters for `.watch`, `.face`, `.clock2`, `.hwf`, `.zip`
- `gradle/libs.versions.toml` — Single source of truth for all dependency versions; update here first
- `docs/` — 8 detailed markdown docs covering conversion logic, crypto algorithms, API specs

## Dependencies (Key)

- Jetpack Compose BOM `2024.04.01`, Navigation `2.8.0`
- OkHttp `4.12.0` (HTTP client — no Retrofit)
- Coil `2.6.0` (image loading)
- GSON `2.10.1`
- `dd-plist:1.28` (binary plist parsing for Clockology)

## Navigation

`MainActivity` sets up a `NavHost`. `WatchfaceActionActivity` handles external file intents and routes to either the Extractor or Editor. The Converter and Tools screens exist in the Home menu but are currently disabled.
