# Engine benchmarks

JVM run with synthetic tiles (unique hues, 16 px thumbnails, 12 px render cells).
One unmeasured 100-tile pass runs first so the numbers below are not dominated by JIT warmup.
Heap is the change in `totalMemory - freeMemory` around the case and is only an estimate.

| Tiles | Grid | Load ms | Analyze ms | Index ms | Match ms | Render ms | Total ms | Probes | Full-library comparisons | Heap MB |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 40×40 | 1.4 | 15.7 | 0.3 | 86.1 | 46.2 | 149.8 | 84135 | 160000 | 0.6 |
| 500 | 60×60 | 2.2 | 13.1 | 0.4 | 37.2 | 109.2 | 162.1 | 233676 | 1800000 | 1.9 |
| 1000 | 80×80 | 4.1 | 22.4 | 0.7 | 64.4 | 196.4 | 287.9 | 415996 | 6400000 | 4.1 |
| 5000 | 120×120 | 12.8 | 114.3 | 2.7 | 141.9 | 436.0 | 707.8 | 936000 | 72000000 | 13.6 |

Probes are candidate color checks inside OKLab bins, capped per cell so a large library is not scanned in full.
The full-library column is cells × tiles, which is what the 1.x matcher did.

## Non-square cells with orientation matching

500 tiles (250 landscape, 250 portrait), 16:9 cells, rotation mode Match orientation. Same candidate cap as the table above.

| Tiles | Grid | Analyze ms | Match ms | Render ms | Total ms | Probes | Full scan |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 500 | 40×40 | 24.8 | 67.1 | 84.7 | 182.3 | 103890 | 800000 |

## Cutout collage

Each placement queries the OKLab index once. Large pieces land first. A finer pass adds small pieces
on edges, including over a pixel that is already covered when that lowers the error.
Shape agreement re-ranks that short list. It does not scan the library again.
Full scan is requested pieces × cutouts × 12 angles.
The 900-piece row uses the same scale range as the rows above, so the extra time is the larger budget.

| Cutouts | Requested | Placed | Analyze ms | Index ms | Match ms | Render ms | Total ms | Probes | Full scan | Note |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 200 | 160 | 160 | 4.1 | 0.2 | 72.7 | 2.0 | 79.0 | 5760 | 384000 | coverage 43% |
| 600 | 280 | 280 | 15.2 | 0.3 | 92.0 | 2.6 | 110.2 | 12951 | 2016000 | coverage 68% |
| 400 | 900 | 900 | 8.3 | 0.2 | 236.6 | 5.7 | 250.9 | 79996 | 4320000 | coverage 100% |

## Hybrid stack

Grid under collage on a 160×100 gradient, 200 cutouts, 160 pieces, 20×12 grid, custom 160×100 output.
Time includes grid matching, collage placement, and the stacked render.

| Cutouts | Requested | Placed | Total ms | Probes | Note |
| ---: | ---: | ---: | ---: | ---: | --- |
| 200 | 160 | 160 | 74.2 | 15562 | grid under collage |

## Matching quality

Portrait scene, 120 photo-like tiles, 32×42 grid, seed 7.
Both sides use the original tile pixels and the same renderer. Left is average-RGB selection. Right is the default OKLab matcher.

| Matcher | Mean OKLab ΔE | Luminance SSIM |
| --- | ---: | ---: |
| Average RGB | 0.0453 | 0.5506 |
| OKLab default | 0.0436 | 0.6559 |

Sample collage on the portrait scene, 240 cutouts, 320 requested, seed 4, mean-color background.
Organic run placed 320 pieces. Photo-texture run placed 320 pieces.
docs/images/cutout-collage.png is the target, the previous collage, this collage, and a photo-texture collage.
The photo textures are generated in this repository. They are not third-party photographs.
The previous collage is the committed output from the residual placer before the detail pass.
Its edge score uses pixels that differ from the target mean color, because that file has no coverage mask.
Output is locked to 280×180 so this row lines up with the previous residual file.
Masked scores count only pixels a cutout painted. Edge ΔE is the top quarter of reference luminance gradients.

| | Whole ΔE | Whole SSIM | Masked ΔE | Masked SSIM | Edge ΔE | Pixels painted |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Underlayer sample | 0.0475 | 0.7655 | 0.0989 | 0.6873 | — | 59% |
| Previous residual | 0.0334 | 0.6746 | 0.0477 | 0.6779 | 0.0563 | 96% |
| This run | 0.0338 | 0.6790 | 0.0467 | 0.6804 | 0.0555 | 93% |
| Photo textures | 0.0338 | 0.6769 | 0.0506 | 0.6781 | 0.0520 | 94% |

Mean absolute RGB error in face windows on the 280×180 canvas, previous residual versus this run.
- Left eye: 37.1 → 31.5
- Right eye: 45.1 → 28.5
- Mouth: 18.8 → 19.1
- Cheek: 16.2 → 15.8


## Shape, density, and hybrid

Same portrait and 240 organic cutouts, seed 4, correction off, mean-color background.
Color-only sets shape weight to 0. Shape-aware sets it to 0.9. Both stay at 280×180 and 320 pieces.
docs/images/cutout-shape-compare.png is the target, color-only, then shape-aware.
Dense coverage asks for 1,200 pieces at the High Quality scale range (2–11%) on a 560×360 canvas.
docs/images/cutout-hybrid.png is cutouts only, grid under collage, then collage under grid, all 280×180.
docs/images/studio-phone-mock.png is a labeled layout mock of the phone Studio. It is not a device screenshot.

| | Whole ΔE | Whole SSIM | Masked ΔE | Masked SSIM | Edge ΔE | Painted | Generate ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Color only | 0.0330 | 0.6795 | 0.0469 | 0.6811 | 0.0557 | 93% | 98 |
| Shape-aware | 0.0345 | 0.6816 | 0.0475 | 0.6829 | 0.0564 | 93% | 115 |
| Dense 1200 | 0.0295 | 0.7368 | 0.0464 | 0.7415 | 0.0464 | 97% | 301 |
| Grid under collage | 0.0298 | 0.7063 | 0.0455 | 0.7121 | 0.0547 | 93% | 125 |
| Collage under grid | 0.0214 | 0.3666 | 0.0321 | 0.3371 | 0.0342 | 15% | 118 |

