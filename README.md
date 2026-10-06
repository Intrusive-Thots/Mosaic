# Mosaic

Mosaic is an Android app that builds a photographic mosaic from your own pictures. The matching engine is pure Kotlin in the `:engine` module, so the algorithm, tests, and benchmarks run on a normal JVM. The Android app handles the camera, gallery, project library, and on-device subject segmentation.

## Architecture

```
:engine   color, descriptors, index, matcher, renderer, coordinator
:app      Compose UI, Room, Keystore, bitmap decode, ML Kit
```

Generation is coordinated by `GenerationCoordinator` and runs in this order:

1. Loading
2. Analyzing
3. Indexing
4. Matching
5. Rendering
6. Saving
7. Complete

Progress events are throttled by time. Cancel stops the coroutine between tiles and cells. Preview and the full render use the same matcher and the same seed. They differ by output resolution. A full render can reuse the preview's tile plan when the target, library, and matching settings are unchanged.

Full-resolution output is written scanline by scanline with `StreamingPngWriter`. The image is never one giant bitmap, and the manifest does not set `android:largeHeap`.

## Matching

Each tile is described once and cached. A descriptor stores:

- OKLab color, luminance, and saturation
- a 4×4×4 OKLab histogram
- a 4×4 spatial OKLab grid
- edge density
- alpha coverage and aspect ratio

The cache key is the URI, pixel size, byte size, modified time, and algorithm version. Adding or removing one photo does not rebuild the other descriptors.

Tiles are placed in an 8×8×8 OKLab bin index. For each target cell the matcher asks for a small candidate set: nearby bins first, then a luminance window, skipping bins that cannot beat the current best. Repetition radius and a per-cell probe budget bound the search. A 5,000-tile library does not compare every tile with every cell.

The default score is mostly OKLab color (0.78), with smaller luminance, histogram, spatial, and edge terms. A usage penalty exists, but its unit is `0.0005` per previous use and the default weight is 0, so it does not override a better color. The repetition radius defaults to 0 for the same reason: a radius of 2 or 3 on a flat region forces a worse tile and the mosaic turns speckled. Set a radius when you want hard spacing. If every legal tile is excluded by that radius, the cell is left as the target color. The matcher does not fall back to a tile inside the exclusion zone. Equal scores break with a deterministic seed.

## Tile preparation

Photos are not stretched into squares. The default is a center crop that keeps the source aspect inside the cell. Fit-inside letterboxes the tile instead. Mostly transparent pixels do not contribute a strong color.

## Non-square cells, rotation, and size

Uniform cells that follow the grid are still the default, and center-crop and fit-inside still work. Extra controls sit beside that:

- **Cell shape.** Match grid keeps the previous row math. Square, 4:3, 3:2, 16:9, and the portrait pairs set the cell aspect when rows are linked to the grid. Sampling cells and output cells share that aspect, so a tile is cropped or fitted into a rectangle instead of a square.
- **Mixed shapes.** The grid is packed with 1×1, 2×1, and 1×2 rectangles. A landscape photo can occupy a wide cell and a portrait photo a tall one. Every unit cell belongs to exactly one rectangle, so the mosaic has no gaps or overlaps. The seed makes preview and the full render pack the same way. Mixed layout does not shift rows like staggered bricks.
- **Automatic rotation.** Off leaves every tile upright. Match orientation tries 90° and 270° only when the tile aspect and the cell aspect disagree, so a portrait photo can fill a landscape cell. Full also tries 180° and a mirror. Each variant reuses the cached descriptor and only rotates the 4×4 spatial grid, so the photo is not decoded again per angle. Repetition and the usage penalty count the source photo, not each rotated copy.
- **Manual rotation.** The target has a rotate button (90° steps). Each library tile has one too. Target turns and the per-tile turns are stored in settings and on the Room project. A manual tile turn is part of the cache key (`uri#qN`).
- **Size.** A target scale from 25% to 100% is applied before matching. Full-render width and height can be custom; blank fields keep the Standard, High, or Ultra preset. Lock aspect derives the height from the rotated target. Each edge must be 0 or from 64 to 8192. A cell cannot exceed 512 pixels on a side, and the image cannot exceed 24 million pixels. Output above 2.5 million pixels is streamed to a PNG instead of one bitmap. The screen states the limit when a size is rejected. Custom output size does not change which tile is chosen, so preview and final still share a plan.

## Cutout collage

Grid mosaics are still the default. Cutout collage is a separate mode. A tile in that mode is an arbitrary shape with a transparent background: a person, a flower, an object, not a rectangle. Pieces may overlap. They are drawn large first, then smaller, so detail lands on top.

The engine does not search every cutout at every pixel. For each placement it picks the least-covered spot on a coarse grid, asks the existing OKLab index for a short candidate list, and scores only those candidates. Each candidate is tried at a bounded set of angles inside the rotation range (15° steps up to ±90°, 20° steps beyond that, at most about a dozen angles). Scale is not searched per pixel either. The run is three passes: the largest scale, a middle scale, then the smallest. The seed makes the anchor order deterministic, so preview and the full render share a plan. Output size is not part of that plan.

