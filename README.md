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

Grid mosaics are still the default. Cutout collage is a separate mode. It rebuilds the target out of paper shapes cut from the source images. A piece follows a region of the target: a coat, a face, a hair edge, an eye line. It is not a rectangle of a still dropped next to other rectangles. Pieces overlap. Large shapes go down first and block in the regions. Smaller shapes and contour strokes land later and can cover what is already there.

The target is analyzed with its long edge capped at 360. The coarse step follows the large-shape scale and stays at least 18% of the short side, so the first layer is a few big regions. Adjacent regions with close OKLab means are merged unless a strong edge runs along their shared boundary. Fine cuts are kept only at 7% scale or smaller, and only the ones on the strongest gradients. Edge strokes sit on the darker side of a luminance gradient. Of the requested piece count, the coarse layer uses up to 34% and at most 42 shapes, the fine layer up to 6% and at most 48, and the edge layer up to 10% and at most 72. Flat areas are left as the large shapes. Color is measured on the undilated core, so a thin dark line is not averaged with the field around it. The mask is grown by the overlap setting, softened, and stored at up to 128 pixels on a side with partial alpha.

The engine does not search every source for every shape. Each shape asks the OKLab index for a short candidate list, then scores only that list. A blocking shape, about 5.5% of the short side or larger, looks for a low-detail crop: upright, a small window, and a short span, so a mostly uniform patch covers the region. A smaller piece scores spatial samples plus the region's average color, and tries crops and rotations inside the texture-rotation range. The seed only jitters ties. Preview and the full render share the plan. Output size is not part of it.

A shaped piece is filled from the source region that won, not from the whole cutout. Transparent texels are filled from the nearest opaque source color first, so a cleared background does not punch a hole, and the sample is bilinear. Later shapes overwrite earlier ones. There is no rectangular border. A saved placement that has no mask still uses the older stamp path, which alpha-composites the cutout and only replaces a pixel when the new color is closer to the target.

While pieces land, the coordinator renders a small preview twice: after the large shapes, and again after the edge shapes. The progress label changes with the stage. Cancel still stops the loop. The full export is the same chunked scanline renderer as the grid.

What a descriptor stores for a cutout, in addition to the usual OKLab color, histogram, and spatial grid:

- those color features are computed only on pixels with alpha above 40
- an 8×8 alpha mask of the opaque bounds
- the opaque bounds as fractions of the source, so a stamp rotation does not need another decode

The shaped matcher builds a 12×12 OKLab swatch from the opaque pixels inside those bounds. Repetition and the usage penalty count the source image, not each crop or angle.

Rendering writes scanlines. The default background is the target's mean color, not the target photo. The target photo is still available as an explicit background if you want it under any gap. When color correction is on, each shape is shifted in OKLab so its mean and luminance spread match the target region it covers, and a fraction of each pixel is then pulled toward a 64-pixel-wide average of the target. Color-corrected mode uses strength × 0.22 for that nudge. Blended mode uses strength × 0.12, so Torn paper at strength 0.40 stays close to the paper. Strength 0 leaves the pixel unchanged. The published synthetic sample leaves correction off, so the source colors have to rebuild the picture. The showcases use color-corrected mode at strength 0.72. An optional Separate pieces switch draws a short dark offset under stamp placements. Shaped pieces follow the mask and do not use that offset.

A final collage is scaled so its long edge is at least 1600 px on Standard, 2200 on High, and 2800 on Ultra, then clamped to 24 million pixels. Preview and a custom width or height skip that floor. Output above 2.5 million pixels is streamed to a PNG. A size that would break the cell or pixel cap is rejected with the limit on screen. There is no `android:largeHeap`.

