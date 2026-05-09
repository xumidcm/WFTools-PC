# AGENTS.md - WFTools Developer Guide

This file provides guidance for agentic coding agents working on the WFTools Android project.

## Project Overview

WFTools is an Android app (v6.0, min API 26, target API 35) for downloading, decrypting, extracting, and editing smartwatch watchface files. It supports multiple formats: HSC/HiWatch, WatchMaker (`.watch`), Facer (`.face`), Clockology (`.clock2`), and WFF.

## Build Commands

```bash
# Build
./gradlew assembleDebug          # Build debug APK
./gradlew assembleRelease        # Build release APK
./gradlew build                  # Full build (all variants)

# Testing
./gradlew test                   # Run unit tests
./gradlew connectedAndroidTest    # Run instrumented tests (requires device/emulator)

# Code Quality
./gradlew lint                   # Run lint checks

# Single Test Execution
./gradlew test --tests "com.ailife.wftools2.domain.extractor.SomeTest"  # Run specific test class
./gradlew test --tests "com.ailife.wftools2.domain.extractor.SomeTest.testMethod"  # Run specific test

# Debug APK Location
# app/build/outputs/apk/debug/app-debug.apk
```

## Architecture

Clean Architecture with MVVM pattern:

- **`ui/`** — Jetpack Compose screens + ViewModels (StateFlow). Each feature has `Screen.kt` + `ViewModel.kt`.
- **`domain/`** — Business logic: extractors (`WatchfaceExtractor` interface), editor abstractions (`WatchfaceProject`, `WatchfaceFormatAdapter`).
- **`data/`** — API clients (OkHttp, no Retrofit), `DownloaderRepository` aggregates external sources.

Key patterns:
- **Format Adapter Pattern**: `WatchfaceFormatAdapter` abstracts HSC vs WatchMaker editing differences.
- **Extractor Pattern**: `WatchfaceExtractor` interface with per-format impls. Auto-detection by file extension.
- **Undo/Redo in Editor**: `EditorViewModel` maintains a 50-state stack via sealed `EditorUiState`.
- **Storage Access Framework**: All file I/O uses SAF—never direct filesystem paths.
- **Coroutines**: All async work via `viewModelScope` + `Dispatchers.IO`.

## Code Style Guidelines

### Package Declaration
```kotlin
package com.ailife.wftools2.<module>.<submodule>
```

### Imports
Group imports in this order (no blank lines between groups):
1. Android/Kotlin standard library
2. Jetpack Compose (androidx.compose.*)
3. Navigation (androidx.navigation.*)
4. Lifecycle/ViewModel (androidx.lifecycle.*)
5. Third-party libraries (coil, okhttp, gson, dd-plist)
6. Project domain classes
7. Project data classes
8. Project UI classes

```kotlin
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ailife.wftools2.domain.editor.WatchfaceProject
import com.ailife.wftools2.data.repository.DownloaderRepository
import com.ailife.wftools2.ui.theme.WFToolsTheme
```

### Naming Conventions

| Element | Convention | Example |
|---------|------------|---------|
| Classes/Interfaces | PascalCase | `EditorViewModel`, `WatchfaceExtractor` |
| Functions/Properties | camelCase | `loadProjectFromUri()`, `uiState` |
| Sealed Classes | PascalCase | `EditorUiState`, `ResourceItem` |
| Enum Values | UPPER_SNAKE_CASE | `GLOBAL`, `LAYERS`, `PROPERTY` |
| Constants | UPPER_SNAKE_CASE | `MAX_HISTORY_SIZE = 50` |
| File Names | Match class name + .kt | `EditorViewModel.kt` |

### Sealed Classes for UI State
Use sealed classes for representing UI states:

```kotlin
sealed class EditorUiState {
    object Selection : EditorUiState()
    object Loading : EditorUiState()
    data class Editing(
        val project: WatchfaceProject,
        val projectDir: File,
        val adapter: WatchfaceFormatAdapter
    ) : EditorUiState()
    data class Error(val message: String) : EditorUiState()
}
```

### StateFlow Usage
Always use `MutableStateFlow` internally and expose `StateFlow`:

```kotlin
private val _uiState = MutableStateFlow<EditorUiState>(EditorUiState.Selection)
val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()
```

### ViewModels
- Extend `AndroidViewModel(application: Application)` when Android context is needed
- Use `viewModelScope.launch` for async operations
- Use `withContext(Dispatchers.IO)` for blocking I/O operations

```kotlin
class EditorViewModel(application: Application) : AndroidViewModel(application) {
    fun loadProjectFromUri(uri: Uri) {
        _uiState.value = EditorUiState.Loading
        viewModelScope.launch {
            when (val result = adapter.loadFromZip(uri)) {
                is ProjectLoadResult.Success -> { /* handle success */ }
                is ProjectLoadResult.Error -> _uiState.value = EditorUiState.Error(result.message)
            }
        }
    }
}
```

