# Engine benchmarks

JVM run with synthetic tiles (unique hues, 16 px thumbnails, 12 px render cells).
One unmeasured 100-tile pass runs first so the numbers below are not dominated by JIT warmup.
Heap is the change in `totalMemory - freeMemory` around the case and is only an estimate.

| Tiles | Grid | Load ms | Analyze ms | Index ms | Match ms | Render ms | Total ms | Probes | Full-library comparisons | Heap MB |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 40×40 | 1.1 | 10.2 | 0.3 | 61.9 | 45.1 | 118.5 | 84135 | 160000 | 0.6 |
| 500 | 60×60 | 2.5 | 12.7 | 0.4 | 37.7 | 109.0 | 162.3 | 233676 | 1800000 | 1.9 |
| 1000 | 80×80 | 4.1 | 22.4 | 0.6 | 65.6 | 192.9 | 285.6 | 415996 | 6400000 | 4.0 |
| 5000 | 120×120 | 12.5 | 112.3 | 2.4 | 136.0 | 434.9 | 698.1 | 936000 | 72000000 | 13.6 |

Probes are candidate color checks inside OKLab bins, capped per cell so a large library is not scanned in full.
The full-library column is cells × tiles, which is what the 1.x matcher did.

## Non-square cells with orientation matching

500 tiles (250 landscape, 250 portrait), 16:9 cells, rotation mode Match orientation. Same candidate cap as the table above.

| Tiles | Grid | Analyze ms | Match ms | Render ms | Total ms | Probes | Full scan |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 500 | 40×40 | 23.5 | 64.9 | 85.7 | 179.7 | 103890 | 800000 |

## Cutout collage

Each placement queries the OKLab index once, then keeps a candidate only when its masked color lowers error
without spoiling pixels that are already close. Full scan is requested pieces × cutouts × 12 angles.

| Cutouts | Requested | Placed | Analyze ms | Index ms | Match ms | Render ms | Total ms | Probes | Full scan | Note |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 200 | 160 | 160 | 3.3 | 0.2 | 19.7 | 2.0 | 25.2 | 6079 | 384000 | coverage 47% |
| 600 | 280 | 280 | 9.7 | 0.3 | 26.4 | 2.5 | 38.9 | 13596 | 2016000 | coverage 74% |

## Matching quality

Portrait scene, 120 photo-like tiles, 32×42 grid, seed 7.
Both sides use the original tile pixels and the same renderer. Left is average-RGB selection. Right is the default OKLab matcher.

| Matcher | Mean OKLab ΔE | Luminance SSIM |
| --- | ---: | ---: |
| Average RGB | 0.0453 | 0.5506 |
| OKLab default | 0.0436 | 0.6559 |

Sample collage on the portrait scene, 240 organic cutouts, 320 requested, seed 4.
315 pieces were placed. The background is the target's mean color.
docs/images/cutout-collage.png shows the target, the collage, and a grid mosaic of the same library.
The previous sample painted 59% of pixels (masked ΔE 0.0989, masked SSIM 0.6873).
That run used the target photo as the underlayer and stopped when a coarse grid filled.
Masked scores count only pixels a cutout painted. Whole-image scores include the flat background.

| | Whole ΔE | Whole SSIM | Masked ΔE | Masked SSIM | Pixels painted |
| --- | ---: | ---: | ---: | ---: | ---: |
| Previous | 0.0475 | 0.7655 | 0.0989 | 0.6873 | 59% |
| This run | 0.0334 | 0.6746 | 0.0477 | 0.6779 | 96% |

