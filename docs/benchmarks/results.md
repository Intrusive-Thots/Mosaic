# Engine benchmarks

JVM run with synthetic tiles (unique hues, 16 px thumbnails, 12 px render cells).
One unmeasured 100-tile pass runs first so the numbers below are not dominated by JIT warmup.
Heap is the change in `totalMemory - freeMemory` around the case and is only an estimate.

| Tiles | Grid | Load ms | Analyze ms | Index ms | Match ms | Render ms | Total ms | Probes | Full-library comparisons | Heap MB |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 40×40 | 1.3 | 5.4 | 0.3 | 55.4 | 48.7 | 111.0 | 84135 | 160000 | 0.6 |
| 500 | 60×60 | 2.9 | 12.7 | 0.6 | 38.3 | 108.8 | 163.2 | 233676 | 1800000 | 1.9 |
| 1000 | 80×80 | 4.1 | 23.9 | 0.8 | 72.5 | 192.7 | 293.9 | 415996 | 6400000 | 4.0 |
| 5000 | 120×120 | 12.5 | 113.0 | 2.3 | 163.5 | 433.9 | 725.2 | 936000 | 72000000 | 13.6 |

Probes are candidate color checks inside OKLab bins, capped per cell so a large library is not scanned in full.
The full-library column is cells × tiles, which is what the 1.x matcher did.

## Non-square cells with orientation matching

500 tiles (250 landscape, 250 portrait), 16:9 cells, rotation mode Match orientation. Same candidate cap as the table above.

| Tiles | Grid | Analyze ms | Match ms | Render ms | Total ms | Probes | Full scan |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 500 | 40×40 | 24.1 | 43.6 | 82.6 | 156.0 | 103890 | 800000 |

## Cutout collage

Each placement queries the OKLab index once, then scores that short list at a few angles. Full scan is requested pieces × cutouts × 12 angles.

| Cutouts | Requested | Placed | Analyze ms | Index ms | Match ms | Render ms | Total ms | Probes | Full scan | Note |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 200 | 60 | 46 | 1.7 | 0.2 | 11.1 | 2.9 | 15.9 | 2135 | 144000 | coverage 100% |
| 800 | 100 | 57 | 6.8 | 0.3 | 11.5 | 3.6 | 22.2 | 2736 | 960000 | coverage 100% |

## Matching quality

Portrait scene, 120 photo-like tiles, 32×42 grid, seed 7.
Both sides use the original tile pixels and the same renderer. Left is average-RGB selection. Right is the default OKLab matcher.

| Matcher | Mean OKLab ΔE | Luminance SSIM |
| --- | ---: | ---: |
| Average RGB | 0.0453 | 0.5506 |
| OKLab default | 0.0436 | 0.6559 |

Sample collage on the portrait scene, 64 cutouts, 72 placements, seed 4.
The target is the left half of docs/images/cutout-collage.png.
Whole-image scores include the target underlayer. Masked scores count only pixels a cutout painted.

| Whole ΔE | Whole SSIM | Masked ΔE | Masked SSIM | Pixels painted | Coarse coverage |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 0.0475 | 0.7655 | 0.0989 | 0.6873 | 59% | 100% |

