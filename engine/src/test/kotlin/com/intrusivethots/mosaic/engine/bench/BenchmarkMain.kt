package com.intrusivethots.mosaic.engine.bench

import com.intrusivethots.mosaic.engine.color.argb
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.coord.GenerationCoordinator
import com.intrusivethots.mosaic.engine.gradient
import com.intrusivethots.mosaic.engine.hueTile
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.index.TileIndex
import com.intrusivethots.mosaic.engine.legacy.LegacyRgbMatcher
import com.intrusivethots.mosaic.engine.match.TileMatcher
import com.intrusivethots.mosaic.engine.render.MosaicRenderer
import com.intrusivethots.mosaic.engine.render.RowSink
import com.intrusivethots.mosaic.engine.render.StreamingPngWriter
import com.intrusivethots.mosaic.engine.scene
import com.intrusivethots.mosaic.engine.tile.MemoryTileSource
import com.intrusivethots.mosaic.engine.tile.TileAnalyzer
import com.intrusivethots.mosaic.engine.tile.TileDescriptor
import com.intrusivethots.mosaic.engine.tile.toKey
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.measureNanoTime

data class BenchCase(val tiles: Int, val columns: Int, val rows: Int)

data class BenchResult(
    val tiles: Int,
    val columns: Int,
    val rows: Int,
    val loadMs: Double,
    val analyzeMs: Double,
    val indexMs: Double,
    val matchMs: Double,
    val renderMs: Double,
    val totalMs: Double,
    val probes: Long,
    val comparisons: Long,
    val heapMb: Double
)

fun main() = runBlocking {
    val cases = listOf(
        BenchCase(100, 40, 40),
        BenchCase(500, 60, 60),
        BenchCase(1_000, 80, 80),
        BenchCase(5_000, 120, 120)
    )
    val results = cases.map { runCase(it) }
    val report = renderReport(results)
    val output = File("engine/build/reports/benchmarks/results.md")
    output.parentFile.mkdirs()
    output.writeText(report)
    println(report)
    writeComparison()
}

private suspend fun runCase(case: BenchCase): BenchResult {
    val runtime = Runtime.getRuntime()
    System.gc()
    val before = runtime.totalMemory() - runtime.freeMemory()
    val config = MosaicConfig(
        gridColumns = case.columns,
        gridRows = case.rows,
        linkAspectToGrid = false,
        candidateCount = 16,
        descriptorMaxEdge = 16,
        maxRepetitionDistance = 3,
        renderMode = RenderMode.COLOR_CORRECTED,
        colorMatchWeight = 0.65f,
        previewCellPixels = 12,
        randomSeed = 1
    )
    lateinit var sources: List<MemoryTileSource>
    val loadMs = measureNanoTime {
        sources = List(case.tiles) { index ->
            MemoryTileSource(hueTile(index, case.tiles, size = 16), "bench-$index")
        }
    }.ms()
    val analyzer = TileAnalyzer()
    val thumbs = ArrayList<PixelImage>(case.tiles)
    val descriptors = ArrayList<TileDescriptor>(case.tiles)
    val analyzeMs = measureNanoTime {
        sources.forEach { source ->
            val thumb = source.loadThumbnail(config.descriptorMaxEdge)
            thumbs += thumb
            val key = source.identity.toKey(analyzer.algorithmVersion)
            descriptors += analyzer.describe(key, source.identity.width, source.identity.height, thumb)
        }
    }.ms()
    lateinit var index: TileIndex
    val indexMs = measureNanoTime {
        index = TileIndex.build(descriptors)
    }.ms()
    val target = gradient(case.columns * 8, case.rows * 8)
    val matcher = TileMatcher(analyzer)
    lateinit var matchResult: Pair<com.intrusivethots.mosaic.engine.match.MosaicPlan, com.intrusivethots.mosaic.engine.match.MatchStats>
    val matchMs = measureNanoTime {
        matchResult = matcher.match(target, descriptors, index, config, descriptors.map { it.key.token() })
    }.ms()
    val renderer = MosaicRenderer()
    val layout = com.intrusivethots.mosaic.engine.config.planOutput(
        target.width,
        target.height,
        config,
        preview = true
    )
    val renderMs = measureNanoTime {
        renderer.render(matchResult.first, descriptors, thumbs, layout, config, RowSink { _, _ -> })
    }.ms()
    System.gc()
    val after = runtime.totalMemory() - runtime.freeMemory()
    val heapMb = (after - before).coerceAtLeast(0) / (1024.0 * 1024.0)
    return BenchResult(
        tiles = case.tiles,
        columns = case.columns,
        rows = case.rows,
        loadMs = loadMs,
        analyzeMs = analyzeMs,
        indexMs = indexMs,
        matchMs = matchMs,
        renderMs = renderMs,
        totalMs = loadMs + analyzeMs + indexMs + matchMs + renderMs,
        probes = matchResult.second.probes,
        comparisons = matchResult.second.comparisons,
        heapMb = heapMb
    ).also { result ->
        val naive = case.columns.toLong() * case.rows * case.tiles
        check(result.probes < naive / 5) {
            "Candidate probes ${result.probes} were not smaller than a full scan ($naive)."
        }
        println(
            "bench ${case.tiles}/${case.columns}x${case.rows}: " +
                "total ${"%.1f".format(result.totalMs)} ms, probes ${result.probes} vs $naive"
        )
    }
}

