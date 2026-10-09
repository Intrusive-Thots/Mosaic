# Collage tuning log

Measurements use the showcase cutout settings (`currentCollage`: color grade 0.72, dense style, separate pieces) at a 560 px long edge and 640 requested pieces. Distance ΔE and SSIM are the 64 px read. Fidelity is piece-content SSIM, median and 10th percentile. Coverage is the union of piece masks on a 160 px grid.

Grid golden `0745027b47ef5ffb4aad0ea56cad0d5fa123d41795e750931914bd37af2194ba` stays put. Collage golden stays put while these edits only change graded rendering.

## Baseline

| Theme | ΔE | SSIM | fidelity | p10 | coverage |
| --- | ---: | ---: | ---: | ---: | ---: |
| portrait | 0.0533 | 0.4827 | 0.921 | 0.629 | 0.990 |
| naruto | 0.0854 | 0.7191 | 0.748 | 0.575 | 0.994 |
| rick | 0.0724 | 0.6596 | 0.639 | 0.399 | 0.988 |
| koth | 0.0984 | 0.4122 | 0.751 | 0.633 | 0.992 |
| pokemon | 0.0907 | 0.6162 | 0.712 | 0.477 | 0.990 |

## 1. Stronger grade on large pieces

Large pieces (`scale >= 0.055`) take 1.25× the color grade, capped at 1. Small pieces stay on the slider, so source detail is not graded any harder.

| Theme | ΔE | SSIM | fidelity | p10 | coverage |
| --- | ---: | ---: | ---: | ---: | ---: |
| portrait | 0.0518 | 0.4887 | 0.921 | 0.633 | 0.990 |
| naruto | 0.0833 | 0.7239 | 0.747 | 0.571 | 0.994 |
| rick | 0.0710 | 0.6634 | 0.639 | 0.398 | 0.988 |
| koth | 0.0941 | 0.4191 | 0.750 | 0.634 | 0.992 |
| pokemon | 0.0890 | 0.6164 | 0.710 | 0.478 | 0.990 |

Every theme’s 64 px read improved. Piece fidelity stayed within 0.004. Kept.

Tried and reverted in this session, before this keep: lighter paper rims (no 64 px change at this size), a coarser grade field, outline-only silhouettes, bilinear mask edges, honest grade matching, a wider candidate pool, a quieter torn contour, and smaller flat pieces. Each one moved Naruto’s ΔE or SSIM the wrong way.
