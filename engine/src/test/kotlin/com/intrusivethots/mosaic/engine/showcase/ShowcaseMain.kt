package com.intrusivethots.mosaic.engine.showcase

import com.intrusivethots.mosaic.engine.image.downscaleLongEdge
import com.intrusivethots.mosaic.engine.match.CartoonFaceFinder
import kotlinx.coroutines.runBlocking
import java.io.File

fun main(args: Array<String>) = runBlocking {
    val theme = args.firstOrNull()?.ifBlank { null } ?: "naruto"
    if (theme == "probe") {
        probeFaces(File(args.getOrNull(1) ?: error("probe needs a directory")))
        return@runBlocking
    }
    val (library, prefix, message) = when (theme) {
        "naruto" -> Triple(
            loadShowcase(File("showcase-sources"), tileEdge = 320),
            "",
            "Wrote the Team 7 showcase into docs/images"
        )
        "rick" -> Triple(
            loadShowcase(File("showcase-sources/rick"), tileEdge = 320),
            "rick-",
            "Wrote the Rick and Morty showcase into docs/images"
        )
        "koth" -> Triple(
            loadCrossover(File("showcase-sources/koth"), File("showcase-sources/pokemon"), tileEdge = 320),
            "koth-",
            "Wrote the King of the Hill showcase from Pokemon pieces"
        )
        "pokemon" -> Triple(
            loadCrossover(File("showcase-sources/pokemon"), File("showcase-sources/koth"), tileEdge = 320),
            "pokemon-",
            "Wrote the Pokemon showcase from King of the Hill pieces"
        )
        else -> error("Unknown showcase theme $theme")
    }
    println(
        "Showcase library: target ${library.target.width}×${library.target.height}, " +
            "${library.cutouts.size} cutouts, ${library.photos.size} photos"
    )
    writeShowcase(library, File("docs/images"), prefix)
    println(message)
}

private fun probeFaces(directory: File) {
    val files = directory.listFiles { file -> file.isFile && file.name[0].isDigit() && !file.name.startsWith("000-") }
        ?.sortedBy { it.name }
        .orEmpty()
    var detected = 0
    for (file in files) {
        val image = readImage(file).downscaleLongEdge(320)
        val faces = CartoonFaceFinder.find(image).size
        if (faces > 0) detected++ else println("NO FACE ${file.name}")
    }
    println("PROBE ${directory.name} faces detected $detected excluded ${files.size - detected} of ${files.size}")
}
