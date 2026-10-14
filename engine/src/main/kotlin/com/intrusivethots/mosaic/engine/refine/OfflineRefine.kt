package com.intrusivethots.mosaic.engine.refine

import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.coord.GenerationCoordinator
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.match.CutoutPlacement
import com.intrusivethots.mosaic.engine.match.MosaicPlan
import com.intrusivethots.mosaic.engine.quality.distanceReadability
import com.intrusivethots.mosaic.engine.tile.TileSource
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Offline refinement prototype (expert review item 10). It is off by default and the app never
 * calls it. The tuning harness turns it on with `-Dmosaic.offlineRefine=true`.
 *
 * It tunes each large piece's crop and tone against a measured objective instead of a heuristic:
 * render the plan, score it against the target, keep any move that lowers the score. This is
 * coordinate descent on a real render, a stand-in for the gradient step of a differentiable
 * rasterizer (DiffVG, Neural Collage Transfer). Masks never change, so outlines stay as cut and
 * copies of one source stay as far apart as before.
 */
object OfflineRefine {
    val enabled: Boolean = System.getProperty("mosaic.offlineRefine")?.toBoolean() ?: false
}

class RefineReport(val plan: MosaicPlan, val before: Double, val after: Double, val evaluations: Int, val accepted: Int)

/** Lower is better: color distance plus a share of structural dissimilarity, at a small size. */
fun refineObjective(rendered: PixelImage, target: PixelImage): Double {
    val read = distanceReadability(rendered, target, OBJECTIVE_WIDTH)
    return read.deltaE + SSIM_SHARE * (1.0 - read.ssim)
}

suspend fun refineOffline(
    target: PixelImage,
    tiles: List<TileSource>,
    config: MosaicConfig,
    start: MosaicPlan,
    budgetMs: Long = DEFAULT_BUDGET_MS,
    maxPieces: Int = DEFAULT_PIECES,
    coordinator: GenerationCoordinator = GenerationCoordinator()
): RefineReport {
    val deadline = System.nanoTime() + budgetMs * 1_000_000
    var evaluations = 0
    suspend fun score(plan: MosaicPlan): Double {
        coroutineContext.ensureActive()
        evaluations++
        val image = coordinator.generate(target, tiles, config, preview = true, reusePlan = plan).image
            ?: return Double.MAX_VALUE
        return refineObjective(image, target)
    }
    var plan = start
    val before = score(plan)
    var best = before
    var accepted = 0
    val order = start.placements.indices
        .filter { !start.placements[it].pinned && start.placements[it].mask != null }
        .sortedByDescending { start.placements[it].maskArea() }
        .take(maxPieces)
    for (index in order) {
        for (move in MOVES) {
            if (System.nanoTime() > deadline) return RefineReport(plan, before, best, evaluations, accepted)
            val trial = plan.withPlacement(index, move(plan.placements[index]))
            val value = score(trial)
            if (value < best) {
                best = value
                plan = trial
                accepted++
            }
        }
    }
    return RefineReport(plan, before, best, evaluations, accepted)
}

private fun CutoutPlacement.maskArea(): Float = mask?.let { (it.right - it.left) * (it.bottom - it.top) } ?: 0f

private fun MosaicPlan.withPlacement(index: Int, placement: CutoutPlacement): MosaicPlan = MosaicPlan(
    columns, rows, assignments, cellRgb, cellLab, staggered, fingerprint,
    orientations, anchors, spanX, spanY,
    placements.toMutableList().also { it[index] = placement },
    coverage
)

private fun CutoutPlacement.adjusted(
    cropU: Float = this.cropU,
    cropV: Float = this.cropV,
    cropSpan: Float = this.cropSpan,
    targetL: Float = this.targetL
) = CutoutPlacement(
    tileIndex, x, y, angleDegrees, scale, targetL, targetA, targetB, pinned, mask,
    cropU.coerceIn(CROP_LOW, CROP_HIGH), cropV.coerceIn(CROP_LOW, CROP_HIGH), cropSpan.coerceIn(SPAN_LOW, SPAN_HIGH),
    faceLeft, faceTop, faceRight, faceBottom
)

private val MOVES: List<(CutoutPlacement) -> CutoutPlacement> = listOf(
    { it.adjusted(cropU = it.cropU + CROP_STEP) },
    { it.adjusted(cropU = it.cropU - CROP_STEP) },
    { it.adjusted(cropV = it.cropV + CROP_STEP) },
    { it.adjusted(cropV = it.cropV - CROP_STEP) },
    { it.adjusted(cropSpan = it.cropSpan * (1f + SPAN_STEP)) },
    { it.adjusted(cropSpan = it.cropSpan * (1f - SPAN_STEP)) },
    { it.adjusted(targetL = it.targetL + TONE_STEP) },
    { it.adjusted(targetL = it.targetL - TONE_STEP) }
)

private const val OBJECTIVE_WIDTH = 96
private const val SSIM_SHARE = 0.1
private const val DEFAULT_BUDGET_MS = 60_000L
private const val DEFAULT_PIECES = 24
private const val CROP_STEP = 0.04f
private const val SPAN_STEP = 0.1f
private const val TONE_STEP = 0.03f
private const val CROP_LOW = 0.05f
private const val CROP_HIGH = 0.95f
private const val SPAN_LOW = 0.04f
private const val SPAN_HIGH = 1f
