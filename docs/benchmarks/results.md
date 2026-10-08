# Engine benchmarks

JVM run with synthetic tiles (unique hues, 16 px thumbnails, 12 px render cells).
One unmeasured 100-tile pass runs first so the numbers below are not dominated by JIT warmup.
Heap is the change in `totalMemory - freeMemory` around the case and is only an estimate.

| Tiles | Grid | Load ms | Analyze ms | Index ms | Match ms | Render ms | Total ms | Probes | Full-library comparisons | Heap MB |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 40×40 | 1.0 | 4.6 | 0.2 | 48.8 | 48.9 | 103.5 | 84135 | 160000 | 0.6 |
| 500 | 60×60 | 2.2 | 47.2 | 0.4 | 52.8 | 108.9 | 211.5 | 233676 | 1800000 | 1.9 |
| 1000 | 80×80 | 4.1 | 22.4 | 0.6 | 65.5 | 194.9 | 287.5 | 415996 | 6400000 | 4.0 |
| 5000 | 120×120 | 13.1 | 115.2 | 2.7 | 141.5 | 439.7 | 712.2 | 936000 | 72000000 | 13.6 |

Probes are candidate color checks inside OKLab bins, capped per cell so a large library is not scanned in full.
The full-library column is cells × tiles, which is what the 1.x matcher did.

## Non-square cells with orientation matching

500 tiles (250 landscape, 250 portrait), 16:9 cells, rotation mode Match orientation. Same candidate cap as the table above.

| Tiles | Grid | Analyze ms | Match ms | Render ms | Total ms | Probes | Full scan |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 500 | 40×40 | 22.8 | 43.7 | 85.4 | 158.1 | 103890 | 800000 |

## Cutout collage

The target is cut into coarse regions, finer edge regions, and contour strokes.
Each shape is filled from the source crop, rotation, and scale that best match its color.
Later shapes overlap freely. The index is queried once per shape, then only that short list is scored.
Full scan is requested pieces × cutouts × 12 angles.
The 900-piece row uses the same scale range as the rows above, so the extra time is the larger budget.

| Cutouts | Requested | Placed | Analyze ms | Index ms | Match ms | Render ms | Total ms | Probes | Full scan | Note |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 200 | 160 | 44 | 4.2 | 0.2 | 266.9 | 28.5 | 299.8 | 4071 | 384000 | coverage 100% |
| 600 | 280 | 58 | 13.5 | 0.3 | 262.0 | 30.4 | 306.1 | 6737 | 2016000 | coverage 100% |
| 400 | 900 | 81 | 6.5 | 0.2 | 282.8 | 29.6 | 319.2 | 9576 | 4320000 | coverage 100% |

## Hybrid stack

Grid under collage on a 160×100 gradient, 200 cutouts, 160 pieces, 20×12 grid, custom 160×100 output.
Time includes grid matching, collage placement, and the stacked render.

| Cutouts | Requested | Placed | Total ms | Probes | Note |
| ---: | ---: | ---: | ---: | ---: | --- |
| 200 | 160 | 44 | 438.2 | 13868 | grid under collage |

## Matching quality

Portrait scene, 120 photo-like tiles, 32×42 grid, seed 7.
Both sides use the original tile pixels and the same renderer. Left is average-RGB selection. Right is the default OKLab matcher.

| Matcher | Mean OKLab ΔE | Luminance SSIM |
| --- | ---: | ---: |
| Average RGB | 0.0453 | 0.5506 |
| OKLab default | 0.0436 | 0.6559 |

Sample collage on the portrait scene, 240 cutouts, 320 requested, seed 4, mean-color background.
Organic run placed 60 pieces. Photo-texture run placed 56 pieces.
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
| Shaped collage | 0.0288 | 0.9153 | 0.0353 | 0.9153 | 0.0522 | 100% | 30.1340 | 0.0331 | 0.9294 |
| Photo textures | 0.0298 | 0.9104 | 0.0373 | 0.9104 | 0.0464 | 100% | 29.1004 | 0.0353 | 0.9250 |

Mean absolute RGB error in face windows on the 280×180 canvas, previous residual versus this run.
- Left eye: 37.1 → 22.8
- Right eye: 45.1 → 20.9
- Mouth: 18.8 → 12.1
- Cheek: 16.2 → 7.7


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
| Color only | 0.0288 | 0.9153 | 0.0353 | 0.9153 | 0.0522 | 100% | 30.1340 | 0.0331 | 0.9294 | 920 |
| Shape-aware | 0.0288 | 0.9153 | 0.0353 | 0.9153 | 0.0522 | 100% | 30.1340 | 0.0331 | 0.9294 | 920 |
| Dense 1200 | 0.0296 | 0.9212 | 0.0358 | 0.9212 | 0.0358 | 100% | 31.5644 | 0.0338 | 0.9318 | 1032 |
| Grid under collage | 0.0285 | 0.9210 | 0.0350 | 0.9210 | 0.0518 | 100% | 30.1340 | 0.0328 | 0.9321 | 907 |
| Collage under grid | 0.0209 | 0.3829 | 0.0321 | 0.3476 | 0.0349 | 16% | 30.1340 | 0.0335 | 0.8054 | 911 |