Limits: 4 to 4,000 placements, scale from 1.5% to 90% of the target's short side, rotation from 0° to ±180°. The default asks for 480 pieces, from 3% to 16% of the short side. The coarse shapes cover the picture, and the rest of the budget is smaller edge shapes. Quality presets set the collage budget, scale range, and correction strength along with the grid knobs. A style then sets overlap, rotation, and color. Advanced sliders stay behind an Advanced section. Collage mode uses the cutout pool and automatic extraction. Full photos are included only when you turn that on, or when no cutout could be loaded. After a mask is cropped, alpha below 24 is dropped, enclosed holes are filled from the nearest opaque color, specks smaller than 24 pixels are removed, and the outer edge is softened. On the stamp itself, Trim raises the alpha cutoff and crops to the remaining subject. Edit can erase with a finger and apply that soft edge again. The refined mask is stored with the pool. You can add many photos at once; extraction reports progress and can be cancelled. ML Kit still has no person or object labels, and it still needs the on-device model.

## Quality presets

Advanced controls stay available. Choosing a preset fills them in:

| Preset | Grid | Descriptor edge | Candidates | Output | Render | Collage pieces | Collage scale | Adjustments |
| --- | ---: | ---: | ---: | --- | --- | ---: | --- | ---: |
| Draft | 24×24 | 16 px | 8 | Standard (24 px cells) | Blended | 180 | 5–22% | 0 |
| Balanced | 40×40 | 24 px | 16 | Standard | Color corrected | 480 | 3–16% | 8 |
| High Quality | 64×64 | 32 px | 32 | High (40 px cells) | Color corrected | 1,200 | 2–11% | 14 |
| Maximum | 100×100 | 48 px | 48 | Ultra (64 px cells) | Color corrected | 2,200 | 1.5–8% | 20 |

Output modes are Standard, High, and Ultra. Render modes are Original, Color corrected, and Blended. Grid color correction shifts each tile toward the cell in OKLab. Collage correction shifts chroma and keeps most of the cutout's luminance variation. Blending uses a weaker chroma shift. The old flat translucent rectangle is gone. Correction strength is 0.45, 0.65, 0.75, and 0.85 for the four presets.

Draft and Balanced leave the usage penalty at 0. High Quality and Maximum add a light penalty (0.15 and 0.30) so repeated near-matches rotate a little. A few hundred distinct photos is enough for a balanced grid. More than about a thousand helps Maximum grids. Very small libraries will repeat until you raise the repetition radius.

## Performance

Measured with `./gradlew :engine:benchmark` on this machine (OpenJDK 21, synthetic unique-hue tiles, 16 px analysis thumbnails, 12 px render cells, one discarded warmup pass). Heap is `totalMemory - freeMemory` around the case and is only an estimate. The full table is in [docs/benchmarks/results.md](docs/benchmarks/results.md).

| Tiles | Grid | Analyze ms | Index ms | Match ms | Render ms | Total ms | Probes | Full scan | Heap MB |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 40×40 | 4.5 | 0.2 | 49.3 | 49.4 | 104.5 | 84,135 | 160,000 | 0.6 |
| 500 | 60×60 | 51.1 | 0.5 | 46.6 | 111.5 | 212.0 | 233,676 | 1,800,000 | 1.9 |
| 1,000 | 80×80 | 22.5 | 0.6 | 68.4 | 195.5 | 291.5 | 415,996 | 6,400,000 | 4.1 |
| 5,000 | 120×120 | 119.6 | 3.0 | 145.7 | 439.7 | 721.5 | 936,000 | 72,000,000 | 13.5 |

"Full scan" is cells × tiles, which is what the 1.x matcher did. At 5,000 tiles the index probes about 1.3% of that. Matching 14,400 cells against 5,000 tiles took 145.7 ms in this run. Probe counts match the previous grid run.

A separate 500-tile case uses 16:9 cells and orientation matching (250 landscape tiles, 250 portrait). The grid is 40×40. Match took 79.8 ms and 103,890 probes against a full scan of 800,000. Total time was 199.4 ms. Probes stay on the source photos; rotated copies are scored from the cached spatial grid. Probe counts match the previous grid run, and the grid golden image is unchanged.

Cutout collage, after a discarded warmup, on a 160×100 gradient. The coarse pass is a full partition, so coverage is 100% even when the piece budget asks for more regions than the scale step can cut. Probes stay far below a full scan. The 900-piece row uses the same scale range as the smaller rows.