private fun renderReport(results: List<BenchResult>): String = buildString {
    appendLine("# Engine benchmarks")
    appendLine()
    appendLine("JVM run with synthetic tiles (unique hues, 16 px thumbnails, 12 px render cells).")
    appendLine("Heap is the change in `totalMemory - freeMemory` around the case and is only an estimate.")
    appendLine()
    appendLine("| Tiles | Grid | Load ms | Analyze ms | Index ms | Match ms | Render ms | Total ms | Probes | Full-library comparisons | Heap MB |")
    appendLine("| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
    results.forEach { result ->
        val naive = result.columns.toLong() * result.rows * result.tiles
        append("| ${result.tiles} | ${result.columns}×${result.rows} | ")
        append("%.1f".format(result.loadMs)).append(" | ")
        append("%.1f".format(result.analyzeMs)).append(" | ")
        append("%.1f".format(result.indexMs)).append(" | ")
        append("%.1f".format(result.matchMs)).append(" | ")
        append("%.1f".format(result.renderMs)).append(" | ")
        append("%.1f".format(result.totalMs)).append(" | ")
        append("${result.probes} | $naive | ")
        append("%.1f".format(result.heapMb)).appendLine(" |")
    }
    appendLine()
    appendLine("Probes are candidate color checks inside OKLab bins. The full-library column is cells × tiles, which is what the 1.x matcher did.")
}

private suspend fun writeComparison() {
    val target = scene(80, 80)
    val images = (0 until 24).map { hueTile(it, 24, size = 20) }
    val legacy = LegacyRgbMatcher.render(
        target,
        images.map { LegacyRgbMatcher.analyze(it) },
        columns = 20,
        rows = 20,
        blend = 0.8f
    )
    val modern = GenerationCoordinator().generate(
        target = target,
        tiles = images.mapIndexed { index, image -> MemoryTileSource(image, "compare-$index") },
        config = MosaicConfig(
            gridColumns = 20,
            gridRows = 20,
            linkAspectToGrid = false,
            candidateCount = 12,
            descriptorMaxEdge = 20,
            maxRepetitionDistance = 2,
            renderMode = RenderMode.COLOR_CORRECTED,
            colorMatchWeight = 0.7f,
            randomSeed = 5,
            previewCellPixels = 4
        ),
        preview = true
    ).image ?: error("Comparison render failed")
    val gap = 8
    val width = legacy.width + gap + modern.width
    val height = maxOf(legacy.height, modern.height)
    val pixels = IntArray(width * height) { argb(20, 16, 28) }
    val combined = PixelImage(width, height, pixels)
    blit(combined, legacy, 0, 0)
    blit(combined, modern, legacy.width + gap, 0)
    listOf(
        File("docs/images/matching-comparison.png"),
        File("/opt/cursor/artifacts/matching-comparison.png")
    ).forEach { file ->
        file.parentFile?.mkdirs()
        file.outputStream().use { stream ->
            StreamingPngWriter(stream, combined.width, combined.height).use { writer ->
                val row = IntArray(combined.width)
                for (y in 0 until combined.height) {
                    for (x in 0 until combined.width) row[x] = combined.pixel(x, y)
                    writer.writeRow(y, row)
                }
            }
        }
    }
}

private fun blit(destination: PixelImage, source: PixelImage, originX: Int, originY: Int) {
    for (y in 0 until source.height) {
        val destY = originY + y
        if (destY !in 0 until destination.height) continue
        val row = destY * destination.width
        for (x in 0 until source.width) {
            val destX = originX + x
            if (destX !in 0 until destination.width) continue
            destination.pixels[row + destX] = source.pixel(x, y)
        }
    }
}

private fun Long.ms(): Double = this / 1_000_000.0
