# Collage tuning log

Measurements use the showcase cutout settings (`currentCollage`: color grade 0.72, dense style, separate pieces off) at a 560 px long edge and 640 requested pieces. Distance ΔE and SSIM are the 64 px read. Fidelity is piece-content SSIM, median and 10th percentile. Coverage is the union of piece masks on a 160 px grid. Touch is the number of same-source pairs whose visible regions share an edge or a corner. Edge F1 compares 64 px Sobel maps and allows a one-pixel miss.

Grid golden `d1a220dd54d8b766667c236a44c91e01e3d53afd6c4a9393adb7e23d6fade175`. Collage golden `65776d15ebfb1f1de2bd1a1565f9b493e1c0794b12312749d279863429e12946`. Shaped-grid golden `4e9a75d488fd2c1b26231d1b3fed192cd7c380b5ea491423816771807d1e969b`. Those three moved in step 5, with the spacing rule, edge subdivision, and outline ink.

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

## 5. Copies must not touch, and outlines stay inked

A source may be reused, and a copy may be flipped, rotated, or resized. Two copies must not touch, including at a corner. On a grid the ban is Chebyshev distance at least 1, and a multi-cell span counts as one copy. On a collage the visible masks are rasterized to the same 96-grid used at placement time. A candidate that would touch is skipped. The next legal source is kept when its score stays within 0.12 of the best offer. A much worse legal source is not placed.

Dropping a buried piece, or culling a face that is no longer visible, can uncover two copies that then share an edge. The finished stack is walked once more. A touching copy is shrunk back to its original cut when that cut is legal, otherwise it is replaced, otherwise it is dropped. Pieces kept outside a regenerated region stay on that grid, so a new piece cannot land against a copy just outside the hole. Growing a mask to mend a seam is rejected when the growth would touch another copy.

Uniform grids double along the target's outlines. A coarse block splits when its luma range, or the jump to a neighbor, is above 0.11. A flat block stays one 2×2 cell. Mixed layout and staggered bricks stay on the requested grid. Output cell pixels are halved when the grid doubles. `preserveTargetEdges` darkens along the target line art, at most 0.34 in lightness above a floor of 56, and leaves chroma alone. Gradient-orientation matching and edge-snapped cut contours are not in this step.

| Theme | ΔE | SSIM | fidelity | p10 | coverage | reuse | max | touch | edge F1 | chamfer |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| portrait | 0.0556 | 0.4598 | 0.930 | 0.682 | 0.973 | 44 | 16 | 0 | 0.853 | 1.67 |
| naruto | 0.0986 | 0.6686 | 0.733 | 0.508 | 0.955 | 59 | 16 | 0 | 0.868 | 0.45 |
| rick | 0.0982 | 0.6284 | 0.688 | 0.491 | 0.982 | 44 | 25 | 0 | 0.805 | 0.35 |
| tmnt | 0.1322 | 0.4843 | 0.760 | 0.580 | 0.979 | 36 | 36 | 0 | 0.816 | 1.82 |
| koth | 0.0934 | 0.3940 | 0.758 | 0.642 | 0.977 | 41 | 19 | 0 | 0.799 | 2.47 |
| pokemon | 0.0904 | 0.6110 | 0.727 | 0.521 | 0.986 | 51 | 30 | 0 | 0.822 | 0.83 |

Touch is 0 on every theme. Before the final repair the same renders had 8–15 touching pairs, because a dropped piece had been the only thing between two copies. Reuse is 36–59 sources. Versus section 4, ΔE rose by 0.002–0.006. SSIM rose on Rick (+0.024) and TMNT (+0.023) and fell on the portrait (−0.029), Naruto (−0.028), and King of the Hill (−0.026). Piece fidelity stayed within 0.02. Coverage stayed above 0.95. Edge F1 is 0.80–0.87.

Kept. The spacing rule is required, and the outline scores are the new gate. The 300 px thumbs still read as the characters: the portrait face and flower, Team 7 from Rick and Morty stills, the Smiths from TMNT, the turtles on white, the alley group from Pokemon, and Ash with Pikachu from King of the Hill. Face crops keep the eye and hair lines. A light halo remains on some cuts. Random grid seeds place 64 cells from 6 sources with max reuse 16 and zero touches. Collage seeds place 19 copies with max reuse 6 and zero touches. A denser color-corrected portrait places 62 copies with max reuse 10 and zero touches.
