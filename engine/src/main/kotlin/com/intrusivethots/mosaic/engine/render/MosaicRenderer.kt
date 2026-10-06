package com.intrusivethots.mosaic.engine.render

import com.intrusivethots.mosaic.engine.color.OkLab
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.OutputLayout
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.config.TileFit
import com.intrusivethots.mosaic.engine.config.validated
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.image.sampleBilinear
import com.intrusivethots.mosaic.engine.image.sourceCoordinate
import com.intrusivethots.mosaic.engine.match.MosaicPlan
import com.intrusivethots.mosaic.engine.tile.TileAnalyzer
import com.intrusivethots.mosaic.engine.tile.TileDescriptor
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class MosaicRenderer {
    suspend fun render(
        plan: MosaicPlan,
        descriptors: List<TileDescriptor>,
        thumbnails: List<PixelImage>,
        layout: OutputLayout,
        config: MosaicConfig,
        sink: RowSink,
        onProgress: (Float) -> Unit = {}
    ) {
        val validated = config.validated()
        val row = IntArray(layout.width)
        val cellWidth = layout.cellWidth
        val cellHeight = layout.cellHeight
        val strength = validated.colorMatchWeight
        val centerCrop = validated.tileFit == TileFit.CENTER_CROP
        var lastReported = -1
        for (y in 0 until layout.height) {
            if (y % cellHeight == 0) coroutineContext.ensureActive()
            val cellRow = y / cellHeight
            val dy = y % cellHeight
            val shift = if (layout.staggered && cellRow % 2 == 1) cellWidth / 2 else 0
            for (x in 0 until layout.width) {
                var localX = x - shift
                if (localX < 0) localX += layout.width
                val cellColumn = (localX / cellWidth).coerceIn(0, layout.columns - 1)
                val dx = localX % cellWidth
                val cell = cellRow * layout.columns + cellColumn
                row[x] = pixelAt(
                    plan = plan,
                    descriptors = descriptors,
                    thumbnails = thumbnails,
                    cell = cell,
                    dx = dx,
                    dy = dy,
                    cellWidth = cellWidth,
                    cellHeight = cellHeight,
                    centerCrop = centerCrop,
                    mode = validated.renderMode,
                    strength = strength
                )
            }
            sink.writeRow(y, row)
            val percent = ((y + 1) * 100) / layout.height
            if (percent != lastReported) {
                lastReported = percent
                onProgress((y + 1).toFloat() / layout.height.toFloat())
            }
        }
    }

    private fun pixelAt(
        plan: MosaicPlan,
        descriptors: List<TileDescriptor>,
        thumbnails: List<PixelImage>,
        cell: Int,
        dx: Int,
        dy: Int,
        cellWidth: Int,
        cellHeight: Int,
        centerCrop: Boolean,
        mode: RenderMode,
        strength: Float
    ): Int {
        val fallback = plan.cellRgb[cell] or OPAQUE
        val tileIndex = plan.assignments[cell]
        if (tileIndex !in thumbnails.indices || tileIndex !in descriptors.indices) return fallback
        val source = thumbnails[tileIndex]
        val mapped = sourceCoordinate(source.width, source.height, cellWidth, cellHeight, dx, dy, centerCrop)
            ?: return fallback
        val sampled = source.sampleBilinear(mapped.first, mapped.second)
        if ((sampled ushr 24) <= TileAnalyzer.ALPHA_THRESHOLD) return fallback
        return correct(sampled or OPAQUE, descriptors[tileIndex], plan, cell, mode, strength)
    }

    private fun correct(
        argb: Int,
        tile: TileDescriptor,
        plan: MosaicPlan,
        cell: Int,
        mode: RenderMode,
        strength: Float
    ): Int {
        if (mode == RenderMode.ORIGINAL || strength <= 0f) return argb
        val lab = OkLab.fromArgb(argb)
        val base = cell * 3
        val targetL = plan.cellLab[base]
        val targetA = plan.cellLab[base + 1]
        val targetB = plan.cellLab[base + 2]
        val shifted = OkLab.Lab(
            l = (lab.l + (targetL - tile.labL) * strength).coerceIn(0f, 1f),
            a = (lab.a + (targetA - tile.labA) * strength).coerceIn(-0.5f, 0.5f),
            b = (lab.b + (targetB - tile.labB) * strength).coerceIn(-0.5f, 0.5f)
        )
        val corrected = OkLab.toArgb(shifted)
        if (mode == RenderMode.COLOR_CORRECTED) return corrected
        val pull = strength * 0.5f
        val blended = OkLab.Lab(
            l = shifted.l + (targetL - shifted.l) * pull,
            a = shifted.a + (targetA - shifted.a) * pull,
            b = shifted.b + (targetB - shifted.b) * pull
        )
        return OkLab.toArgb(blended)
    }

    companion object {
        private const val OPAQUE = 0xFF shl 24
    }
}
