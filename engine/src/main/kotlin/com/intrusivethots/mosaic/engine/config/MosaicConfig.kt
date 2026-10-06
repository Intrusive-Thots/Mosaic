package com.intrusivethots.mosaic.engine.config

/**
 * Generation settings. The first nine parameters match the 1.x constructor so existing call sites
 * keep compiling. New fields have defaults.
 */
data class MosaicConfig(
    val aspectRatio: AspectRatioPreset = AspectRatioPreset.ORIGINAL,
    val gridColumns: Int = 40,
    val gridRows: Int = 40,
    val linkAspectToGrid: Boolean = true,
    val colorMatchWeight: Float = 0.65f,
    val allowTileRepetition: Boolean = true,
    val maxRepetitionDistance: Int = 0,
    val extractSubjectsWithAi: Boolean = false,
    val mosaicStyle: MosaicStyle = MosaicStyle.GRID,
    val qualityPreset: QualityPreset = QualityPreset.BALANCED,
    val renderMode: RenderMode = RenderMode.COLOR_CORRECTED,
    val outputMode: OutputMode = OutputMode.STANDARD,
    val tileFit: TileFit = TileFit.CENTER_CROP,
    val descriptorMaxEdge: Int = 24,
    val candidateCount: Int = 16,
    val usageBalanceWeight: Float = 0f,
    val randomSeed: Int = 1,
    val scoreWeights: ScoreWeights = ScoreWeights(),
    val previewCellPixels: Int = 8,
    val segmentation: SegmentationSettings = SegmentationSettings(),
    val cellAspect: CellAspect = CellAspect.MATCH_GRID,
    val layoutMode: LayoutMode = LayoutMode.UNIFORM,
    val rotationMode: RotationMode = RotationMode.OFF,
    val targetQuarterTurns: Int = 0,
    val targetScale: Float = 1f,
    val customOutputWidth: Int = 0,
    val customOutputHeight: Int = 0,
    val lockOutputAspect: Boolean = true
)

enum class AspectRatioPreset(val label: String, val widthRatio: Float, val heightRatio: Float) {
    ORIGINAL("Original", 0f, 0f),
    SQUARE_1_1("1:1", 1f, 1f),
    PHOTO_4_3("4:3", 4f, 3f),
    PORTRAIT_3_4("3:4", 3f, 4f),
    WIDESCREEN_16_9("16:9", 16f, 9f),
    STORY_9_16("9:16", 9f, 16f),
    CLASSIC_3_2("3:2", 3f, 2f),
    PORTRAIT_4_5("4:5", 4f, 5f)
}

enum class MosaicStyle(val label: String) {
    GRID("Standard Grid"),
    STAGGERED_BRICK("Staggered Bricks")
}

enum class RenderMode(val label: String) {
    ORIGINAL("Original tiles"),
    COLOR_CORRECTED("Color corrected"),
    BLENDED("Blended")
}

enum class OutputMode(val label: String, val cellPixels: Int) {
    STANDARD("Standard", 24),
    HIGH("High", 40),
    ULTRA("Ultra", 64)
}

enum class TileFit(val label: String) {
    CENTER_CROP("Center crop"),
    FIT_INSIDE("Fit inside")
}

/** Width / height of one uniform cell. [MATCH_GRID] keeps today's grid math. */
enum class CellAspect(val label: String, val ratio: Float) {
    MATCH_GRID("Match grid", 0f),
    SQUARE("Square", 1f),
    LANDSCAPE_4_3("4:3", 4f / 3f),
    LANDSCAPE_3_2("3:2", 3f / 2f),
    LANDSCAPE_16_9("16:9", 16f / 9f),
    PORTRAIT_3_4("3:4", 3f / 4f),
    PORTRAIT_2_3("2:3", 2f / 3f),
    PORTRAIT_9_16("9:16", 9f / 16f)
}

enum class LayoutMode(val label: String) {
    UNIFORM("Uniform cells"),
    MIXED("Mixed shapes")
}

/** How many rotated copies of a tile the matcher may consider. Usage stays on the source photo. */
enum class RotationMode(val label: String) {
    OFF("Off"),
    ORIENTATION("Match orientation"),
    FULL("Full rotation")
}

const val MIN_CUSTOM_OUTPUT_EDGE = 64
const val MAX_CUSTOM_OUTPUT_EDGE = 8192
const val MAX_OUTPUT_PIXELS = 24_000_000L
const val MAX_CELL_PIXELS = 512

enum class QualityPreset(val label: String) {
    DRAFT("Draft"),
    BALANCED("Balanced"),
    HIGH_QUALITY("High Quality"),
    MAXIMUM("Maximum"),
    CUSTOM("Custom")
}

data class ScoreWeights(
    val color: Float = 0.78f,
    val luminance: Float = 0.12f,
    val histogram: Float = 0.04f,
    val spatial: Float = 0.04f,
    val edge: Float = 0.02f
) {
    fun sanitized(): ScoreWeights {
        val safe = ScoreWeights(
            color = color.coerceAtLeast(0f),
            luminance = luminance.coerceAtLeast(0f),
            histogram = histogram.coerceAtLeast(0f),
            spatial = spatial.coerceAtLeast(0f),
            edge = edge.coerceAtLeast(0f)
        )
        val sum = safe.color + safe.luminance + safe.histogram + safe.spatial + safe.edge
        if (sum <= 1e-4f) return ScoreWeights()
        return ScoreWeights(
            color = safe.color / sum,
            luminance = safe.luminance / sum,
            histogram = safe.histogram / sum,
            spatial = safe.spatial / sum,
            edge = safe.edge / sum
        )
    }
}

