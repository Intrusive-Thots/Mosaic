package com.intrusivethots.mosaic.engine.tile

import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.image.downscaleLongEdge

interface TileSource {
    val identity: TileIdentity
    fun loadThumbnail(maxEdge: Int): PixelImage
}

class MemoryTileSource(
    private val image: PixelImage,
    id: String,
    modifiedTimeMs: Long = 0L,
    byteSize: Long = image.width.toLong() * image.height.toLong() * 4L
) : TileSource {
    override val identity: TileIdentity = TileIdentity(
        uri = id,
        width = image.width,
        height = image.height,
        byteSize = byteSize,
        modifiedTimeMs = modifiedTimeMs
    )

    override fun loadThumbnail(maxEdge: Int): PixelImage = image.downscaleLongEdge(maxEdge)
}
