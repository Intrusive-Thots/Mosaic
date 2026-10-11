package com.intrusivethots.mosaic.ui.components

import com.intrusivethots.mosaic.engine.config.AspectRatioPreset
import com.intrusivethots.mosaic.engine.config.CellAspect
import com.intrusivethots.mosaic.engine.config.CollageBackground
import com.intrusivethots.mosaic.engine.config.CollageStyle
import com.intrusivethots.mosaic.engine.config.HybridStack
import com.intrusivethots.mosaic.engine.config.LayoutMode
import com.intrusivethots.mosaic.engine.config.MosaicKind
import com.intrusivethots.mosaic.engine.config.MosaicStyle
import com.intrusivethots.mosaic.engine.config.OutputMode
import com.intrusivethots.mosaic.engine.config.QualityPreset
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.config.RotationMode
import com.intrusivethots.mosaic.engine.config.TileFit
import com.intrusivethots.mosaic.engine.config.collageLongEdge
import com.intrusivethots.mosaic.engine.image.CropAspect
import com.intrusivethots.mosaic.engine.image.label

internal fun modeHelp(kind: MosaicKind): String = when (kind) {
    MosaicKind.GRID -> "Library photos fill a grid of cells."
    MosaicKind.COLLAGE -> "Cutouts are placed over the picture, large shapes first, then smaller ones."
}

internal fun qualityHelp(preset: QualityPreset): String = when (preset) {
    QualityPreset.DRAFT ->
        "A fast pass: 24 columns, 180 collage pieces, Standard output, and a light color blend."
    QualityPreset.BALANCED ->
        "40 columns, 480 collage pieces, Standard output, and color correction at 65%."
    QualityPreset.HIGH_QUALITY ->
        "64 columns, 1,200 collage pieces, High output, color correction at 75%, and a mild preference for less-used photos."
    QualityPreset.MAXIMUM ->
        "100 columns, 2,200 collage pieces, Ultra output, color correction at 85%, and a stronger preference for less-used photos."
    QualityPreset.CUSTOM ->
        "The controls below are yours. A preset replaces the grid size, piece count, coloring, and output size."
}

internal fun collageStyleHelp(style: CollageStyle): String = when (style) {
    CollageStyle.PAPER ->
        "Sets a light color blend, a thin shadow, a paper edge, and gaps filled with the average color."
    CollageStyle.STAMP ->
        "Photos keep their own colors, with a dark line around each cutout edge."
    CollageStyle.PAINTERLY ->
        "Sets a stronger color blend, and the soft edges of each cutout fade out."
    CollageStyle.SPARSE ->
        "Cuts the current piece count to about 40%, enlarges the fill-in pieces, and adds a shadow " +
            "with moderate color correction. Tapping it again shrinks the count further."
    CollageStyle.DENSE ->
        "Keeps the current coloring. Edges stay mostly sharp, and pieces sit flat on the picture."
}

internal fun layeringHelp(stack: HybridStack): String = when (stack) {
    HybridStack.CUTOUTS -> "Only the cutout collage is drawn."
    HybridStack.GRID -> "Only the photo grid is drawn."
    HybridStack.GRID_UNDER -> "The photo grid is drawn first. Cutouts sit on top of it."
    HybridStack.COLLAGE_UNDER -> "Cutouts are drawn first. The photo grid sits on top of them."
}

internal fun aspectHelp(preset: AspectRatioPreset): String = when (preset) {
    AspectRatioPreset.ORIGINAL -> "The photo keeps its own shape."
    else -> "The photo is center-cropped to ${preset.label} before matching."
}

internal const val SOURCE_SCALE_HELP =
    "Shrinks the photo before matching. Lower is faster and keeps less detail. A collage of a large photo can also finish smaller."

internal fun outputResolutionLabel(mode: OutputMode, stack: HybridStack): String = when (stack) {
    HybridStack.GRID -> "${mode.label} · ${mode.cellPixels} px cells"
    else -> "${mode.label} · ${collageLongEdge(mode)} px long edge"
}

internal fun outputResolutionHelp(mode: OutputMode, stack: HybridStack): String {
    val edge = collageLongEdge(mode)
    val cells = mode.cellPixels
    return when (stack) {
        HybridStack.GRID ->
            "Each cell is $cells pixels tall in the full picture. Width follows the cell shape. " +
                "A custom size below replaces this. The phone may shrink a huge result so it fits in memory."
        HybridStack.CUTOUTS ->
            "The long edge is at least $edge pixels. A photo already larger than that keeps its size. " +
                "A custom size below replaces this. The phone may shrink the result so it fits in memory."
        else ->
            "The long edge is at least $edge pixels, and the grid is divided across that picture. " +
                "A custom size below replaces this. The phone may shrink the result so it fits in memory."
    }
}

internal const val CUSTOM_SIZE_HELP =
    "Leave a field blank to use the output resolution above. A number replaces it, from 64 to 8192 pixels."

internal const val LOCK_ASPECT_HELP =
    "Height follows the width, using the photo's proportions."

internal fun patternHelp(style: MosaicStyle): String = when (style) {
    MosaicStyle.GRID -> "Rows line up in columns."
    MosaicStyle.STAGGERED_BRICK -> "Odd rows shift by half a cell on a uniform layout. A mixed layout stays aligned."
}

internal const val COLUMNS_HELP =
    "More columns mean smaller cells and a finer picture. Matching takes longer."

internal fun matchRowsHelp(linked: Boolean): String = if (linked) {
    "The row count follows the columns and the photo, so cells stay close to the photo's shape."
} else {
    "The row count is the slider below. Cells can come out taller or wider than the photo."
}