enum class SubjectShape { TALL, WIDE, COMPACT }

data class SegmentationSettings(
    val maxExtractedSubjects: Int = 24,
    val minSubjectSizePx: Int = 48,
    val maxLibraryFraction: Float = 0.35f,
    val deduplicate: Boolean = true,
    val dedupDistance: Float = 0.18f,
    val allowedShapes: Set<SubjectShape> = setOf(SubjectShape.TALL, SubjectShape.WIDE, SubjectShape.COMPACT)
) {
    fun sanitized(): SegmentationSettings = copy(
        maxExtractedSubjects = maxExtractedSubjects.coerceIn(0, 500),
        minSubjectSizePx = minSubjectSizePx.coerceIn(1, 4096),
        maxLibraryFraction = maxLibraryFraction.coerceIn(0f, 0.9f),
        dedupDistance = dedupDistance.coerceIn(0f, 1f),
        allowedShapes = allowedShapes.ifEmpty { setOf(SubjectShape.TALL, SubjectShape.WIDE, SubjectShape.COMPACT) }
    )
}

fun MosaicConfig.validated(): MosaicConfig = copy(
    gridColumns = gridColumns.coerceIn(4, 200),
    gridRows = gridRows.coerceIn(4, 200),
    colorMatchWeight = colorMatchWeight.coerceIn(0f, 1f),
    maxRepetitionDistance = maxRepetitionDistance.coerceIn(0, 64),
    descriptorMaxEdge = descriptorMaxEdge.coerceIn(8, 96),
    candidateCount = candidateCount.coerceIn(4, 128),
    usageBalanceWeight = usageBalanceWeight.coerceIn(0f, 10f),
    previewCellPixels = previewCellPixels.coerceIn(2, 64),
    scoreWeights = scoreWeights.sanitized(),
    segmentation = segmentation.sanitized(),
    targetQuarterTurns = targetQuarterTurns and 3,
    targetScale = if (targetScale.isNaN()) 1f else targetScale.coerceIn(0.25f, 1f)
)

fun MosaicConfig.customSizeError(): String? {
    val widthError = dimensionError("Custom width", customOutputWidth)
    if (widthError != null) return widthError
    return dimensionError("Custom height", customOutputHeight)
}

private fun dimensionError(name: String, value: Int): String? {
    if (value == 0) return null
    if (value < MIN_CUSTOM_OUTPUT_EDGE || value > MAX_CUSTOM_OUTPUT_EDGE) {
        return "$name must be from $MIN_CUSTOM_OUTPUT_EDGE to $MAX_CUSTOM_OUTPUT_EDGE pixels, or 0 to use the output preset."
    }
    return null
}

fun outputLimitError(width: Int, height: Int, cellWidth: Int, cellHeight: Int): String? {
    if (cellWidth > MAX_CELL_PIXELS || cellHeight > MAX_CELL_PIXELS) {
        return "Each cell would be ${cellWidth}×$cellHeight pixels. The maximum is $MAX_CELL_PIXELS. Use a finer grid or a smaller output."
    }
    val pixels = width.toLong() * height.toLong()
    if (pixels > MAX_OUTPUT_PIXELS) {
        return "Output ${width}×$height is $pixels pixels. The maximum is $MAX_OUTPUT_PIXELS. Reduce the custom size or the grid."
    }
    return null
}

/**
 * Fields that change which tile is chosen. Render mode and output size are intentionally absent
 * so preview and final output can share a plan.
 */
fun MosaicConfig.matchFingerprint(targetWidth: Int, targetHeight: Int, tileTokens: List<String>): String {
    val weights = scoreWeights.sanitized()
    return buildString {
        append(targetWidth).append('x').append(targetHeight)
        append("|c").append(gridColumns)
        append("|r").append(gridRows)
        append("|link").append(linkAspectToGrid)
        append("|ar").append(aspectRatio.name)
        append("|style").append(mosaicStyle.name)
        append("|rep").append(allowTileRepetition)
        append("@").append(maxRepetitionDistance)
        append("|bal").append(usageBalanceWeight)
        append("|seed").append(randomSeed)
        append("|k").append(candidateCount)
        append("|edge").append(descriptorMaxEdge)
        append("|fit").append(tileFit.name)
        append("|cell").append(cellAspect.name)
        append("|layout").append(layoutMode.name)
        append("|rot").append(rotationMode.name)
        append("|tq").append(targetQuarterTurns and 3)
        append("|scale").append((targetScale.coerceIn(0.25f, 1f) * 1000f).toInt())
        append("|w")
        append(weights.color).append(',')
        append(weights.luminance).append(',')
        append(weights.histogram).append(',')
        append(weights.spatial).append(',')
        append(weights.edge)
        append("|tiles")
        tileTokens.forEach { token ->
            append(token).append(';')
        }
    }
}