| Cutouts | Requested | Placed | Match ms | Render ms | Total ms | Probes | Full scan | Painted |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 200 | 160 | 44 | 63.3 | 6.9 | 74.7 | 1,662 | 384,000 | 100% |
| 600 | 280 | 51 | 36.1 | 5.5 | 56.3 | 2,208 | 2,016,000 | 100% |
| 400 | 900 | 83 | 31.8 | 5.4 | 47.1 | 3,365 | 4,320,000 | 100% |

A hybrid case on the same gradient, 200 cutouts, 160 requested pieces, and a 20×12 grid under the collage, took 51.7 ms and 11,464 probes. It placed 44 shapes.

Full scan is requested pieces × cutouts × 12 angles. The portrait sample uses 240 cutouts and 320 requested pieces, seed 4, on a flat mean-color background, locked to 280×180 so it lines up with the previous residual file. Correction is off. The shaped run placed 61 pieces and painted every pixel. The stamp row is the previous placer on this same portrait. Its edge-alignment figure used the edge pixel itself, and its distance scores were not stored. The previous shaped row is the run before flat crops and tone transfer. Its distance was not stored either. The shaped figure is the mean target-edge strength within two pixels of a piece boundary, divided by the picture average. Above 1 means the cuts follow edges. Distance ΔE and distance SSIM compare both images after area-averaging to 64 pixels wide.

| | Whole ΔE | Whole SSIM | Masked ΔE | Masked SSIM | Edge ΔE | Painted | Alignment | Distance ΔE | Distance SSIM |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Underlayer sample | 0.0475 | 0.7655 | 0.0989 | 0.6873 | — | 59% | — | — | — |
| Previous residual | 0.0334 | 0.6746 | 0.0477 | 0.6779 | 0.0563 | 96% | — | 0.0444 | 0.8026 |
| Stamp collage | 0.0323 | 0.7045 | 0.0447 | 0.7061 | 0.0527 | 94% | 3.211 | — | — |
| Previous shaped | 0.0341 | 0.7417 | 0.0488 | 0.7417 | 0.0556 | 100% | 10.4693 | — | — |
| Shaped collage | 0.0359 | 0.8605 | 0.0425 | 0.8605 | 0.0588 | 100% | 32.5000 | 0.0410 | 0.8541 |
| Photo textures | 0.0368 | 0.8788 | 0.0429 | 0.8788 | 0.0521 | 100% | 32.5000 | 0.0416 | 0.8912 |

| | Whole ΔE | Whole SSIM | Masked ΔE | Masked SSIM | Edge ΔE | Painted | Alignment | Distance ΔE | Distance SSIM | Generate ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Color only | 0.0359 | 0.8605 | 0.0425 | 0.8605 | 0.0588 | 100% | 32.5000 | 0.0410 | 0.8541 | 98 |
| Shape-aware | 0.0359 | 0.8605 | 0.0425 | 0.8605 | 0.0588 | 100% | 32.5000 | 0.0410 | 0.8541 | 89 |
| Dense 1,200 | 0.0367 | 0.8869 | 0.0434 | 0.8869 | 0.0434 | 100% | 15.1046 | 0.0417 | 0.8493 | 162 |
| Grid under collage | 0.0359 | 0.8605 | 0.0425 | 0.8605 | 0.0588 | 100% | 32.5000 | 0.0410 | 0.8541 | 90 |
| Collage under grid | 0.0213 | 0.3784 | 0.0319 | 0.3503 | 0.0355 | 16% | 32.5000 | 0.0342 | 0.8021 | 89 |

Masked scores count only pixels a cutout painted. Edge ΔE is the mean OKLab distance on the covered pixels whose reference luminance gradient is in the top quarter. On this correction-off portrait, whole ΔE is 0.0359, masked ΔE is 0.0425, luminance SSIM is 0.8605, and distance SSIM is 0.8541 at a distance ΔE of 0.0410. Coverage is 100%. The dense run asks for 1,200 pieces at the High Quality scale range on a 560×360 canvas: whole ΔE 0.0367, SSIM 0.8869, distance ΔE 0.0417, distance SSIM 0.8493. Shape weight is still stored on the session. Both of those rows cut the same target shapes, so the numbers match. Collage-under-grid paints the grid over the shapes except for a grout gap, so its masked coverage is the grout (16%) and its whole-image SSIM follows the grid.

