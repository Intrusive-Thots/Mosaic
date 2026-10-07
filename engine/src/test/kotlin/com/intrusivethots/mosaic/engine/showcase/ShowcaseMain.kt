package com.intrusivethots.mosaic.engine.showcase

import kotlinx.coroutines.runBlocking
import java.io.File

fun main() = runBlocking {
    val library = loadShowcase(File("showcase-sources"), tileEdge = 192)
    println(
        "Showcase library: target ${library.target.width}×${library.target.height}, " +
            "${library.cutouts.size} tiles"
    )
    writeShowcase(library, File("docs/images"))
    println("Wrote the Team 7 showcase into docs/images")
}
