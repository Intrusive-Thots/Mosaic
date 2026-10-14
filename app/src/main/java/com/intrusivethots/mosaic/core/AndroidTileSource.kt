package com.intrusivethots.mosaic.core

import android.graphics.Bitmap
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.image.downscaleLongEdge
import com.intrusivethots.mosaic.engine.tile.TileIdentity
import com.intrusivethots.mosaic.engine.tile.TileSource

/**
 * A tile whose pixels are already a thumbnail.
 * [ownsBitmap] is true when this source may recycle [bitmap] after the pixels are copied.
 * Stickers that the screen still shows pass false.
 */
class BitmapTileSource(
    override val identity: TileIdentity,
    private val bitmap: Bitmap,
    private val ownsBitmap: Boolean = false
) : TileSource {
    override fun loadThumbnail(maxEdge: Int): PixelImage {
        val scaled = scaleToLongEdge(bitmap, maxEdge)
        val image = scaled.toPixelImage().downscaleLongEdge(maxEdge)
        if (scaled !== bitmap) scaled.recycle()
        return image
    }

    override fun release() {
        if (ownsBitmap && !bitmap.isRecycled) bitmap.recycle()
    }
}