On the 280×180 face, against the frozen previous residual file: left eye 37.1 → 40.2, right eye 45.1 → 39.4, mouth 18.8 → 22.0, cheek 16.2 → 9.2. Correction is off in this sample, so a small window still picks up source texture. The right eye and the cheek are closer than the residual. The left eye and the mouth are a little farther.

Those scores are the synthetic portrait the benchmark measures, with correction off. The showcases below use color-corrected mode at strength 0.72. Distance readability on the committed pictures, area-averaged to 64 pixels wide:

| | Naruto ΔE | Naruto SSIM | Dense ΔE | Dense SSIM | Rick ΔE | Rick SSIM | Rick dense ΔE | Rick dense SSIM |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Stamp collage | 0.1231 | 0.5617 | 0.1268 | 0.5373 | 0.1003 | 0.5753 | 0.1080 | 0.5249 |
| Previous shaped | 0.1336 | 0.4906 | 0.1154 | 0.5976 | 0.0831 | 0.6654 | 0.0953 | 0.6198 |
| This run | 0.0852 | 0.7495 | 0.0852 | 0.7615 | 0.0775 | 0.7053 | 0.0767 | 0.6857 |

The pictures below are separate showcases. `scripts/regenerate-showcase.py` rebuilds the Naruto pictures, and `scripts/regenerate-showcase.py rick` rebuilds the Rick and Morty pictures. Sources download into the gitignored `showcase-sources/` folder. The source stills are not in the repository. They belong to their respective owners. These showcases are a non-commercial demonstration.

The current theme is Rick and Morty. The target is the Rick and Morty Wiki file *Smith family adult swim*: Rick and Morty standing together on a light background, Rick in the white coat with blue hair and Morty in the yellow shirt. The library is 100 other Rick and Morty Wiki images, listed with their URLs in [docs/showcase/rick-sources.tsv](docs/showcase/rick-sources.tsv). They are well-lit character pictures, including the Smith family and other bright figures, chosen for yellow, blue, white, and skin tones. A dark frame is not used. A flat border is flooded away to leave the subject. A bright picture that already fills the frame stays as a light-edged stamp. The collage seed is 4. The grid seed is 7. The grid color-corrects each tile toward its cell at strength 0.58. The collage color-corrects each shape toward its region at strength 0.72. These files sit beside the Naruto pictures; they do not replace them.

![Rick and Morty, average-RGB selection, and OKLab selection](docs/images/rick-matching-comparison.png)

The grid comparison is the target, then average-RGB selection, then the OKLab matcher. Both selections use the same 64-column grid, seed 7, and color correction at strength 0.58.

![Target, coarser collage, current collage, and opaque photo collage](docs/images/rick-cutout-collage.png)

The collage row is the target, then 220 larger shapes, then 720 shapes at 2.2–9% scale, then the same 720-piece settings using the opaque photos. Repetition radius is 2 so one source cannot tile the row beside itself. Color correction strength is 0.72. The coarse layer still blocks in at 18% or larger.

![Target, large blocking shapes, and edge-following collage](docs/images/rick-cutout-shape-compare.png)

Shape row: the target, then 160 large shapes at 8–20% scale, then the 720-piece collage whose smaller cuts follow edges.

![Dense collage](docs/images/rick-cutout-collage-dense.png)

The dense collage is a full-size result: 1,100 pieces at 1.8–7% scale, so more of the budget is small edge shapes.

![Full-size grid mosaic](docs/images/rick-showcase-grid.png)

The grid is 80 columns, color correction strength 0.58, seed 7.

![Cutouts only, grid under collage, and collage under grid](docs/images/rick-cutout-hybrid.png)

