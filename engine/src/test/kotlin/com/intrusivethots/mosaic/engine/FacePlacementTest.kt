package com.intrusivethots.mosaic.engine

import com.intrusivethots.mosaic.engine.color.argb
import com.intrusivethots.mosaic.engine.config.OutputLayout
import com.intrusivethots.mosaic.engine.coord.GenerationCoordinator
import com.intrusivethots.mosaic.engine.match.CartoonFaceFinder
import com.intrusivethots.mosaic.engine.match.FaceBox
import com.intrusivethots.mosaic.engine.match.ownedFaceFraction
import com.intrusivethots.mosaic.engine.render.MemoryRowSink
import com.intrusivethots.mosaic.engine.render.MosaicRenderer
import com.intrusivethots.mosaic.engine.tile.MemoryTileSource
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class FacePlacementTest {
    @Test
    fun aFlatTileIsNotAFace() {
        val flat = solid(48, 48, argb(180, 140, 90))
        assertTrue(CartoonFaceFinder.find(flat).isEmpty())
        val shade = IntArray(40 * 40) { index ->
            val x = index % 40
            argb(40 + x * 4, 30 + x * 3, 24)
        }
        assertTrue(CartoonFaceFinder.find(com.intrusivethots.mosaic.engine.image.PixelImage(40, 40, shade)).isEmpty())
    }

    @Test
    fun aStampedCutoutContainsAFace() {
        assertTrue(CartoonFaceFinder.find(shapedCutout(1, 8, 36)).isNotEmpty())
        assertTrue(CartoonFaceFinder.find(organicCutout(2, 8, 22)).isNotEmpty(), "22px organic")
        assertTrue(CartoonFaceFinder.find(organicCutout(3, 8, 16)).isNotEmpty(), "16px organic")
    }

    @Test
    fun everyPlacedPieceShowsAFace() = runBlocking {
        val tiles = (0 until 8).map { index ->
            MemoryTileSource(shapedCutout(index, 8, 28), "face-$index")
        }
        val config = collageConfig(pieceCount = 12, seed = 4)
        val target = scene(72, 48)
        val result = GenerationCoordinator().generate(target, tiles, config, preview = true)
        val placements = result.plan.placements
        assertTrue(placements.isNotEmpty(), "no pieces were placed")
        placements.forEach { piece ->
            assertTrue(piece.faceRight > piece.faceLeft && piece.faceBottom > piece.faceTop, "piece has no face box")
        }
        val image = result.image ?: error("missing collage")
        val owners = IntArray(image.width * image.height) { -1 }
        val thumbs = tiles.map { it.loadThumbnail(GenerationCoordinator.COLLAGE_RENDER_EDGE) }
        MosaicRenderer().render(
            result.plan,
            result.descriptors,
            thumbs,
            OutputLayout(1, 1, image.width, image.height, false),
            config,
            MemoryRowSink(image.width, image.height),
            target,
            owners = owners
        )
        placements.forEachIndexed { index, piece ->
            val face = FaceBox(piece.faceLeft, piece.faceTop, piece.faceRight, piece.faceBottom)
            val visible = ownedFaceFraction(owners, image.width, image.height, index, face)
            assertTrue(visible >= 0.55f, "piece $index visible face $visible")
        }
    }

    @Test
    fun showcaseLibrariesDetectCartoonFacesWhenPresent() {
        val naruto = libraryDir("showcase-sources") ?: return
        val rick = libraryDir("showcase-sources/rick") ?: return
        assertCoverage("naruto", naruto)
        assertCoverage("rick", rick)
    }

    private fun assertCoverage(name: String, directory: File) {
        val tiles = directory.listFiles { file -> file.isFile && file.name.contains("-tile.") }.orEmpty()
        assertTrue(tiles.size >= 8, "$name library is too small")
        var detected = 0
        for (file in tiles) {
            val image = com.intrusivethots.mosaic.engine.showcase.readImage(file)
            if (CartoonFaceFinder.find(image).isNotEmpty()) detected++
        }
        val fraction = detected.toFloat() / tiles.size
        println("SHOWCASE $name faces detected $detected excluded ${tiles.size - detected} of ${tiles.size}")
        assertTrue(fraction > 0.40f, "$name face coverage $detected of ${tiles.size}")
    }

    private fun libraryDir(relative: String): File? {
        val candidates = listOf(File(relative), File("../$relative"))
        return candidates.firstOrNull { dir ->
            dir.isDirectory && dir.listFiles().orEmpty().any { it.name.startsWith("001-") }
        }
    }
}
