# Engine benchmarks

JVM run with synthetic tiles (unique hues, 16 px thumbnails, 12 px render cells).
One unmeasured 100-tile pass runs first so the numbers below are not dominated by JIT warmup.
Heap is the change in `totalMemory - freeMemory` around the case and is only an estimate.

| Tiles | Grid | Load ms | Analyze ms | Index ms | Match ms | Render ms | Total ms | Probes | Full-library comparisons | Heap MB |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 40×40 | 1.0 | 9.6 | 0.3 | 60.3 | 53.3 | 124.4 | 84135 | 160000 | 0.6 |
| 500 | 60×60 | 2.1 | 10.9 | 0.4 | 36.7 | 108.4 | 158.5 | 233676 | 1800000 | 1.8 |
| 1000 | 80×80 | 3.8 | 21.4 | 0.6 | 66.3 | 192.0 | 284.2 | 415996 | 6400000 | 3.9 |
| 5000 | 120×120 | 14.4 | 109.6 | 2.5 | 142.4 | 433.1 | 702.0 | 936000 | 72000000 | 13.0 |

Probes are candidate color checks inside OKLab bins, capped per cell so a large library is not scanned in full.
The full-library column is cells × tiles, which is what the 1.x matcher did.

## Non-square cells with orientation matching

500 tiles (250 landscape, 250 portrait), 16:9 cells, rotation mode Match orientation. Same candidate cap as the table above.

| Tiles | Grid | Analyze ms | Match ms | Render ms | Total ms | Probes | Full scan |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 500 | 40×40 | 23.7 | 44.9 | 83.6 | 158.0 | 103890 | 800000 |

## Matching quality

Portrait scene, 120 photo-like tiles, 32×42 grid, seed 7.
Both sides use the original tile pixels and the same renderer. Left is average-RGB selection. Right is the default OKLab matcher.

| Matcher | Mean OKLab ΔE | Luminance SSIM |
| --- | ---: | ---: |
| Average RGB | 0.0453 | 0.5506 |
| OKLab default | 0.0436 | 0.6559 |
