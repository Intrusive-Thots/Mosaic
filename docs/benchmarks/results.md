# Engine benchmarks

JVM run with synthetic tiles (unique hues, 16 px thumbnails, 12 px render cells).
One unmeasured 100-tile pass runs first so the numbers below are not dominated by JIT warmup.
Heap is the change in `totalMemory - freeMemory` around the case and is only an estimate.

| Tiles | Grid | Load ms | Analyze ms | Index ms | Match ms | Render ms | Total ms | Probes | Full-library comparisons | Heap MB |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 40×40 | 1.0 | 4.6 | 0.3 | 52.0 | 50.2 | 108.1 | 84135 | 160000 | 0.6 |
| 500 | 60×60 | 2.1 | 13.1 | 0.4 | 38.1 | 111.1 | 164.8 | 233676 | 1800000 | 1.9 |
| 1000 | 80×80 | 4.3 | 23.3 | 0.8 | 68.1 | 196.4 | 292.9 | 415996 | 6400000 | 4.0 |
| 5000 | 120×120 | 14.0 | 118.5 | 3.9 | 152.4 | 447.1 | 735.9 | 936000 | 72000000 | 13.5 |

Probes are candidate color checks inside OKLab bins, capped per cell so a large library is not scanned in full.
The full-library column is cells × tiles, which is what the 1.x matcher did.

## Non-square cells with orientation matching

500 tiles (250 landscape, 250 portrait), 16:9 cells, rotation mode Match orientation. Same candidate cap as the table above.

| Tiles | Grid | Analyze ms | Match ms | Render ms | Total ms | Probes | Full scan |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 500 | 40×40 | 24.5 | 40.5 | 88.0 | 158.9 | 103890 | 800000 |

## Cutout collage

Each placement queries the OKLab index once. Large pieces land first. A finer pass adds small pieces
on edges, including over a pixel that is already covered when that lowers the error.
Shape agreement re-ranks that short list. It does not scan the library again.
Full scan is requested pieces × cutouts × 12 angles.
The 900-piece row uses the same scale range as the rows above, so the extra time is the larger budget.

| Cutouts | Requested | Placed | Analyze ms | Index ms | Match ms | Render ms | Total ms | Probes | Full scan | Note |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 200 | 160 | 160 | 4.5 | 0.2 | 89.8 | 5.2 | 99.8 | 5466 | 384000 | coverage 45% |
| 600 | 280 | 280 | 15.1 | 0.4 | 91.7 | 5.2 | 112.4 | 12355 | 2016000 | coverage 71% |
| 400 | 900 | 900 | 6.7 | 0.3 | 246.7 | 10.1 | 263.8 | 67102 | 4320000 | coverage 100% |

## Hybrid stack

Grid under collage on a 160×100 gradient, 200 cutouts, 160 pieces, 20×12 grid, custom 160×100 output.
Time includes grid matching, collage placement, and the stacked render.

| Cutouts | Requested | Placed | Total ms | Probes | Note |
| ---: | ---: | ---: | ---: | ---: | --- |
| 200 | 160 | 160 | 82.2 | 15268 | grid under collage |

## Matching quality

Portrait scene, 120 photo-like tiles, 32×42 grid, seed 7.
Both sides use the original tile pixels and the same renderer. Left is average-RGB selection. Right is the default OKLab matcher.

| Matcher | Mean OKLab ΔE | Luminance SSIM |
| --- | ---: | ---: |
| Average RGB | 0.0453 | 0.5506 |
| OKLab default | 0.0436 | 0.6559 |

Sample collage on the portrait scene, 240 cutouts, 320 requested, seed 4, mean-color background.
Organic run placed 320 pieces. Photo-texture run placed 320 pieces.
engine/build/reports/benchmarks/images/cutout-collage.png is the target, the previous collage, this collage, and a photo-texture collage.
The photo textures are generated in this repository. They are not third-party photographs.
The previous collage is the committed output from the residual placer before the detail pass.
Its edge score uses pixels that differ from the target mean color, because that file has no coverage mask.
Output is locked to 280×180 so this row lines up with the previous residual file.
Masked scores count only pixels a cutout painted. Edge ΔE is the top quarter of reference luminance gradients.

| | Whole ΔE | Whole SSIM | Masked ΔE | Masked SSIM | Edge ΔE | Pixels painted |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Underlayer sample | 0.0475 | 0.7655 | 0.0989 | 0.6873 | — | 59% |
| Previous residual | 0.0334 | 0.6746 | 0.0477 | 0.6779 | 0.0563 | 96% |
| This run | 0.0323 | 0.7045 | 0.0447 | 0.7061 | 0.0527 | 94% |
| Photo textures | 0.0314 | 0.6924 | 0.0476 | 0.6940 | 0.0480 | 95% |

Mean absolute RGB error in face windows on the 280×180 canvas, previous residual versus this run.
- Left eye: 37.1 → 26.7
- Right eye: 45.1 → 22.2
- Mouth: 18.8 → 16.3
- Cheek: 16.2 → 14.5


## Shape, density, and hybrid

Same portrait and 240 organic cutouts, seed 4, correction off, mean-color background.
Color-only sets shape weight to 0. Shape-aware sets it to 0.9. Both stay at 280×180 and 320 pieces.
engine/build/reports/benchmarks/images/cutout-shape-compare.png is the target, color-only, then shape-aware.
Dense coverage asks for 1,200 pieces at the High Quality scale range (2–11%) on a 560×360 canvas.
engine/build/reports/benchmarks/images/cutout-hybrid.png is cutouts only, grid under collage, then collage under grid, all 280×180.
docs/images/studio-phone-mock.png is a labeled layout mock of the phone Studio. It is not a device screenshot.

| | Whole ΔE | Whole SSIM | Masked ΔE | Masked SSIM | Edge ΔE | Painted | Generate ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Color only | 0.0319 | 0.7017 | 0.0450 | 0.7028 | 0.0533 | 94% | 120 |
| Shape-aware | 0.0332 | 0.7078 | 0.0452 | 0.7094 | 0.0533 | 94% | 146 |
| Dense 1200 | 0.0270 | 0.7560 | 0.0424 | 0.7569 | 0.0424 | 98% | 440 |
| Grid under collage | 0.0298 | 0.6970 | 0.0481 | 0.7014 | 0.0577 | 94% | 134 |
| Collage under grid | 0.0213 | 0.3657 | 0.0321 | 0.3373 | 0.0342 | 15% | 137 |

