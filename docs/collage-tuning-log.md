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

## 2. Full grade on large pieces

1.25× of a 0.72 grade is 0.90. 1.4× reaches the cap of 1, so a large piece is graded as far as the slider allows. Small pieces are unchanged.

| Theme | ΔE | SSIM | fidelity | p10 | coverage |
| --- | ---: | ---: | ---: | ---: | ---: |
| portrait | 0.0512 | 0.4912 | 0.921 | 0.638 | 0.990 |
| naruto | 0.0826 | 0.7252 | 0.747 | 0.571 | 0.994 |
| rick | 0.0706 | 0.6645 | 0.639 | 0.398 | 0.988 |
| koth | 0.0929 | 0.4205 | 0.747 | 0.631 | 0.992 |
| pokemon | 0.0885 | 0.6158 | 0.710 | 0.478 | 0.990 |

ΔE improved on every theme versus step 1. SSIM improved on four themes and moved 0.0006 on Pokemon. Fidelity stayed within 0.003. Kept.

## 3. Lighter edges

Paper rims, used when Separate pieces is on, drop from alpha 150–200 and up to 3 px to alpha 84–108 and at most 2 px. The shadow is half as strong. The showcase collage also stops forcing Separate pieces, which matches the Dense style and the app default. Renders got faster because the rim is skipped in that mode.

| Theme | ΔE | SSIM | fidelity | p10 | coverage |
| --- | ---: | ---: | ---: | ---: | ---: |
| portrait | 0.0514 | 0.4891 | 0.929 | 0.659 | 0.990 |
| naruto | 0.0825 | 0.7253 | 0.749 | 0.571 | 0.994 |
| rick | 0.0704 | 0.6656 | 0.644 | 0.398 | 0.988 |
| koth | 0.0928 | 0.4201 | 0.753 | 0.637 | 0.992 |
| pokemon | 0.0882 | 0.6166 | 0.715 | 0.487 | 0.990 |

Versus step 2, piece fidelity rose on portrait, Rick, King of the Hill, and Pokemon. The 64 px read stayed within 0.002. Kept.

Also measured and reverted: sparing faces from the extra grade, grading medium pieces harder, easing the grade on small pieces, and raising the Balanced preset from 480 to 640 pieces. Medium and full small-piece changes traded fidelity for ΔE. 640 pieces at the Balanced grade of 0.65 helped Naruto and hurt King of the Hill and Pokemon.

Tried and reverted in this session, before this keep: lighter paper rims (no 64 px change at this size), a coarser grade field, outline-only silhouettes, bilinear mask edges, honest grade matching, a wider candidate pool, a quieter torn contour, and smaller flat pieces. Each one moved Naruto’s ΔE or SSIM the wrong way.

## 4. Crossover libraries, transparent targets on white

Each demo target is rebuilt from another franchise: Team 7 from Rick and Morty, the Smiths from TMNT, the turtles from Naruto, Hank and the guys from Pokemon, and Ash and Pikachu from King of the Hill. King of the Hill and Pokemon were already crossed, so their numbers match step 3. Naruto and Rick moved because the piece library changed.

The TMNT group render is a transparent PNG. Read as-is, the empty pixels are black, and the collage was matching a black field.

| TMNT target | ΔE | SSIM | fidelity | p10 | coverage |
| --- | ---: | ---: | ---: | ---: | ---: |
| Transparent, read as black | 0.2287 | 0.3488 | 0.867 | 0.606 | 0.990 |
| Composited on white | 0.1309 | 0.4616 | 0.765 | 0.601 | 0.985 |

ΔE fell by 0.098 and SSIM rose by 0.113. The turtles read on white. Opaque targets are unchanged, because a picture with almost no clear pixels is returned as itself. Kept.

Crossover baseline after that composite, same grade and rims as step 3:

| Theme | Library | ΔE | SSIM | fidelity | p10 | coverage |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| portrait | synthetic | 0.0514 | 0.4891 | 0.929 | 0.659 | 0.990 |
| naruto | Rick and Morty | 0.0929 | 0.6968 | 0.724 | 0.537 | 0.989 |
| rick | TMNT | 0.0936 | 0.6047 | 0.707 | 0.512 | 0.991 |
| tmnt | Naruto | 0.1309 | 0.4616 | 0.765 | 0.601 | 0.985 |
| koth | Pokemon | 0.0928 | 0.4201 | 0.753 | 0.637 | 0.992 |
| pokemon | King of the Hill | 0.0882 | 0.6166 | 0.715 | 0.487 | 0.990 |