internal const val ROWS_HELP =
    "How many rows to use. Together with the column count, this sets each cell's shape."

internal fun cellShapeHelp(aspect: CellAspect, linked: Boolean): String = when {
    aspect == CellAspect.MATCH_GRID -> "Cells follow the columns, rows, and the photo."
    linked -> "The row count changes so each cell is close to ${aspect.label}."
    else -> "Applies while Match rows is on. With a hand-set row count, cell shape follows those counts."
}

internal fun layoutHelp(mode: LayoutMode): String = when (mode) {
    LayoutMode.UNIFORM -> "Every cell is the same size."
    LayoutMode.MIXED -> "Portrait photos get tall cells and landscape photos get wide cells, with no empty gaps."
}

internal fun placementHelp(fit: TileFit): String = when (fit) {
    TileFit.CENTER_CROP -> "Crops each photo so it fills its cell."
    TileFit.FIT_INSIDE -> "Shows the whole photo and leaves bars where the shapes differ."
}

internal fun rotationHelp(mode: RotationMode): String = when (mode) {
    RotationMode.OFF -> "Every photo stays upright."
    RotationMode.ORIENTATION -> "Turns a photo by a quarter turn when its shape fits the cell better."
    RotationMode.FULL -> "Tries quarter turns and mirrored copies. A mirror still counts as the same photo."
}

internal const val PIECES_HELP =
    "How many cutouts the collage tries to place. More pieces add small detail and take longer. Flat areas and edges decide where they land."

internal const val SMALLEST_PIECE_HELP =
    "The later fill-in pieces use this share of the short side. It stays at or below Largest piece. " +
        "When Minimum piece is larger, that minimum is the size that is used."

internal const val LARGEST_PIECE_HELP =
    "The biggest shapes follow flat areas of the picture, such as sky. Moving this leaves the picture the same, " +
        "unless Smallest piece sits above it, in which case Smallest piece comes down with it."

internal const val MINIMUM_PIECE_HELP =
    "Anything smaller than this share of the short side is merged into a neighbor or covered. Raising it makes the remaining pieces easier to see."

internal const val ROTATION_RANGE_HELP =
    "Cutout pieces stay upright. The picture stays the same when this moves. The value is saved with the project."

internal const val OVERLAP_HELP =
    "Where two pieces cover one spot, the color closer to the picture stays. The picture stays the same when this moves. The value is saved with the project."

internal fun backgroundHelp(background: CollageBackground): String = when (background) {
    CollageBackground.MEAN_COLOR ->
        "Gaps are mostly the picture's average color, with a muted trace of the photo showing through."
    CollageBackground.TARGET -> "Gaps show the target photo."
}

internal const val FULL_PHOTOS_HELP =
    "On, uncut library photos are placed along with the cutouts. Off, only cutouts are used while any exist. With no cutouts, the photos are used."

internal const val SEPARATE_PIECES_HELP =
    "Draws a thin shadow under each piece so the pieces read as separate."

internal fun coloringHelp(mode: RenderMode): String = when (mode) {
    RenderMode.ORIGINAL -> "Photos are placed with their own colors."
    RenderMode.COLOR_CORRECTED -> "Shifts each photo's color toward the picture underneath it. Brightness moves only part of the way."
    RenderMode.BLENDED -> "A lighter color shift that keeps more of the photo's own color."
}

internal fun correctionHelp(mode: RenderMode): String = when (mode) {
    RenderMode.ORIGINAL -> "Original tiles skip this. It applies after you choose Color corrected or Blended."
    RenderMode.COLOR_CORRECTED ->
        "How far each photo's color moves toward the picture under it. 100% pulls the color all the way. Brightness moves only part of the way."
    RenderMode.BLENDED ->
        "The same pull, softer. This percent moves the color about two thirds as far as Color corrected."
}

internal const val REPEATS_HELP =
    "On, a photo may appear more than once. A flip, rotation, or resize still counts as that photo. " +
        "Off, each photo is used at most once, which needs a large library."

internal fun repeatSpacingHelp(allowRepeats: Boolean, stack: HybridStack): String {
    if (!allowRepeats) {
        return "Each photo is used once, so this spacing waits until repeats are allowed."
    }
    val grid = "On the grid, copies stay at least this many cells apart, corners included. " +
        "The lowest values already keep them from sharing an edge or a corner."
    val collage = "Collage copies stay apart, corners included, at every value of this slider. A higher number can consider more photos for each piece."
    return when (stack) {
        HybridStack.GRID -> "$grid A higher number leaves a wider gap."
        HybridStack.CUTOUTS -> collage
        else -> "$grid $collage"
    }
}

internal const val MAX_SUBJECTS_HELP =
    "The most cutouts kept from each photo. A small library keeps fewer than this, so cutouts stay a minority of the pool."

internal const val MIN_SUBJECT_HELP =
    "A shape whose short side is under this many pixels is skipped."

internal fun cropAspectHelp(aspect: CropAspect): String = when (aspect) {
    CropAspect.FREE -> "Any rectangle. Drag inside to move it, and drag a corner or edge to resize it."
    CropAspect.ORIGINAL -> "The box keeps the photo's own shape while you drag."
    else -> "The box stays ${aspect.label} while you drag."
}

internal const val DENSITY_HELP =
    "Higher packs more pieces into the selection and makes them smaller. Lower uses fewer, larger pieces."

internal const val REGION_COLOR_HELP =
    "How far the rebuilt pieces shift toward the picture. 0% keeps their own color. Original tile coloring leaves them unchanged."

internal const val EXCLUDE_REGION_HELP =
    "Photos already inside this region stay out of the rebuild."

internal const val VARIATION_HELP =
    "Tries another arrangement of the same area."
