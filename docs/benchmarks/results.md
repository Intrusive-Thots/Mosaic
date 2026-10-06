# Engine benchmarks

JVM run with synthetic tiles (unique hues, 16 px thumbnails, 12 px render cells).
One unmeasured 100-tile pass runs first so the numbers below are not dominated by JIT warmup.
Heap is the change in `totalMemory - freeMemory` around the case and is only an estimate.

| Tiles | Grid | Load ms | Analyze ms | Index ms | Match ms | Render ms | Total ms | Probes | Full-library comparisons | Heap MB |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 40×40 | 1.1 | 10.0 | 0.3 | 62.4 | 53.3 | 127.1 | 84135 | 160000 | 0.6 |
| 500 | 60×60 | 2.2 | 12.5 | 0.5 | 38.5 | 108.6 | 162.3 | 233676 | 1800000 | 1.9 |
| 1000 | 80×80 | 4.0 | 22.2 | 0.6 | 66.0 | 192.4 | 285.2 | 415996 | 6400000 | 4.1 |
| 5000 | 120×120 | 13.1 | 113.0 | 2.2 | 140.8 | 432.4 | 701.5 | 936000 | 72000000 | 13.6 |

Probes are candidate color checks inside OKLab bins, capped per cell so a large library is not scanned in full.
The full-library column is cells × tiles, which is what the 1.x matcher did.

## Non-square cells with orientation matching

500 tiles (250 landscape, 250 portrait), 16:9 cells, rotation mode Match orientation. Same candidate cap as the table above.

| Tiles | Grid | Analyze ms | Match ms | Render ms | Total ms | Probes | Full scan |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 500 | 40×40 | 22.9 | 41.5 | 82.3 | 152.7 | 103890 | 800000 |

## Cutout collage

Each placement queries the OKLab index once. Large pieces land first. A finer pass adds small pieces
on edges, including over a pixel that is already covered when that lowers the error.
Full scan is requested pieces × cutouts × 12 angles.

| Cutouts | Requested | Placed | Analyze ms | Index ms | Match ms | Render ms | Total ms | Probes | Full scan | Note |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 200 | 160 | 160 | 3.2 | 0.2 | 54.2 | 2.0 | 59.5 | 5798 | 384000 | coverage 46% |
| 600 | 280 | 280 | 9.3 | 0.3 | 49.2 | 2.6 | 61.5 | 13283 | 2016000 | coverage 72% |

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
Masked scores count only pixels a cutout painted. Edge ΔE is the top quarter of reference luminance gradients.

| | Whole ΔE | Whole SSIM | Masked ΔE | Masked SSIM | Edge ΔE | Pixels painted |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Underlayer sample | 0.0475 | 0.7655 | 0.0989 | 0.6873 | — | 59% |
| Previous residual | 0.0334 | 0.6746 | 0.0477 | 0.6779 | 0.0563 | 96% |
| This run | 0.0325 | 0.6803 | 0.0467 | 0.6813 | 0.0554 | 95% |
| Photo textures | 0.0354 | 0.6876 | 0.0508 | 0.6877 | 0.0516 | 95% |

