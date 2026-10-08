# Engine benchmarks

JVM run with synthetic tiles (unique hues, 16 px thumbnails, 12 px render cells).
One unmeasured 100-tile pass runs first so the numbers below are not dominated by JIT warmup.
Heap is the change in `totalMemory - freeMemory` around the case and is only an estimate.

| Tiles | Grid | Load ms | Analyze ms | Index ms | Match ms | Render ms | Total ms | Probes | Full-library comparisons | Heap MB |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 40×40 | 1.1 | 4.5 | 0.2 | 49.3 | 49.4 | 104.5 | 84135 | 160000 | 0.6 |
| 500 | 60×60 | 2.3 | 51.1 | 0.5 | 46.6 | 111.5 | 212.0 | 233676 | 1800000 | 1.9 |
| 1000 | 80×80 | 4.4 | 22.5 | 0.6 | 68.4 | 195.5 | 291.5 | 415996 | 6400000 | 4.1 |
| 5000 | 120×120 | 13.4 | 119.6 | 3.0 | 145.7 | 439.7 | 721.5 | 936000 | 72000000 | 13.5 |

Probes are candidate color checks inside OKLab bins, capped per cell so a large library is not scanned in full.
The full-library column is cells × tiles, which is what the 1.x matcher did.

## Non-square cells with orientation matching

500 tiles (250 landscape, 250 portrait), 16:9 cells, rotation mode Match orientation. Same candidate cap as the table above.

| Tiles | Grid | Analyze ms | Match ms | Render ms | Total ms | Probes | Full scan |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 500 | 40×40 | 26.1 | 79.8 | 87.4 | 199.4 | 103890 | 800000 |

## Cutout collage

The target is cut into coarse regions, finer edge regions, and contour strokes.
Each shape is filled from the source crop, rotation, and scale that best match its color.
Later shapes overlap freely. The index is queried once per shape, then only that short list is scored.
Full scan is requested pieces × cutouts × 12 angles.
The 900-piece row uses the same scale range as the rows above, so the extra time is the larger budget.

| Cutouts | Requested | Placed | Analyze ms | Index ms | Match ms | Render ms | Total ms | Probes | Full scan | Note |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 200 | 160 | 44 | 4.2 | 0.2 | 63.3 | 6.9 | 74.7 | 1662 | 384000 | coverage 100% |
| 600 | 280 | 51 | 14.5 | 0.3 | 36.1 | 5.5 | 56.3 | 2208 | 2016000 | coverage 100% |
| 400 | 900 | 83 | 9.7 | 0.2 | 31.8 | 5.4 | 47.1 | 3365 | 4320000 | coverage 100% |

## Hybrid stack

Grid under collage on a 160×100 gradient, 200 cutouts, 160 pieces, 20×12 grid, custom 160×100 output.
Time includes grid matching, collage placement, and the stacked render.

| Cutouts | Requested | Placed | Total ms | Probes | Note |
| ---: | ---: | ---: | ---: | ---: | --- |
| 200 | 160 | 44 | 51.7 | 11464 | grid under collage |

## Matching quality

Portrait scene, 120 photo-like tiles, 32×42 grid, seed 7.
Both sides use the original tile pixels and the same renderer. Left is average-RGB selection. Right is the default OKLab matcher.

| Matcher | Mean OKLab ΔE | Luminance SSIM |
| --- | ---: | ---: |
| Average RGB | 0.0453 | 0.5506 |
| OKLab default | 0.0436 | 0.6559 |

Sample collage on the portrait scene, 240 cutouts, 320 requested, seed 4, mean-color background.
Organic run placed 61 pieces. Photo-texture run placed 61 pieces.
engine/build/reports/benchmarks/images/cutout-collage.png is the target, the previous collage, this collage, and a photo-texture collage.
The photo textures are generated in this repository. They are not third-party photographs.
The previous collage is the committed output from the residual placer before the detail pass.
Its edge score uses pixels that differ from the target mean color, because that file has no coverage mask.
Output is locked to 280×180 so this row lines up with the previous residual file.
Masked scores count only pixels a cutout painted. Edge ΔE is the top quarter of reference luminance gradients.
Edge alignment is mean target-edge strength within two pixels of a piece boundary,
divided by the picture average. Above 1 means cuts follow edges.
The stamp row is the previous placer on this same portrait. Its alignment used the edge pixel itself.
Distance ΔE and distance SSIM compare both images after area-averaging to 64 pixels wide.
The previous shaped row is the run before flat crops and tone transfer. Its distance was not stored.

| | Whole ΔE | Whole SSIM | Masked ΔE | Masked SSIM | Edge ΔE | Painted | Alignment | Distance ΔE | Distance SSIM |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Underlayer sample | 0.0475 | 0.7655 | 0.0989 | 0.6873 | — | 59% | — | — | — |
| Previous residual | 0.0334 | 0.6746 | 0.0477 | 0.6779 | 0.0563 | 96% | — | 0.0444 | 0.8026 |
| Stamp collage | 0.0323 | 0.7045 | 0.0447 | 0.7061 | 0.0527 | 94% | 3.211 | — | — |
| Previous shaped | 0.0341 | 0.7417 | 0.0488 | 0.7417 | 0.0556 | 100% | 10.4693 | — | — |
| Shaped collage | 0.0359 | 0.8605 | 0.0425 | 0.8605 | 0.0588 | 100% | 32.5000 | 0.0410 | 0.8541 |
| Photo textures | 0.0368 | 0.8788 | 0.0429 | 0.8788 | 0.0521 | 100% | 32.5000 | 0.0416 | 0.8912 |

Mean absolute RGB error in face windows on the 280×180 canvas, previous residual versus this run.
- Left eye: 37.1 → 40.2
- Right eye: 45.1 → 39.4
- Mouth: 18.8 → 22.0
- Cheek: 16.2 → 9.2


## Shape, density, and hybrid

Same portrait and 240 organic cutouts, seed 4, correction off, mean-color background.
Color-only and shape-aware both cut the same target shapes at 280×180 and 320 pieces.
Shape weight stays in saved sessions. The cut follows the target either way.
engine/build/reports/benchmarks/images/cutout-shape-compare.png is the target, color-only, then shape-aware.
Dense coverage asks for 1,200 pieces at the High Quality scale range (2–11%) on a 560×360 canvas.
engine/build/reports/benchmarks/images/cutout-hybrid.png is cutouts only, grid under collage, then collage under grid, all 280×180.
docs/images/studio-phone-mock.png is a labeled layout mock of the phone Studio. It is not a device screenshot.

| | Whole ΔE | Whole SSIM | Masked ΔE | Masked SSIM | Edge ΔE | Painted | Alignment | Distance ΔE | Distance SSIM | Generate ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Color only | 0.0359 | 0.8605 | 0.0425 | 0.8605 | 0.0588 | 100% | 32.5000 | 0.0410 | 0.8541 | 98 |
| Shape-aware | 0.0359 | 0.8605 | 0.0425 | 0.8605 | 0.0588 | 100% | 32.5000 | 0.0410 | 0.8541 | 89 |
| Dense 1200 | 0.0367 | 0.8869 | 0.0434 | 0.8869 | 0.0434 | 100% | 15.1046 | 0.0417 | 0.8493 | 162 |
| Grid under collage | 0.0359 | 0.8605 | 0.0425 | 0.8605 | 0.0588 | 100% | 32.5000 | 0.0410 | 0.8541 | 90 |
| Collage under grid | 0.0213 | 0.3784 | 0.0319 | 0.3503 | 0.0355 | 16% | 32.5000 | 0.0342 | 0.8021 | 89 |