Hybrid, left to right: cutouts only, grid under the collage, collage under the grid. Each asks for 480 pieces on a 48-column grid.

A full-size 720-piece collage is in `docs/images/rick-cutout-collage-output.png`. The comparison strips are scaled to a 1680 px long edge. Panel renders use an 840 px long edge before they are joined.

The earlier Naruto showcase is still in `docs/images/` without a prefix. The target is the Naruto Wiki file *Team Kakashi*: Naruto, Sakura, Sasuke, and Kakashi on a white background, with orange, pink, blue, and silver large enough to read. Group pictures that also include Sai are night scenes or monochrome drawings, so this brighter photograph is the target. The library is 100 other Naruto Wiki images, listed with their URLs in [docs/showcase/sources.tsv](docs/showcase/sources.tsv). The same cutout rules and seeds apply.

![Target, coarser collage, current collage, and opaque photo collage](docs/images/cutout-collage.png)

The collage row is the target, then 220 larger shapes, then 720 shapes at 2.2–9% scale, then the same 720-piece settings using the opaque photos. Repetition radius is 2 so one source cannot tile the row beside itself. Color correction strength is 0.72. The coarse layer still blocks in at 18% or larger.

![Target, large blocking shapes, and edge-following collage](docs/images/cutout-shape-compare.png)

Shape row: the target, then 160 large shapes at 8–20% scale, then the 720-piece collage whose smaller cuts follow edges.

![Dense 1,200-piece collage](docs/images/cutout-collage-dense.png)

The dense collage is a full-size result: 1,100 pieces at 1.8–7% scale, so more of the budget is small edge shapes. The picture is 1680×1380.

![Full-size grid mosaic](docs/images/showcase-grid.png)

The grid is 80 columns, color correction strength 0.58, seed 7. The picture is 1680×1365.

![Cutouts only, grid under collage, and collage under grid](docs/images/cutout-hybrid.png)

Hybrid, left to right: cutouts only, grid under the collage, collage under the grid. Each asks for 480 pieces on a 48-column grid.

A full-size 720-piece collage is in `docs/images/cutout-collage-output.png` (1680×1380). The comparison strips are scaled to a 1680 px long edge. Panel renders use an 840 px long edge before they are joined.

Quality on the synthetic portrait is a separate check: a face, a gradient background, and a hard-edged flower, with 120 varied tiles, a 32×42 grid, and seed 7. Both sides are the original tile pixels through the same renderer, so neither side is helped by a color wash. Lower OKLab ΔE is closer color. Higher luminance SSIM is closer structure. `MatchingQualityTest` fails if the OKLab side stops beating average-RGB on either number.

| Matcher | Mean cell OKLab ΔE | Luminance SSIM |
| --- | ---: | ---: |
| Average RGB | 0.0453 | 0.5506 |
| OKLab default | 0.0436 | 0.6559 |

![Team 7, average-RGB selection, and OKLab selection](docs/images/matching-comparison.png)

The Team 7 grid comparison is the target, then average-RGB selection, then the OKLab matcher. Both selections use the same 64-column grid, seed 7, and color correction at strength 0.58, so the difference is which tile was chosen. On the synthetic portrait the same test is a 656×420 picture with the original tile pixels: the OKLab side keeps the face, the background gradient, and the red flower, and the average-RGB side breaks those regions into blockier tiles. An earlier comparison looked worse on the OKLab side because a repetition radius of 3 and a large usage penalty were discarding the best color.

## AI segmentation

Subject extraction uses on-device ML Kit. It does not upload photos. Automatic extraction, when enabled, adds cutouts beside the original photos. It does not replace them. Limits:

- maximum number of extracted subjects
- minimum subject size
- shape categories (tall, wide, compact)
- deduplication
- a cap so cutouts stay a fraction of the normal library

The cutout pool extracts subjects from many picked photos, with the same size and duplicate filters and a higher cap. Each kept mask is cleaned before it is shown: a faint halo is removed, enclosed holes are filled, tiny specks are dropped, and the outer edge is softened. Thumbnails sit on a checkerboard. You can filter by shape, remove a cutout, or clear the pool. Trim, erase, and soft edge are stored back into the pool. If the segmenter misses a shape, add or delete stamps by hand.

