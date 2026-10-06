# Mosaic engine upgrade plan

This document records the audit of the existing app and the implementation plan for the production photomosaic engine. It is the source of truth for the work on this branch. Behavior changes versus the 1.x app are called out explicitly so review can check that no feature was dropped.

## 1. Audit (current `master`)

The project is a single-module Android app (`com.intrusivethots.mosaic`). There is no test source set, no CI workflow, and no persistent descriptor cache.

| Area | What exists today |
| --- | --- |
| UI | Jetpack Compose Material 3. `MainScreen.kt` is about 1,280 lines and owns Studio, Stamps, Library, and Settings. |
| State | `MainViewModel` exposes many independent `MutableStateFlow`s (`targetBitmap`, `tileUris`, `config`, `generationState`, …). |
| Engine | `MosaicEngine` analyzes tiles and renders in one class. |
| Matching | Average RGB. Distance is `2ΔR² + 4ΔG² + 3ΔB²`. Every cell scans every tile: **O(cells × tiles)**. |
| Tile prep | Every tile is force-scaled to a **64×64** square (`Bitmap.createScaledBitmap`), which stretches non-square photos. |
| Repetition | `allowTileRepetition` defaults to `true`, and the distance check runs only when repetition is **disabled**. The map stores one position per tile, so only the latest use is considered. If every tile is rejected, the code returns `tiles[0]`, which can sit inside the exclusion zone. The control is not in the UI. |
| Preview | Preview uses **half** the column/row count, so preview selection and final selection differ. |
| Color blend | A translucent RGB rectangle is drawn over each tile (`alpha = weight * 160`). |
| Memory | `android:largeHeap="true"`. Analysis and render allocate full `ARGB_8888` bitmaps. Adding or removing one tile clears the entire analysis. |
| AI | ML Kit subject segmentation (`SubjectSegmenterHelper`). Auto mode **replaces** each photo with cutouts. No cap, no minimum size, no dedup. The Stamps tab extracts subjects into a separate pool. |
| Projects | `filesDir/projects/library_index.json` plus JPEG files. A crash during `writeText` can corrupt the index. No orphan cleanup. |
| API key | Gemini key stored in plain `SharedPreferences` (`mosaic_settings`). The key is **not called** by any network client; on-device ML Kit does the segmentation. |
| Build | AGP 9.0.0, Kotlin 2.2.10, Compose BOM 2025.10.00, minSdk 29, compile/targetSdk 36. Release minify is on. Signing is optional via gitignored `keystore.properties`. |

Features that must keep working:

- Gallery and camera target selection, manual crop, reset crop.
- Aspect-ratio presets and the “link rows to aspect” switch.
- Standard grid and staggered-brick layout.
- Tile multi-picker and the Stamps tab (multi-subject extraction, per-stamp delete).
- Color blend control.
- Preview, full render, gallery export, project library, delete, enlarge.
- Optional user-supplied Gemini API key field.
- On-device subject segmentation toggle.

## 2. Architecture after the upgrade

```
:engine   pure Kotlin / JVM (no Android)
          color, descriptors, index, matcher, renderer, coordinator, file reconcile
:app      Android shell
          Bitmap adapters, ML Kit, Room, Keystore, Compose UI
```

The core stays free of `android.graphics.Bitmap` so unit tests, golden images, and benchmarks run on a JDK in CI.

`MosaicEngine` remains in the Android module as a backward-compatible facade. Existing method names (`analyzeTileImages`, `cropToAspectRatio`, `generateMosaic`) delegate to the new pipeline. `MosaicConfig` keeps its current constructor parameters, in the same order, with defaults on every new field.

### Pipeline

`GenerationCoordinator` is the only orchestration entry point:

1. **Loading** — decode thumbnails (long edge capped), skip corrupt files.
2. **Analyzing** — `TileAnalyzer` builds a `TileDescriptor` per tile. Cache hit skips recomputation.
3. **Indexing** — `TileIndex` buckets descriptors into OKLab bins.
4. **Matching** — `TileMatcher` scores a small candidate set per cell and writes a `MosaicPlan`.
5. **Rendering** — `MosaicRenderer` paints that plan. It does not choose tiles.
6. **Saving** — the Android layer writes files atomically, then inserts a Room row.
7. **Complete**

Preview and final render share one `MosaicPlan` when the match fingerprint (grid, weights, seed, tile keys, target size) is unchanged. They differ by output pixels per cell, not by which tile was chosen.

Cancellation is cooperative: suspend functions call `ensureActive()` on every tile and every grid row. The UI Cancel button cancels the generation `Job`.

Progress events are throttled to at most one emission per 100 ms, plus a forced emission on stage changes.

## 3. Tile analysis

`TileDescriptor` (algorithm version 1) stores:

- identity key: URI (or content hash for in-memory stamps), pixel size, byte size, modified time, algorithm version
- mean OKLab, relative luminance (OKLab L), saturation (OKLab chroma)
- 4×4×4 OKLab histogram (64 bins, normalized over opaque pixels)
- 4×4 spatial mean OKLab (48 floats)
- edge density from an 8×8 luminance grid
- alpha coverage (pixels with alpha > 40, same threshold the old engine used)
- source aspect ratio

