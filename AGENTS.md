# AGENTS.md — TxtReaderApp

## Build & Run

```bash
# Windows
.\gradlew.bat :app:assembleDebug

# macOS/Linux
./gradlew :app:assembleDebug
```

No test suite exists. `assembleDebug` is the only verification.

## Critical: AGP 9 + KSP Compatibility

`gradle.properties` has `android.disallowKotlinSourceSets=false` — **do not remove**. AGP 9.3.0 with built-in Kotlin blocks KSP's source-set DSL without this flag. If you see `"Using kotlin.sourceSets DSL to add Kotlin sources is not allowed with built-in Kotlin"`, this flag is missing.

## Architecture

Single-module Android app. Package: `com.xld.txtreader`

```
core/           — Domain logic (no Android framework deps except android.text for StaticLayout)
  RegexPresets.kt    — 17 regex presets + CUSTOM_INDEX=16 sentinel
  ChapterParser.kt   — Line-by-line scan, regex must match at line start (range.first==0), ≤80 char guard
  Paginator.kt       — android.text.StaticLayout-based pagination (shared by UI + TTS service)
  EncodingReader.kt  — BOM detection, UTF-8 strict → GBK fallback
  BookContent.kt     — Book model + BookSearcher for full-text search

data/           — Persistence layer
  db/BookRecord.kt   — Room entity, filePath as PrimaryKey
  db/BookDao.kt      — DAO with Flow queries
  db/AppDatabase.kt  — Room database
  BookRepository.kt  — Import/delete/progress facade
  SettingsStore.kt   — SharedPreferences (font, colors, viewport, TTS speed)

tts/            — Text-to-speech
  TtsBus.kt          — StateFlow for TTS state
  TtsController.kt   — startForegroundService commands
  TtsService.kt      — Foreground service, notification controls, auto page/chapter advance

ui/             — Compose UI
  booklist/          — BookListScreen + BookListViewModel (shelf, sort, search, batch delete)
  reader/            — ReaderScreen + ReaderViewModel (reading, settings, TTS, search)
  SettingsUtil.kt    — ACTION_APP_OPEN_BY_DEFAULT_SETTINGS helper
  theme/             — MaterialTheme
```

Entry: `MainActivity` → NavHost with routes `booklist` / `readerContent`. ViewModel factories via `APPLICATION_KEY` pattern (no Hilt/DI framework).

## Gotchas

### Color handling
**Always use `Color(argb.toInt())`**, never `Color(argb.toULong())`. The `Color(ULong)` constructor expects Compose's internal encoding, not raw ARGB. `bgColor`/`textColor` are stored as `Long` (e.g. `0xFFFFF9F0`) — cast with `.toInt()` to get the correct ARGB Int.

### Chapter regex
- Presets are integer-indexed (0–16). Index 16 = custom regex.
- `setRegex(Int)` handles preset selection; `setRegexCustom(String)` handles custom input.
- `ChapterParser.parse()` matches line-by-line. Regex must match at column 0 (`range.first == 0`). Lines >80 chars are skipped as body text.

### Room KSP
Room compiler uses KSP (not kapt). Entity `BookRecord` uses `@Upsert` for dedup by `filePath`.

### Icons
Use `material-icons-core` only — do not add `material-icons-extended`. Use individual vector drawables in `res/drawable/` for custom icons (ic_pause, ic_play, ic_sort, ic_chapters, ic_speaker, ic_skip_prev, ic_skip_next).

### Deprecation warnings (non-blocking)
- `Icons.Filled.ArrowBack` → use `Icons.AutoMirrored.Filled.ArrowBack`
- `MenuAnchorType` → renamed to `ExposedDropdownMenuAnchorType`
- `fallbackToDestructiveMigration()` → new overload requires boolean param
- `AnimatedVisibility` inside Column scope → use fully-qualified `androidx.compose.animation.AnimatedVisibility(...)` to avoid ColumnScope extension resolution

### External file opening
`ACTION_VIEW` intents (content:// or file://) are handled in `MainActivity.handleOpenIntent()`. content:// URIs are read via ContentResolver (requires `MANAGE_EXTERNAL_STORAGE`). file:// paths are read directly. Navigation uses `OpenBookStore` singleton to pass file path (no route args).

## Conventions

- No comments in code unless explicitly requested
- No DI framework — manual singleton pattern via `TxtReaderApplication` (extension properties `appSettings`, `appRepositories`)
- Each book stores its own regex preference independently in Room DB
- Pagination is computed via `android.text.StaticLayout` (shared between Compose UI and TTS service for consistency)
- TTS notification uses plain `NotificationCompat` (no MediaSession, no `androidx.media` dependency)
