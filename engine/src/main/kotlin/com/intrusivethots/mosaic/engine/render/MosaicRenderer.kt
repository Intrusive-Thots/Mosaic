package com.intrusivethots.mosaic.engine.render

import com.intrusivethots.mosaic.engine.color.OkLab
import com.intrusivethots.mosaic.engine.config.CollageBackground
import com.intrusivethots.mosaic.engine.config.HybridStack
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.OutputLayout
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.config.TileFit
import com.intrusivethots.mosaic.engine.config.effectiveStack
import com.intrusivethots.mosaic.engine.config.validated
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.image.sampleBilinear
import com.intrusivethots.mosaic.engine.image.sourceCoordinate
import com.intrusivethots.mosaic.engine.image.unrotateSample
import com.intrusivethots.mosaic.engine.match.MosaicPlan
import com.intrusivethots.mosaic.engine.tile.TileAnalyzer
import com.intrusivethots.mosaic.engine.tile.TileDescriptor
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class MosaicRenderer(
    private val collageRenderer: CollageRenderer = CollageRenderer()
) {
    suspend fun render(
        plan: MosaicPlan,
        descriptors: List<TileDescriptor>,
        thumbnails: List<PixelImage>,
        layout: OutputLayout,
        config: MosaicConfig,
        sink: RowSink,
        target: PixelImage? = null,
        coverage: BooleanArray? = null,
        owners: IntArray? = null,
        onProgress: (Float) -> Unit = {}
    ) {
        val stack = config.validated().effectiveStack()
        if (stack == HybridStack.GRID_UNDER || stack == HybridStack.COLLAGE_UNDER) {
            renderHybrid(plan, descriptors, thumbnails, layout, config, sink, target, stack, coverage, owners, onProgress)
            return
        }
        if (plan.placements.isNotEmpty() && stack.usesCollage()) {
            collageRenderer.render(
                plan, descriptors, thumbnails, layout, config, target, sink, coverage, owners, onProgress
            )
            return
        }
        if (plan.anchors.isNotEmpty()) {
            renderPlaced(plan, descriptors, thumbnails, layout, config, sink, onProgress)
            return
        }
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
                val code = orientationCode(plan, cell)
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
                    strength = strength,
                    quarterTurns = code and 3,
                    mirror = code >= 4
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

    private suspend fun renderPlaced(
        plan: MosaicPlan,
        descriptors: List<TileDescriptor>,
        thumbnails: List<PixelImage>,
        layout: OutputLayout,
        config: MosaicConfig,
        sink: RowSink,
        onProgress: (Float) -> Unit
    ) {
        val validated = config.validated()
        val row = IntArray(layout.width)
        val centerCrop = validated.tileFit == TileFit.CENTER_CROP
        var lastReported = -1
        for (y in 0 until layout.height) {
            if (y % layout.cellHeight == 0) coroutineContext.ensureActive()
            val cellRow = (y / layout.cellHeight).coerceAtMost(layout.rows - 1)
            for (x in 0 until layout.width) {
                val cellColumn = (x / layout.cellWidth).coerceAtMost(layout.columns - 1)
                val unit = cellRow * layout.columns + cellColumn
                val anchor = plan.anchors[unit]
                val anchorColumn = anchor % layout.columns
                val anchorRow = anchor / layout.columns
                val placeWidth = plan.spanX[anchor].toInt() * layout.cellWidth
                val placeHeight = plan.spanY[anchor].toInt() * layout.cellHeight
                val code = orientationCode(plan, anchor)
                row[x] = pixelAt(
                    plan = plan,
                    descriptors = descriptors,
                    thumbnails = thumbnails,
                    cell = anchor,
                    dx = x - anchorColumn * layout.cellWidth,
                    dy = y - anchorRow * layout.cellHeight,
                    cellWidth = placeWidth,
                    cellHeight = placeHeight,
                    centerCrop = centerCrop,
                    mode = validated.renderMode,
                    strength = validated.colorMatchWeight,
                    quarterTurns = code and 3,
                    mirror = code >= 4
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
        strength: Float,
        quarterTurns: Int = 0,
        mirror: Boolean = false
    ): Int {
        val fallback = plan.cellRgb[cell] or OPAQUE
        val tileIndex = plan.assignments[cell]
        if (tileIndex !in thumbnails.indices || tileIndex !in descriptors.indices) return fallback
        val source = thumbnails[tileIndex]
        val mapped = mappedSample(source, cellWidth, cellHeight, dx, dy, centerCrop, quarterTurns, mirror)
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

    private fun mappedSample(
        source: PixelImage,
        cellWidth: Int,
        cellHeight: Int,
        dx: Int,
        dy: Int,
        centerCrop: Boolean,
        quarterTurns: Int,
        mirror: Boolean
    ): Pair<Float, Float>? {
        if (quarterTurns == 0 && !mirror) {
            return sourceCoordinate(source.width, source.height, cellWidth, cellHeight, dx, dy, centerCrop)
        }
        val displayWidth = if (quarterTurns and 1 == 1) source.height else source.width
        val displayHeight = if (quarterTurns and 1 == 1) source.width else source.height
        val oriented = sourceCoordinate(displayWidth, displayHeight, cellWidth, cellHeight, dx, dy, centerCrop)
            ?: return null
        return unrotateSample(source.width, source.height, oriented.first, oriented.second, quarterTurns, mirror)
    }

    private suspend fun renderHybrid(
        plan: MosaicPlan,
        descriptors: List<TileDescriptor>,
        thumbnails: List<PixelImage>,
        layout: OutputLayout,
        config: MosaicConfig,
        sink: RowSink,
        target: PixelImage?,
        stack: HybridStack,
        coverage: BooleanArray?,
        owners: IntArray?,
        onProgress: (Float) -> Unit
    ) {
        val collageOnTop = stack == HybridStack.GRID_UNDER
        val sprites = collageRenderer.sprites(plan, descriptors, thumbnails, layout.width, layout.height, target)
        val field = lowFrequencyField(target)
        val mean = if (target == null) 0xFF18181C.toInt() else meanOf(target)
        val useTarget = config.collage.background == CollageBackground.TARGET && target != null
        val row = IntArray(layout.width)
        val gridRow = IntArray(layout.width)
        val grout = (minOf(layout.cellWidth, layout.cellHeight) / 10).coerceAtLeast(1)
        var lastReported = -1
        for (y in 0 until layout.height) {
            if (y % 8 == 0) coroutineContext.ensureActive()
            if (collageOnTop) {
                fillUniformRow(plan, descriptors, thumbnails, layout, config, y, row)
                paintCutouts(row, y, sprites, config, layout, coverage, owners, field)
            } else {
                collageRenderer.paintBackground(row, y, layout.width, layout.height, target, useTarget, mean)
                paintCutouts(row, y, sprites, config, layout, coverage, owners, field)
                fillUniformRow(plan, descriptors, thumbnails, layout, config, y, gridRow)
                overlayGrid(row, gridRow, y, layout, grout, coverage)
            }
            sink.writeRow(y, row)
            val percent = ((y + 1) * 100) / layout.height
            if (percent != lastReported) {
                lastReported = percent
                onProgress((y + 1).toFloat() / layout.height.toFloat())
            }
        }
    }

    private fun paintCutouts(
        row: IntArray,
        y: Int,
        sprites: List<CollageRenderer.Sprite>,
        config: MosaicConfig,
        layout: OutputLayout,
        coverage: BooleanArray?,
        owners: IntArray?,
        field: PixelImage?
    ) {
        val width = layout.width
        for (index in sprites.indices) {
            val sprite = sprites[index]
            if (sprite.placement.mask != null) {
                paintShapeRow(
                    row, y, width, layout.height, sprite.source, sprite.base, sprite.descriptor, sprite.placement,
                    config, coverage, owners, index, sprite.tone, field, sprite.outline
                )
                continue
            }
            if (config.collage.separatePieces) collageRenderer.paintShadow(row, y, sprite, width)
            if (y < sprite.draw.top || y > sprite.draw.bottom) continue
            collageRenderer.paintSprite(row, y, sprite, config, coverage, width, owners = owners, owner = index)
        }
    }

    private fun overlayGrid(
        row: IntArray,
        gridRow: IntArray,
        y: Int,
        layout: OutputLayout,
        grout: Int,
        coverage: BooleanArray?
    ) {
        val dy = y % layout.cellHeight
        if (dy < grout) return
        for (x in row.indices) {
            if (x % layout.cellWidth < grout) continue
            row[x] = gridRow[x]
            if (coverage != null) coverage[y * layout.width + x] = false
        }
    }

    private fun fillUniformRow(
        plan: MosaicPlan,
        descriptors: List<TileDescriptor>,
        thumbnails: List<PixelImage>,
        layout: OutputLayout,
        config: MosaicConfig,
        y: Int,
        row: IntArray
    ) {
        val validated = config.validated()
        val cellWidth = layout.cellWidth
        val cellHeight = layout.cellHeight
        val cellRow = (y / cellHeight).coerceIn(0, layout.rows - 1)
        val dy = y % cellHeight
        val shift = if (layout.staggered && cellRow % 2 == 1) cellWidth / 2 else 0
        for (x in 0 until layout.width) {
            var localX = x - shift
            if (localX < 0) localX += layout.width
            val cellColumn = (localX / cellWidth).coerceIn(0, layout.columns - 1)
            val cell = cellRow * layout.columns + cellColumn
            val code = orientationCode(plan, cell)
            row[x] = pixelAt(
                plan, descriptors, thumbnails, cell, localX % cellWidth, dy, cellWidth, cellHeight,
                validated.tileFit == TileFit.CENTER_CROP, validated.renderMode, validated.colorMatchWeight,
                code and 3, code >= 4
            )
        }
    }

    private fun meanOf(image: PixelImage): Int {
        var red = 0L
        var green = 0L
        var blue = 0L
        val step = (image.pixels.size / 2000).coerceAtLeast(1)
        var count = 0L
        var index = 0
        while (index < image.pixels.size) {
            val pixel = image.pixels[index]
            red += (pixel ushr 16) and 0xFF
            green += (pixel ushr 8) and 0xFF
            blue += pixel and 0xFF
            count++
            index += step
        }
        val n = count.coerceAtLeast(1)
        return ((red / n).toInt() shl 16) or ((green / n).toInt() shl 8) or (blue / n).toInt() or OPAQUE
    }

    private fun orientationCode(plan: MosaicPlan, cell: Int): Int {
        if (plan.orientations.isEmpty() || cell !in plan.orientations.indices) return 0
        return plan.orientations[cell].toInt() and 0xFF
    }

    companion object {
        private const val OPAQUE = 0xFF shl 24
    }
}
