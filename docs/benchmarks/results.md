# Engine benchmarks

JVM run with synthetic tiles (unique hues, 16 px thumbnails, 12 px render cells).
One unmeasured 100-tile pass runs first so the numbers below are not dominated by JIT warmup.
Heap is the change in `totalMemory - freeMemory` around the case and is only an estimate.

| Tiles | Grid | Load ms | Analyze ms | Index ms | Match ms | Render ms | Total ms | Probes | Full-library comparisons | Heap MB |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 40×40 | 1.2 | 9.7 | 0.3 | 63.7 | 54.1 | 129.0 | 84135 | 160000 | 0.6 |
| 500 | 60×60 | 2.2 | 12.9 | 0.7 | 40.5 | 100.6 | 157.0 | 233676 | 1800000 | 1.8 |
| 1000 | 80×80 | 4.1 | 22.7 | 0.7 | 62.6 | 179.7 | 269.7 | 415996 | 6400000 | 3.9 |
| 5000 | 120×120 | 15.1 | 112.5 | 4.1 | 131.1 | 406.4 | 669.1 | 936000 | 72000000 | 13.1 |

Probes are candidate color checks inside OKLab bins, capped per cell so a large library is not scanned in full.
The full-library column is cells × tiles, which is what the 1.x matcher did.

## Matching quality

Portrait scene, 120 photo-like tiles, 32×42 grid, seed 7.
Both sides use the original tile pixels and the same renderer. Left is average-RGB selection. Right is the default OKLab matcher.

| Matcher | Mean OKLab ΔE | Luminance SSIM |
| --- | ---: | ---: |
| Average RGB | 0.0453 | 0.5506 |
| OKLab default | 0.0436 | 0.6559 |