### Data Classes
Use data classes for models with `copy()` support:

```kotlin
data class WatchfaceItem(
    val id: String,
    val title: String,
    val previewUrl: String,
    val downloadUrl: String = "",
    val author: String = "",
    val isPremium: Boolean = false,
    val date: String = ""
)
```

### Collections
- Use `mutableStateListOf()` for Compose state lists
- Use `listOf()`, `setOf()`, `mapOf()` for immutable collections
- Prefer `toMutableList()` when mutation is needed

```kotlin
val selectedItems = remember { mutableStateListOf<ResourceItem>() }
val newLayers = state.project.layers.toMutableList()
```

### Error Handling
- Use sealed classes or Result types for operations that can fail
- Log exceptions with `e.printStackTrace()` for debugging
- Show user-friendly error messages in UI state

```kotlin
try {
    val result = adapter.loadFromZip(uri)
} catch (e: Exception) {
    e.printStackTrace()
    _uiState.value = EditorUiState.Error("Failed to load: ${e.message}")
}
```

### Coroutine Dispatchers
- `Dispatchers.Main` — Default for Compose, auto-used by `viewModelScope.launch`
- `Dispatchers.IO` — For file I/O, network calls, database operations
- `Dispatchers.Default` — For CPU-intensive computation

```kotlin
suspend fun downloadFile(url: String, file: File): Boolean {
    return withContext(Dispatchers.IO) {
        // Blocking I/O here
    }
}
```

### Compose Guidelines

**Composable Functions:**
- Use `@Composable` annotation
- Name with PascalCase
- Use `Modifier` as first optional parameter
- Destructure `remember`, `mutableStateOf` for clarity

```kotlin
@Composable
fun ExtractorScreen(
    onBackClick: () -> Unit,
    initialUri: Uri? = null,
    viewModel: ExtractorViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    // ...
}
```

**Scaffold Structure:**
```kotlin
Scaffold(
    topBar = { TopAppBar(title = { Text("Title") }) },
    floatingActionButton = { FAB(...) }
) { padding ->
    Box(modifier = Modifier.padding(padding)) { ... }
}
```

### File Organization

```
app/src/main/java/com/ailife/wftools2/
├── MainActivity.kt
├── WatchfaceActionActivity.kt
├── data/
│   ├── model/
│   ├── remote/
│   └── repository/
├── domain/
│   ├── converter/
│   ├── editor/
│   │   ├── hsc/
│   │   └── watchmaker/
│   └── extractor/
└── ui/
    ├── ai/
    ├── converter/
    ├── downloader/
    ├── editor/
    │   ├── components/
    │   │   └── tabs/
    │   └── utils/
    ├── extractor/
    └── theme/
```

### Testing

- Tests go in `app/src/test/java/` (unit tests)
- Test file naming: `ClassNameTest.kt`
- Use JUnit 4 assertions
- Mock dependencies with real implementations or simple stubs

```kotlin
class ExampleTest {
    @Test
    fun testAddition() {
        assertEquals(4, 2 + 2)
    }
}
```

### Dependencies

All dependency versions are centralized in `gradle/libs.versions.toml`. Update versions there, not in build.gradle.kts:

```toml
[versions]
agp = "8.8.1"
kotlin = "2.0.0"
coreKtx = "1.15.0"
lifecycleRuntimeKtx = "2.8.7"
activityCompose = "1.10.0"
composeBom = "2024.04.01"
navCompose = "2.8.0"
okhttp = "4.12.0"
coil = "2.6.0"
media3 = "1.2.0"

[libraries]
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
coil-compose = { group = "io.coil-kt", name = "coil-compose", version.ref = "coil" }
androidx-media3-exoplayer = { group = "androidx.media3", name = "media3-exoplayer", version.ref = "media3" }
androidx-media3-ui = { group = "androidx.media3", name = "media3-ui", version.ref = "media3" }
```

Additional dependencies declared directly in `build.gradle.kts`:
- `com.googlecode.plist:dd-plist:1.28` — Plist file parser
- `com.google.code.gson:gson:2.10.1` — JSON serialization
- `androidx.documentfile:documentfile:1.0.1` — SAF document file access

### Gradle Kotlin DSL

The project uses Kotlin DSL (`build.gradle.kts`). Use `alias` for plugins:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
```

### Constants

Define constants as top-level vals or in companion objects:

```kotlin
companion object {
    private const val MAX_HISTORY_SIZE = 50
    private const val EXPORT_QUALITY = 90
}
```

### Documentation

- Use KDoc for public APIs (`/** ... */`)
- No comments for obvious implementation details
- Document complex business logic in domain layer