What a descriptor stores for a cutout, in addition to the usual OKLab color, histogram, and spatial grid:

- those color features are computed only on pixels with alpha above 40
- an 8×8 alpha mask of the opaque bounds
- the opaque bounds as fractions of the source, so rotation and scale do not need another decode

Rotation of a candidate remaps that mask. It does not rotate the bitmap until the renderer samples it. Repetition and the usage penalty count the source cutout, not each angle.

Rendering writes scanlines. Each output pixel starts as the target photo or the target's mean color, whichever background you picked, then cutouts are alpha-composited in order with a bilinear sample and a soft edge. Color correction, when it is on, shifts the piece toward the target region it covers and keeps the piece's alpha. The same 24-megapixel and 2.5-megapixel in-memory limits apply. There is no `android:largeHeap`.

Limits: 4 to 1,000 placements, scale from 3% to 90% of the target's short side, rotation from 0° to ±180°. Collage mode uses the Stamps tab and automatic extraction. Full photos are included only when you turn that on, or when no cutout could be loaded. On the stamp itself, Trim raises the alpha cutoff and crops to the remaining subject. Removing a stamp drops it from the pool. ML Kit still has no person or object labels, and it still needs the on-device model.

## Quality presets

Advanced controls stay available. Choosing a preset fills them in:

| Preset | Grid | Descriptor edge | Candidates | Output | Render |
| --- | ---: | ---: | ---: | --- | --- |
| Draft | 24×24 | 16 px | 8 | Standard (24 px cells) | Blended |
| Balanced | 40×40 | 24 px | 16 | Standard | Color corrected |
| High Quality | 64×64 | 32 px | 32 | High (40 px cells) | Color corrected |
| Maximum | 100×100 | 48 px | 48 | Ultra (64 px cells) | Color corrected |

Output modes are Standard, High, and Ultra. Render modes are Original, Color corrected, and Blended. Color correction shifts each tile toward the cell in OKLab. Blending mixes that correction with the original tile. The old flat translucent rectangle is gone.

Draft and Balanced leave the usage penalty at 0. High Quality and Maximum add a light penalty (0.15 and 0.30) so repeated near-matches rotate a little. A few hundred distinct photos is enough for a balanced grid. More than about a thousand helps Maximum grids. Very small libraries will repeat until you raise the repetition radius.

## Performance

Measured with `./gradlew :engine:benchmark` on this machine (OpenJDK 21, synthetic unique-hue tiles, 16 px analysis thumbnails, 12 px render cells, one discarded warmup pass). Heap is `totalMemory - freeMemory` around the case and is only an estimate. The full table is in [docs/benchmarks/results.md](docs/benchmarks/results.md).

| Tiles | Grid | Analyze ms | Index ms | Match ms | Render ms | Total ms | Probes | Full scan | Heap MB |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 40×40 | 5.4 | 0.3 | 55.4 | 48.7 | 111.0 | 84,135 | 160,000 | 0.6 |
| 500 | 60×60 | 12.7 | 0.6 | 38.3 | 108.8 | 163.2 | 233,676 | 1,800,000 | 1.9 |
| 1,000 | 80×80 | 23.9 | 0.8 | 72.5 | 192.7 | 293.9 | 415,996 | 6,400,000 | 4.0 |
| 5,000 | 120×120 | 113.0 | 2.3 | 163.5 | 433.9 | 725.2 | 936,000 | 72,000,000 | 13.6 |

"Full scan" is cells × tiles, which is what the 1.x matcher did. At 5,000 tiles the index probes about 1.3% of that. Matching 14,400 cells against 5,000 tiles took 164 ms in this run.

A separate 500-tile case uses 16:9 cells and orientation matching (250 landscape tiles, 250 portrait). The grid is 40×40. Match took 43.6 ms and 103,890 probes against a full scan of 800,000. Total time was 156.0 ms. Probes stay on the source photos; rotated copies are scored from the cached spatial grid.

Cutout collage, after a discarded warmup, on a 160×100 gradient:

| Cutouts | Requested | Placed | Match ms | Render ms | Total ms | Probes | Full scan |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 200 | 60 | 46 | 11.1 | 2.9 | 15.9 | 2,135 | 144,000 |
| 800 | 100 | 57 | 11.5 | 3.6 | 22.2 | 2,736 | 960,000 |

Full scan is requested pieces × cutouts × 12 angles. Placement stops once the coarse grid is covered, which is why fewer pieces land than were requested. The portrait sample (64 cutouts, 72 requested, seed 4) paints 59% of pixels. Whole-image ΔE is 0.0475 and SSIM is 0.7655, helped by the target underlayer. On painted pixels only, ΔE is 0.0989 and SSIM is 0.6873. The picture is `docs/images/cutout-collage.png`, target on the left.

