package com.intrusivethots.mosaic.engine.match

import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.index.TileIndex
import com.intrusivethots.mosaic.engine.tile.TileDescriptor

/**
 * Builds a cutout collage by cutting the target into shapes and filling those shapes
 * from the library. Grid matching does not come through here.
 */
class CollagePlacer {
    private val shapes = ShapedCollage()

    suspend fun place(
        target: PixelImage,
        descriptors: List<TileDescriptor>,
        index: TileIndex,
        config: MosaicConfig,
        tileTokens: List<String>,
        thumbnails: List<PixelImage> = emptyList(),
        onSnapshot: suspend (List<CutoutPlacement>, String) -> Unit = { _, _ -> },
        onProgress: (Float, String) -> Unit = { _, _ -> },
        knownFaces: List<List<FaceBox>>? = null
    ): Pair<MosaicPlan, MatchStats> {
        val sources = if (thumbnails.size == descriptors.size) thumbnails else descriptors.map { flatThumb(it) }
        return shapes.place(target, descriptors, sources, index, config, onSnapshot, onProgress, knownFaces).also {
            it.second.solidCells = 0
            if (tileTokens.isEmpty()) return@also
        }
    }

    suspend fun replaceRegion(
        target: PixelImage,
        descriptors: List<TileDescriptor>,
        index: TileIndex,
        config: MosaicConfig,
        tileTokens: List<String>,
        existing: List<CutoutPlacement>,
        centerX: Float,
        centerY: Float,
        radius: Float,
        thumbnails: List<PixelImage> = emptyList()
    ): MosaicPlan {
        val sources = if (thumbnails.size == descriptors.size) thumbnails else descriptors.map { flatThumb(it) }
        if (tileTokens.isEmpty() && existing.isEmpty()) {
            return shapes.place(target, descriptors, sources, index, config, { _, _ -> }, { _, _ -> }).first
        }
        return shapes.replace(
            target, descriptors, sources, index, config, existing, centerX, centerY, radius
        )
    }
}

private fun flatThumb(descriptor: TileDescriptor): PixelImage {
    val color = com.intrusivethots.mosaic.engine.color.OkLab.toArgb(
        com.intrusivethots.mosaic.engine.color.OkLab.Lab(descriptor.labL, descriptor.labA, descriptor.labB)
    )
    return PixelImage.filled(2, 2, color)
}
