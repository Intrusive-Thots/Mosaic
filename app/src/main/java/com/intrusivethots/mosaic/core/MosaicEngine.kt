package com.intrusivethots.mosaic.core

import android.graphics.Bitmap
import com.intrusivethots.mosaic.engine.config.AspectRatioPreset
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.MosaicStyle
import com.intrusivethots.mosaic.engine.coord.GenerationCoordinator
import com.intrusivethots.mosaic.engine.image.centerAspectRect
import com.intrusivethots.mosaic.engine.tile.MemoryTileSource
import com.intrusivethots.mosaic.engine.tile.TileAnalyzer
import com.intrusivethots.mosaic.engine.tile.TileDescriptor
import com.intrusivethots.mosaic.engine.tile.toKey

/**
 * Android entry point kept for existing call sites. Generation itself runs in [GenerationCoordinator].
 * Returned bitmaps stay under the in-memory pixel cap; larger renders go through a [com.intrusivethots.mosaic.engine.render.RowSink].
 */
class MosaicEngine(
    private val coordinator: GenerationCoordinator = GenerationCoordinator()
) {
    suspend fun analyzeTileImages(
        bitmaps: List<Bitmap>,
        onProgress: (Float) -> Unit = {}
    ): List<TileAnalysis> {
        val analyzer = TileAnalyzer()
        return bitmaps.mapIndexed { index, bitmap ->
            val image = bitmap.toPixelImage()
            val source = MemoryTileSource(image, "bitmap-$index")
            val features = com.intrusivethots.mosaic.engine.tile.FeatureVector()
            analyzer.sample(image, 0, 0, image.width, image.height, wrapX = false, into = features)
            val descriptor = analyzer.describe(
                key = source.identity.toKey(analyzer.algorithmVersion),
                sourceWidth = bitmap.width,
                sourceHeight = bitmap.height,
                image = image
            )
            onProgress((index + 1f) / bitmaps.size.coerceAtLeast(1))
            TileAnalysis(
                originalIndex = index,
                avgRed = features.meanRed,
                avgGreen = features.meanGreen,
                avgBlue = features.meanBlue,
                thumbnail = scaleToLongEdge(bitmap, 64),
                descriptor = descriptor
            )
        }
    }

    fun cropToAspectRatio(source: Bitmap, preset: AspectRatioPreset): Bitmap {
        val rect = centerAspectRect(source.width, source.height, preset.widthRatio, preset.heightRatio)
        if (rect.x == 0 && rect.y == 0 && rect.width == source.width && rect.height == source.height) return source
        return Bitmap.createBitmap(source, rect.x, rect.y, rect.width, rect.height)
    }

    suspend fun generateMosaic(
        targetImage: Bitmap,
        tiles: List<TileAnalysis>,
        isPreview: Boolean = false,
        config: MosaicConfig = MosaicConfig(),
        onProgress: (Float) -> Unit = {}
    ): Bitmap {
        val sources = tiles.map { tile ->
            MemoryTileSource(tile.thumbnail.toPixelImage(), "analysis-${tile.originalIndex}")
        }
        val result = coordinator.generate(
            target = targetImage.toPixelImage(),
            tiles = sources,
            config = config,
            preview = isPreview,
            onProgress = { progress -> onProgress(progress.fraction) }
        )
        return result.image?.toBitmap()
            ?: error("This mosaic is too large to return as one bitmap. Render it to a file instead.")
    }
}

data class TileAnalysis(
    val originalIndex: Int,
    val avgRed: Int,
    val avgGreen: Int,
    val avgBlue: Int,
    val thumbnail: Bitmap,
    val descriptor: TileDescriptor? = null
)

typealias AspectRatioPreset = com.intrusivethots.mosaic.engine.config.AspectRatioPreset
typealias MosaicStyle = com.intrusivethots.mosaic.engine.config.MosaicStyle
typealias MosaicConfig = com.intrusivethots.mosaic.engine.config.MosaicConfig