Quality is measured on a separate portrait: a face, a gradient background, and a hard-edged flower, with 120 varied tiles, a 32×42 grid, and seed 7. Both sides are the original tile pixels through the same renderer, so neither side is helped by a color wash. Lower OKLab ΔE is closer color. Higher luminance SSIM is closer structure. `MatchingQualityTest` fails if the OKLab side stops beating average-RGB on either number.

| Matcher | Mean cell OKLab ΔE | Luminance SSIM |
| --- | ---: | ---: |
| Average RGB | 0.0453 | 0.5506 |
| OKLab default | 0.0436 | 0.6559 |

![Average-RGB selection on the left, OKLab selection on the right](docs/images/matching-comparison.png)

The picture is 656×420. The right side keeps the face, the background gradient, and the red flower. The left side breaks those regions into blockier, less accurate tiles. An earlier comparison looked worse on the OKLab side because a repetition radius of 3 and a large usage penalty were discarding the best color.

## AI segmentation

Subject extraction uses on-device ML Kit. It does not upload photos. Automatic extraction, when enabled, adds cutouts beside the original photos. It does not replace them. Limits:

- maximum number of extracted subjects
- minimum subject size
- shape categories (tall, wide, compact)
- deduplication
- a cap so cutouts stay a fraction of the normal library

The Stamps tab still extracts subjects from a picked photo into their own pool, with the same size and duplicate filters and a higher cap.

## Privacy and API keys

There is no developer API key in the app. An optional key you type is encrypted with an Android Keystore master key (`EncryptedSharedPreferences`) and stays on the device. If the keystore cannot be opened, the key is not written to plain preferences. A key left in the old plaintext setting is copied into encrypted storage and then removed.

This version does not send that key anywhere. Segmentation is on-device. The field is kept so a key you already saved is still available and is no longer stored in plaintext.

## Projects

Project metadata lives in Room (`mosaic.db`). Image files stay in app storage. Writes go to a temporary file, are flushed, then renamed. On launch, a missing preview or full image is marked on the project, and files that no longer belong to a project are deleted. An existing `library_index.json` is imported once and renamed to `library_index.json.migrated`.

## Build

Requirements: JDK 17, Android SDK 36.

```bash
./gradlew :engine:test
./gradlew :engine:benchmark
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:bundleRelease
```

Debug output: `app/build/outputs/apk/debug/app-debug.apk`

Without `keystore.properties`, release packaging still succeeds and produces an unsigned APK (`app-release-unsigned.apk`) and an unsigned AAB. To sign a release, create a gitignored `keystore.properties`:

```properties
storeFile=release.jks
storePassword=
keyAlias=
keyPassword=
```

Do not commit that file or the keystore.

## Testing

JVM tests cover OKLab conversion, histograms, cropping, descriptors, candidate indexing, repetition, usage balance, grid planning, non-square cells, cutout masks, collage placement, cancellation, atomic writes, and golden images. The grid golden digest is SHA-256 `0745027b47ef5ffb4aad0ea56cad0d5fa123d41795e750931914bd37af2194ba`. The cutout-collage golden digest is SHA-256 `efeb834c450fc38a6d24b6158ad1aef4a6d1af78564256b7f53723a8c817f200`.

```bash
./gradlew :engine:test :engine:detekt :app:detekt :app:lintDebug
```

`ProjectDaoTest` is an instrumented Room test. CI runs it on an API 29 emulator. It needs a device or emulator locally:

```bash
./gradlew :app:connectedDebugAndroidTest
```

## Release procedure

GitHub Actions (`.github/workflows/ci.yml`) runs engine tests, detekt, benchmarks, Android lint, debug and release packages, and the instrumented test. Release signing uses these repository secrets when they are present:

- `KEYSTORE_BASE64`
- `STORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

If `KEYSTORE_BASE64` is empty, the workflow logs that and still builds unsigned artifacts. Upload the AAB to Play Console from a machine that has the real upload key. This repository does not contain one.

## Known limitations

- Photo picker batches are capped at 100 images by the system picker contract. Add more in further picks.
- Ultra output is a large PNG. The renderer streams it, but the device still needs enough free storage. The app checks free space before a full render and refuses when the estimate plus an 8 MB reserve does not fit.
- Descriptor modified time is whatever the content provider reports. Providers that omit it fall back to file size, so a same-size replacement may reuse a cached descriptor until the photo is removed and added again.
- ML Kit subject segmentation needs the Play services model. The first extraction can fail until that model is available. The app keeps the original photos either way.
- Benchmark heap figures move around with the garbage collector. Use the probe counts and stage times for comparisons.
- Process death, a real low-memory device, and Play Console signing were not exercised in the automated JVM run. Projects are reloaded from Room on startup, and generation is cancelled by cancelling the coroutine job.
