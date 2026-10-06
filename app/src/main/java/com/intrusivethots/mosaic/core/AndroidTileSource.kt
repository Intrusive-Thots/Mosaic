package com.intrusivethots.mosaic.core

import android.graphics.Bitmap
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.image.downscaleLongEdge
import com.intrusivethots.mosaic.engine.tile.TileIdentity
import com.intrusivethots.mosaic.engine.tile.TileSource

/** A tile whose pixels are already a thumbnail. The source bitmap is not recycled here. */
class BitmapTileSource(
    override val identity: TileIdentity,
    private val bitmap: Bitmap
) : TileSource {
    override fun loadThumbnail(maxEdge: Int): PixelImage {
        val scaled = scaleToLongEdge(bitmap, maxEdge)
        val image = scaled.toPixelImage().downscaleLongEdge(maxEdge)
        if (scaled !== bitmap) scaled.recycle()
        return image
    }
}