Fully transparent images get a neutral mid-gray descriptor and zero coverage so they do not dominate matching.

`FileDescriptorCache` persists descriptors. Adding a tile analyzes that tile only. Removing a tile drops it from the active set and does not recompute the others. Changing the algorithm version misses the cache on purpose.

Analysis runs on a thumbnail whose long edge is the configured descriptor resolution (16–48 px). Full-resolution bitmaps are not retained.

## 4. Tile preparation

`TileFit.CENTER_CROP` (default) scales uniformly and crops the center so the tile fills the cell. `TileFit.FIT_INSIDE` letterboxes the whole tile on the cell’s target color. Neither path stretches.

Output cells follow the target cell’s aspect ratio. A wide cell stays wide; the tile is cropped into it, not squashed to a square.

Transparent source pixels (alpha ≤ 40) are filled with the cell color so AI cutouts composite onto the picture instead of black.

## 5. Index and score

OKLab is quantized to 8×8×8 bins. For each cell the index walks the bin and its neighbors and keeps a bounded set of legal candidates (default 8–48 depending on preset) ranked by OKLab distance. A dense bin is scanned through a window around the target luminance, sized to exceed the repetition exclusion zone, not through the whole library.

The match score (lower is better), after weights are normalized to sum to 1:

| Term | Default weight | Definition |
| --- | --- | --- |
| Color | 0.40 | OKLab Euclidean distance |
| Luminance | 0.15 | Absolute L difference |
| Histogram | 0.15 | L1 distance / 2 |
| Spatial | 0.20 | Mean absolute difference of the 4×4 OKLab grid |
| Edge | 0.10 | Absolute edge-density difference |

Aspect ratio is stored and used for cropping. It is not a score term.

### Repetition and balance

- `allowTileRepetition = false`: each tile is used at most once.
- `allowTileRepetition = true`: a tile may repeat, but not inside Chebyshev radius `maxRepetitionDistance` (default 3).
- Usage penalty among the legal candidates: `usageCount * usageBalanceWeight * 0.0005`. The unit is small on purpose: a hard repetition radius still applies when the user sets one, but the default radius is 0 so color fidelity is not traded away for spreading.
- Ties break with a deterministic hash of `(seed, column, row, tileId)`.
- If no legal tile exists in the candidate search, the cell is filled with its mean color. The fallback does **not** place `tiles[0]` inside the exclusion zone.

This replaces the inverted repetition check. Repeated tiles still happen, but they are spaced and balanced. That is a visible behavior change from 1.x, where the default configuration applied no repetition limit at all.

## 6. Rendering and memory

`RenderMode`:

- `ORIGINAL` — tile pixels only.
- `COLOR_CORRECTED` — shift each pixel in OKLab by `(cellMean - tileMean) * strength`. Local contrast stays in the tile. This replaces the flat translucent overlay.
- `BLENDED` — OKLab mix of the original pixel and the color-corrected pixel by `colorMatchWeight`. Strength 0 matches the original tile, so the existing slider still has a zero point.

`OutputMode` sets square-cell pixel size before aspect adjustment: Standard 24 px, High 40 px, Ultra 64 px. Preview uses `previewCellPixels` (default 8) and the same plan.

The renderer emits scanlines to a `RowSink`. `StreamingPngWriter` deflates those rows into PNG IDAT chunks. Peak raster memory is one row, not the full frame. Outputs above 2.5 megapixels are never assembled into one `Bitmap`. `android:largeHeap` is removed.

A bounded LRU holds decode bitmaps (default 32 MB). Thumbnails used for analysis are the only images kept for matching.

## 7. Quality presets

| Preset | Columns | Descriptor edge | Candidates | Output | Correction | Usage balance |
| --- | --- | --- | --- | --- | --- | --- |
| Draft | 24 | 16 | 8 | Standard | Blended 0.45 | 0.25 |
| Balanced | 40 | 24 | 16 | Standard | Color-corrected 0.65 | 0.60 |
| High quality | 64 | 32 | 32 | High | Color-corrected 0.75 | 1.00 |
| Maximum | 100 | 48 | 48 | Ultra | Color-corrected 0.85 | 1.40 |

Applying a preset keeps aspect ratio, layout style, seed, and AI settings. Editing an advanced control (grid, weights, blend, repetition, fit, output) switches the preset to Custom. Advanced controls remain on the Studio screen.

`MosaicConfig.validated()` coerces columns and rows into 4..200 (and never above the target’s pixel size), clamps weights, and normalizes score weights.

## 8. AI segmentation

ML Kit subject segmentation does **not** return semantic labels. The engine therefore exposes geometric categories the segmenter can actually honor:

- Tall (aspect &lt; 0.8)
- Compact
- Wide (aspect &gt; 1.25)

Automatic extraction during generate:

- drops subjects smaller than the configured minimum (default 48 px on the short side)
- drops shapes the user turned off
- deduplicates by histogram L1
- caps count so extracted subjects stay at or below `maxLibraryFraction` (default 0.35) of the combined library, and never above `maxExtractedSubjects`

Original photos stay in the library. 1.x auto mode replaced photos with cutouts; that overwhelmed the pool and destroyed color variety. The Stamps tab is unchanged in purpose: the user explicitly extracts subjects into the stamp pool. Manual stamps are still deduped and size-filtered, with a higher cap, because the user asked for them.

## 9. Secrets, projects, lifecycle

API keys are user-owned. The app does not embed a developer key and does not send the stored key anywhere. On-device ML Kit performs segmentation. The key field remains for the user and is stored with `EncryptedSharedPreferences` backed by an Android Keystore AES-256 master key. If a legacy plain preference exists, it is copied into the encrypted store and the plain value is removed. If the keystore cannot be created, the app refuses to persist the key and does not fall back to plaintext.

Projects move to Room (`projects` table: id, title, created time, preview path, full-image path, tile count, columns, rows, preset, missing-file flag). Image bytes stay files, not blobs. Writes go to a temp file, are fsynced, then renamed. The database row is inserted only after both files exist. Startup:

- imports `library_index.json` if present, then renames it aside
- flags rows whose files are gone
- deletes files in the project directory that no row references

Generation runs in `viewModelScope`. Cancel, failure, and success all publish one `MosaicUiState`. One-shot messages (export result, skip warnings) use a `SharedFlow` so they are not replayed after rotation. Saved projects are what survive process death; an in-progress studio session is not a project until save completes, so a kill mid-render cannot leave a half-written index.

## 10. UI split

`MainScreen` keeps navigation (Studio, Stamps, Library, Settings) and activity results. The rest moves to:

`StudioScreen`, `TargetImageCard`, `TileLibraryCard`, `MosaicControlsCard`, `GridControls`, `ColorControls`, `AiControls`, `PreviewPanel`, `GenerationProgress`, `ProjectLibraryScreen`, `ProjectCard`, `ExportDialog`, `SettingsDialog`, plus the existing crop dialog and Stamps tab in their own files.

`GenerationProgress` shows the stage label and a Cancel action.

## 11. Tests and benchmarks

JVM tests in `:engine` cover OKLab conversion, histograms, descriptors, cropping, indexing, scoring, repetition, usage balance, grid math, config validation, empty libraries, tiny images, large images, and transparent images.

Golden test: fixed synthetic target, tile set, config, and seed. The SHA-256 of the rendered pixels is compared to a committed digest so algorithm edits fail loudly.

Benchmarks (synthetic, diverse colors, JDK):

| Tiles | Grid |
| --- | --- |
| 100 | 40×40 |
| 500 | 60×60 |
| 1,000 | 80×80 |
| 5,000 | 120×120 |

Each run records load, analyze, index, match, render, total time, comparison count, and a JVM heap estimate. Render uses 12 px cells so CI measures the renderer without allocating multi-hundred-megabyte frames. A separate test asserts a wide frame is emitted row-by-row.

The benchmark also writes a side-by-side PNG of the legacy average-RGB matcher versus the new matcher.

Android instrumented tests cover Room insert/query. They need a device; CI runs them only when an emulator job is explicitly enabled. Repository file reconciliation is covered on the JVM.

## 12. Build and release

GitHub Actions on push and pull request:

- JDK 17, Android SDK
- `:engine:test`, `:engine:benchmark`
- `:app:assembleDebug`, `:app:testDebugUnitTest`, `:app:lintDebug`, detekt
- `:app:bundleRelease`
- upload the AAB and benchmark report
- if `KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` secrets exist, write `keystore.properties` and a keystore before the bundle task; otherwise the bundle is unsigned and the job still succeeds

`keystore.properties` and `*.jks` stay gitignored.

## 13. Validation matrix

| Case | How it is checked |
| --- | --- |
| Empty library | Unit test + UI error state |
| Corrupt image | Skipped tile, unit-level decoder failure path |
| Tiny and huge targets | Grid coercion tests |
| Transparent tiles | Descriptor + render tests |
| Cancel | Coroutine test |
| Missing project file | Reconcile test |
| Insufficient storage | `StatFs` check before save; instrumented/manual on device |
| Process death | Room + atomic files; manual: render, kill process, reopen library |
| Rotation | `ViewModel` retains `MosaicUiState`; manual |
| Low memory | No `largeHeap`, row-wise render, LRU cap; manual on a low-RAM device |
| Config change mid-edit | Preset drops to Custom; plan fingerprint invalidates |

## 14. Implementation order

1. This plan.
2. `:engine` color, image, config, analyzer, index, matcher, renderer, coordinator.
3. JVM tests, golden digest, benchmarks, comparison image.
4. Android facade, cache, Room, keystore, manifest (`largeHeap` removed).
5. `MosaicUiState` and Compose split.
6. Workflows, README, measured numbers.

Each step should compile. Engine tests must pass before the app is rewired, so the app cannot ship a second matching implementation.
