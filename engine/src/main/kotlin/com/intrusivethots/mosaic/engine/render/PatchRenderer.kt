package com.intrusivethots.mosaic.engine.render

import com.intrusivethots.mosaic.engine.color.argb
import com.intrusivethots.mosaic.engine.config.CollageBackground
import com.intrusivethots.mosaic.engine.config.HybridStack
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.OutputLayout
import com.intrusivethots.mosaic.engine.config.TileFit
import com.intrusivethots.mosaic.engine.config.effectiveStack
import com.intrusivethots.mosaic.engine.config.validated
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.match.MosaicPlan
import com.intrusivethots.mosaic.engine.tile.TileDescriptor
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Renders only the pixels inside a window. Callers composite that window onto the previous image,
 * so the rest of the mosaic is never repainted.
 */
class PatchRenderer(
    private val collage: CollageRenderer = CollageRenderer(),
    private val grid: MosaicRenderer = MosaicRenderer()
) {
    suspend fun render(
        plan: MosaicPlan,
        descriptors: List<TileDescriptor>,
        thumbnails: List<PixelImage>,
        layout: OutputLayout,
        config: MosaicConfig,
        target: PixelImage?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): PixelImage {
        val clipLeft = left.coerceIn(0, layout.width - 1)
        val clipTop = top.coerceIn(0, layout.height - 1)
        val clipRight = right.coerceIn(clipLeft + 1, layout.width)
        val clipBottom = bottom.coerceIn(clipTop + 1, layout.height)
        val stack = config.validated().effectiveStack()
        if (stack == HybridStack.GRID_UNDER || stack == HybridStack.COLLAGE_UNDER) {
            return paintRows(plan, descriptors, thumbnails, layout, config, target, clipLeft, clipTop, clipRight, clipBottom, stack)
        }
        if (plan.placements.isNotEmpty() && stack.usesCollage()) {
            return paintRows(plan, descriptors, thumbnails, layout, config, target, clipLeft, clipTop, clipRight, clipBottom, stack)
        }
        return paintGrid(plan, descriptors, thumbnails, layout, config, clipLeft, clipTop, clipRight, clipBottom)
    }

    private fun paintGrid(
        plan: MosaicPlan,
        descriptors: List<TileDescriptor>,
        thumbnails: List<PixelImage>,
        layout: OutputLayout,
        config: MosaicConfig,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): PixelImage {
        val patchWidth = right - left
        val patchHeight = bottom - top
        val pixels = IntArray(patchWidth * patchHeight)
        val validated = config.validated()
        for (y in top until bottom) {
            for (x in left until right) {
                pixels[(y - top) * patchWidth + (x - left)] = gridPixel(
                    plan, descriptors, thumbnails, layout, validated, x, y
                )
            }
        }
        return PixelImage(patchWidth, patchHeight, pixels)
    }

    private fun gridPixel(
        plan: MosaicPlan,
        descriptors: List<TileDescriptor>,
        thumbnails: List<PixelImage>,
        layout: OutputLayout,
        config: MosaicConfig,
        x: Int,
        y: Int
    ): Int {
        val centerCrop = config.tileFit == TileFit.CENTER_CROP
        if (plan.anchors.isNotEmpty()) {
            return anchoredPixel(plan, descriptors, thumbnails, layout, config, x, y, centerCrop)
        }
        val cellWidth = layout.cellWidth
        val cellHeight = layout.cellHeight
        val cellRow = (y / cellHeight).coerceIn(0, layout.rows - 1)
        val shift = if (layout.staggered && cellRow % 2 == 1) cellWidth / 2 else 0
        var localX = x - shift
        if (localX < 0) localX += layout.width
        val cellColumn = (localX / cellWidth).coerceIn(0, layout.columns - 1)
        val cell = cellRow * layout.columns + cellColumn
        val code = grid.orientationCode(plan, cell)
        return grid.pixelAt(
            plan, descriptors, thumbnails, cell, localX % cellWidth, y % cellHeight,
            cellWidth, cellHeight, centerCrop, config.renderMode, config.colorMatchWeight,
            code and 3, code >= 4
        )
    }

    private fun anchoredPixel(
        plan: MosaicPlan,
        descriptors: List<TileDescriptor>,
        thumbnails: List<PixelImage>,
        layout: OutputLayout,
        config: MosaicConfig,
        x: Int,
        y: Int,
        centerCrop: Boolean
    ): Int {
        val cellColumn = (x / layout.cellWidth).coerceAtMost(layout.columns - 1)
        val cellRow = (y / layout.cellHeight).coerceAtMost(layout.rows - 1)
        val unit = cellRow * layout.columns + cellColumn
        val anchor = plan.anchors[unit]
        val anchorColumn = anchor % layout.columns
        val anchorRow = anchor / layout.columns
        val placeWidth = plan.spanX[anchor].toInt() * layout.cellWidth
        val placeHeight = plan.spanY[anchor].toInt() * layout.cellHeight
        val code = grid.orientationCode(plan, anchor)
        return grid.pixelAt(
            plan, descriptors, thumbnails, anchor,
            x - anchorColumn * layout.cellWidth, y - anchorRow * layout.cellHeight,
            placeWidth, placeHeight, centerCrop, config.renderMode, config.colorMatchWeight,
            code and 3, code >= 4
        )
    }

    private suspend fun paintRows(
        plan: MosaicPlan,
        descriptors: List<TileDescriptor>,
        thumbnails: List<PixelImage>,
        layout: OutputLayout,
        config: MosaicConfig,
        target: PixelImage?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        stack: HybridStack
    ): PixelImage {
        val width = layout.width
        val height = layout.height
        val sprites = collage.sprites(plan, descriptors, thumbnails, width, height, target).filter { sprite ->
            sprite.draw.bottom >= top && sprite.draw.top <= bottom && sprite.draw.right >= left && sprite.draw.left <= right
        }
        val field = lowFrequencyField(target)
        val mean = meanColor(target)
        val useTarget = config.collage.background == CollageBackground.TARGET && target != null
        val row = IntArray(width)
        val patchWidth = right - left
        val pixels = IntArray(patchWidth * (bottom - top))
        val gridOnTop = stack == HybridStack.COLLAGE_UNDER
        val collageOnTop = stack == HybridStack.GRID_UNDER
        for (y in top until bottom) {
            if ((y - top) % 8 == 0) coroutineContext.ensureActive()
            if (collageOnTop || gridOnTop) {
                paintHybridRow(row, y, plan, descriptors, thumbnails, layout, config, target, sprites, field, mean, useTarget, collageOnTop)
            } else {
                paintCollageRow(row, y, width, height, sprites, config, target, field, mean, useTarget)
            }
            System.arraycopy(row, left, pixels, (y - top) * patchWidth, patchWidth)
        }
        return PixelImage(patchWidth, bottom - top, pixels)
    }

    private fun paintCollageRow(
        row: IntArray,
        y: Int,
        width: Int,
        height: Int,
        sprites: List<CollageRenderer.Sprite>,
        config: MosaicConfig,
        target: PixelImage?,
        field: PixelImage?,
        mean: Int,
        useTarget: Boolean
    ) {
        collage.paintBackground(row, y, width, height, target, useTarget, mean)
        stampSprites(row, y, width, height, sprites, config, field)
    }

    private fun paintHybridRow(
        row: IntArray,
        y: Int,
        plan: MosaicPlan,
        descriptors: List<TileDescriptor>,
        thumbnails: List<PixelImage>,
        layout: OutputLayout,
        config: MosaicConfig,
        target: PixelImage?,
        sprites: List<CollageRenderer.Sprite>,
        field: PixelImage?,
        mean: Int,
        useTarget: Boolean,
        collageOnTop: Boolean
    ) {
        if (collageOnTop) {
            fillGridRow(row, y, plan, descriptors, thumbnails, layout, config)
            stampSprites(row, y, layout.width, layout.height, sprites, config, field)
            return
        }
        collage.paintBackground(row, y, layout.width, layout.height, target, useTarget, mean)
        stampSprites(row, y, layout.width, layout.height, sprites, config, field)
        overlayGrid(row, y, plan, descriptors, thumbnails, layout, config)
    }

    private fun stampSprites(
        row: IntArray,
        y: Int,
        width: Int,
        height: Int,
        sprites: List<CollageRenderer.Sprite>,
        config: MosaicConfig,
        field: PixelImage?
    ) {
        for (index in sprites.indices) {
            val sprite = sprites[index]
            if (sprite.placement.mask != null) {
                paintShapeRow(
                    row, y, width, height, sprite.source, sprite.base, sprite.descriptor, sprite.placement,
                    config, null, null, index, sprite.tone, field, sprite.outline, null
                )
                continue
            }
            if (config.collage.separatePieces) collage.paintShadow(row, y, sprite, width)
            if (y < sprite.draw.top || y > sprite.draw.bottom) continue
            collage.paintSprite(row, y, sprite, config, null, width, null, null, index)
        }
    }

    private fun fillGridRow(
        row: IntArray,
        y: Int,
        plan: MosaicPlan,
        descriptors: List<TileDescriptor>,
        thumbnails: List<PixelImage>,
        layout: OutputLayout,
        config: MosaicConfig
    ) {
        val validated = config.validated()
        for (x in row.indices) row[x] = gridPixel(plan, descriptors, thumbnails, layout, validated, x, y)
    }

    private fun overlayGrid(
        row: IntArray,
        y: Int,
        plan: MosaicPlan,
        descriptors: List<TileDescriptor>,
        thumbnails: List<PixelImage>,
        layout: OutputLayout,
        config: MosaicConfig
    ) {
        val grout = (minOf(layout.cellWidth, layout.cellHeight) / 10).coerceAtLeast(1)
        if (y % layout.cellHeight < grout) return
        val validated = config.validated()
        for (x in row.indices) {
            if (x % layout.cellWidth < grout) continue
            row[x] = gridPixel(plan, descriptors, thumbnails, layout, validated, x, y)
        }
    }

    private fun meanColor(image: PixelImage?): Int {
        if (image == null) return argb(24, 24, 28)
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
        val samples = count.coerceAtLeast(1)
        return argb((red / samples).toInt(), (green / samples).toInt(), (blue / samples).toInt())
    }
}
