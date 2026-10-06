# Engine benchmarks

JVM run with synthetic tiles (unique hues, 16 px thumbnails, 12 px render cells).
One unmeasured 100-tile pass runs first so the numbers below are not dominated by JIT warmup.
Heap is the change in `totalMemory - freeMemory` around the case and is only an estimate.

| Tiles | Grid | Load ms | Analyze ms | Index ms | Match ms | Render ms | Total ms | Probes | Full-library comparisons | Heap MB |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 40×40 | 1.1 | 9.6 | 0.3 | 75.0 | 60.8 | 146.9 | 84182 | 160000 | 0.6 |
| 500 | 60×60 | 2.4 | 11.2 | 0.5 | 38.0 | 101.3 | 153.3 | 233398 | 1800000 | 1.8 |
| 1000 | 80×80 | 4.1 | 23.2 | 0.9 | 67.3 | 199.8 | 295.3 | 416000 | 6400000 | 3.9 |
| 5000 | 120×120 | 13.8 | 110.8 | 2.8 | 154.6 | 427.0 | 709.0 | 936000 | 72000000 | 13.0 |

Probes are candidate color checks inside OKLab bins, capped per cell so a large library is not scanned in full.
The full-library column is cells × tiles, which is what the 1.x matcher did.