## Collage styles

A style is a look, separate from the quality preset's piece budget. Sparse is the one that also cuts the piece count.

| Style | Overlap | Rotation | Shadow | Outline | Feather | Coverage | Shape weight | Color |
| --- | ---: | ---: | --- | --- | ---: | ---: | ---: | --- |
| Torn paper | 0.12 | ±6° | on | off | 0.20 | 0.90 | 0.50 | Blended 0.40 |
| Hard cuts | 0.02 | ±12° | off | on | 0 | 0.72 | 0.60 | Original |
| Soft overlap | 0.70 | ±28° | off | off | 0.55 | 0.98 | 0.25 | Blended 0.72 |
| Fewer shapes | 0.08 | ±16° | on | off | 0.10 | 0.45 | 0.55 | Corrected 0.45 |
| Shaped coverage | 0.38 | unchanged | off | off | 0.08 | 0.99 | 0.40 | unchanged |

Every style cuts the target into shapes. Overlap, rotation, piece count, and color strength are what change the picture. Fewer shapes also lowers the piece count. Hard cuts is low overlap and original color, not a grid of rectangles. Feather, outline, and the separate-pieces shadow still apply to a stamp placement that has no mask.

## Stacking with the grid

The stack is part of the match fingerprint, so preview and export of one stack share a plan. Grid under collage paints the mosaic first and the cutouts on top. Collage under grid paints the cutouts first and leaves a grout gap so the collage shows between cells. Choosing Grid as the mode keeps a grid-only plan unless a hybrid stack is selected.

## Editing a result

Tap the result, then regenerate that neighborhood, swap the piece under the finger, pin it, or remove it. A pin stays through a later regenerate. Undo keeps the last 16 plans, and Redo walks forward again. Swap, pin, and remove reuse the existing plan, including each shape mask and crop. Regenerate keeps pinned pieces and pieces outside the tap, and fills the tapped area with new shapes. The current plan, both stacks, the target URI, and the library URIs are written to app storage, so a process death restores them and redraws the preview when the pictures still match. A run killed before the first plan is saved has nothing to restore. Settings, including style and stack, stay in preferences. A plan written by this version stores the crop and the mask. An older plan still opens and renders as stamps.

## Phone Studio

Grid and Collage are the first control. Style, stack, and quality sit above the library. Generate is a 52 dp bar at the bottom of a portrait phone, and Cancel replaces it while a run is in progress. The screen stays on for that run. Progress shows the stage. Save and Share sit with the title. Advanced controls stay collapsed. Landscape wider than 700 dp splits the target and the controls into two columns. Tap targets for the primary chips are at least 48 dp. The layout mock below is drawn from that hierarchy. It is not a photograph of a device.

![Phone Studio layout mock](docs/images/studio-phone-mock.png)

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

JVM tests cover OKLab conversion, histograms, cropping, descriptors, candidate indexing, repetition, usage balance, grid planning, non-square cells, cutout masks, shaped collage placement, cancellation, atomic writes, and golden images. The grid golden digest is SHA-256 `0745027b47ef5ffb4aad0ea56cad0d5fa123d41795e750931914bd37af2194ba`. The cutout-collage golden digest is SHA-256 `a1c941d3b17cbfdc28f6c1e7c8ad36f1ec8e7f2e70aa8efd1638c40fd8536850`. The collage regression, with color correction at 0.72, requires at least 96% of the preview painted, masked OKLab ΔE under 0.055, masked luminance SSIM above 0.55, edge ΔE under 0.09, distance ΔE under 0.048, and distance SSIM above 0.75. Distance readability area-averages the collage and the target to 64 pixels wide, then measures mean OKLab ΔE and luminance SSIM, so texture inside a piece does not hide a missed color mass. A two-tone picture must keep the top half on the light sources and the bottom half on the dark sources, with edge alignment above 1.4.

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
