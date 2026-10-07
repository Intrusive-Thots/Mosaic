package com.intrusivethots.mosaic.engine.showcase

import kotlinx.coroutines.runBlocking
import java.io.File

fun main(args: Array<String>) = runBlocking {
    val theme = args.firstOrNull()?.ifBlank { null } ?: "naruto"
    val rick = theme == "rick"
    require(theme == "naruto" || rick) { "Unknown showcase theme $theme" }
    val directory = if (rick) File("showcase-sources/rick") else File("showcase-sources")
    val prefix = if (rick) "rick-" else ""
    val library = loadShowcase(directory, tileEdge = 320)
    println(
        "Showcase library: target ${library.target.width}×${library.target.height}, " +
            "${library.cutouts.size} cutouts, ${library.photos.size} photos"
    )
    writeShowcase(library, File("docs/images"), prefix)
    println(if (rick) "Wrote the Rick and Morty showcase into docs/images" else "Wrote the Team 7 showcase into docs/images")
}
