package com.intrusivethots.mosaic.engine.bench

import com.intrusivethots.mosaic.engine.COMPARISON_COLUMNS
import com.intrusivethots.mosaic.engine.COMPARISON_ROWS
import com.intrusivethots.mosaic.engine.COMPARISON_SEED
import com.intrusivethots.mosaic.engine.compareMatchers
import com.intrusivethots.mosaic.engine.collageConfig
import com.intrusivethots.mosaic.engine.config.CellAspect
import com.intrusivethots.mosaic.engine.config.CollageBackground
import com.intrusivethots.mosaic.engine.config.CollageSettings
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.MosaicKind
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.config.RotationMode
import com.intrusivethots.mosaic.engine.config.planCollageOutput
import com.intrusivethots.mosaic.engine.config.planGrid
import com.intrusivethots.mosaic.engine.match.CollagePlacer
import com.intrusivethots.mosaic.engine.portrait
import com.intrusivethots.mosaic.engine.quality.luminanceSsim
import com.intrusivethots.mosaic.engine.quality.maskedLuminanceSsim
import com.intrusivethots.mosaic.engine.quality.maskedMeanDeltaE
import com.intrusivethots.mosaic.engine.quality.meanCellDeltaE
import com.intrusivethots.mosaic.engine.organicCutout
import com.intrusivethots.mosaic.engine.writePng
import com.intrusivethots.mosaic.engine.gradient
import com.intrusivethots.mosaic.engine.hueTile
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.index.TileIndex
import com.intrusivethots.mosaic.engine.match.TileMatcher
import com.intrusivethots.mosaic.engine.config.OutputLayout
import com.intrusivethots.mosaic.engine.render.MemoryRowSink
import com.intrusivethots.mosaic.engine.render.MosaicRenderer
import com.intrusivethots.mosaic.engine.render.RowSink
import com.intrusivethots.mosaic.engine.tile.MemoryTileSource
import com.intrusivethots.mosaic.engine.writeRowOf
import com.intrusivethots.mosaic.engine.writeSideBySide
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
    val heapMb: Double,
    val note: String = ""
)

fun main() = runBlocking {
    val cases = listOf(
        BenchCase(100, 40, 40),
        BenchCase(500, 60, 60),
        BenchCase(1_000, 80, 80),
        BenchCase(5_000, 120, 120)
    )
    runCase(cases.first(), report = false)
    val results = cases.map { runCase(it) }
    val shape = runShapeCase()
    runCollageCase(40, 24)
    val collage = listOf(200 to 160, 600 to 280).map { (tiles, pieces) -> runCollageCase(tiles, pieces) }
    val report = renderReport(results, shape, collage)
    listOf(
        File("engine/build/reports/benchmarks/results.md"),
        File("docs/benchmarks/results.md")
    ).forEach { output ->
        output.parentFile.mkdirs()
        output.writeText(report)
    }
    println(report)
    writeComparison()
    writeCollageSample()
}

private suspend fun runCase(
    case: BenchCase,
    report: Boolean = true,
    config: MosaicConfig = benchConfig(case.columns, case.rows),
    targetImage: PixelImage? = null,
    loadSources: (() -> List<MemoryTileSource>)? = null
): BenchResult {
    val runtime = Runtime.getRuntime()
    System.gc()
    val before = runtime.totalMemory() - runtime.freeMemory()
    lateinit var sources: List<MemoryTileSource>
    val loadMs = measureNanoTime {
        sources = loadSources?.invoke() ?: List(case.tiles) { index ->
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
    val target = targetImage ?: gradient(case.columns * 8, case.rows * 8)
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
        val cells = case.columns.toLong() * case.rows
        val naive = cells * case.tiles
        val perCell = result.probes.toDouble() / cells.toDouble()
        check(perCell < case.tiles) {
            "Average probes per cell ($perCell) reached the full library (${case.tiles})."
        }
        if (case.tiles >= 500) {
            check(result.probes < naive / 4) {
                "Candidate probes ${result.probes} were not well below a full scan ($naive)."
            }
        }
        if (report) {
            println(
                "bench ${case.tiles}/${case.columns}x${case.rows}: " +
                    "total ${"%.1f".format(result.totalMs)} ms, probes ${result.probes} vs $naive"
            )
        }
    }
}

private suspend fun runShapeCase(): BenchResult {
    val columns = 40
    val probe = planGrid(
        320,
        180,
        MosaicConfig(gridColumns = columns, linkAspectToGrid = true, cellAspect = CellAspect.LANDSCAPE_16_9)
    )
    val config = MosaicConfig(
        gridColumns = columns,
        gridRows = probe.rows,
        linkAspectToGrid = false,
        cellAspect = CellAspect.LANDSCAPE_16_9,
        rotationMode = RotationMode.ORIENTATION,
        candidateCount = 16,
        descriptorMaxEdge = 16,
        maxRepetitionDistance = 3,
        renderMode = RenderMode.COLOR_CORRECTED,
        colorMatchWeight = 0.65f,
        previewCellPixels = 12,
        randomSeed = 1
    )
    return runCase(
        BenchCase(500, probe.columns, probe.rows),
        config = config,
        targetImage = gradient(320, 180),
        loadSources = {
            val wide = List(250) { index ->
                MemoryTileSource(wideTile(hueTile(index, 500, size = 16)), "wide-$index")
            }
            val tall = List(250) { index -> MemoryTileSource(tallTile(index), "tall-$index") }
            wide + tall
        }
    )
}

private fun benchConfig(columns: Int, rows: Int) = MosaicConfig(
    gridColumns = columns,
    gridRows = rows,
    linkAspectToGrid = false,
    candidateCount = 16,
    descriptorMaxEdge = 16,
    maxRepetitionDistance = 3,
    renderMode = RenderMode.COLOR_CORRECTED,
    colorMatchWeight = 0.65f,
    previewCellPixels = 12,
    randomSeed = 1
)

private fun wideTile(square: PixelImage): PixelImage {
    val pixels = IntArray(square.width * 2 * square.height)
    for (y in 0 until square.height) {
        for (x in 0 until square.width * 2) {
            pixels[y * square.width * 2 + x] = square.pixels[(y * square.width) + (x / 2)]
        }
    }
    return PixelImage(square.width * 2, square.height, pixels)
}

private fun tallTile(index: Int): PixelImage {
    val square = hueTile(index + 250, 500, size = 16)
    val pixels = IntArray(square.width * square.height * 2)
    for (y in 0 until square.height * 2) {
        for (x in 0 until square.width) {
            pixels[y * square.width + x] = square.pixels[(y / 2) * square.width + x]
        }
    }
    return PixelImage(square.width, square.height * 2, pixels)
}

private suspend fun runCollageCase(tileCount: Int, pieceCount: Int): BenchResult {
    val config = collageConfig(pieceCount, seed = 1).copy(
        collage = CollageSettings(
            pieceCount = pieceCount,
            minScale = 0.04f,
            maxScale = 0.18f,
            rotationRangeDegrees = 18f,
            overlap = 0.4f,
            coverageGoal = 0.99f,
            background = CollageBackground.MEAN_COLOR,
            shapeWeight = 0.3f
        ),
        descriptorMaxEdge = 16,
        candidateCount = 12,
        mosaicKind = MosaicKind.COLLAGE,
        renderMode = RenderMode.ORIGINAL,
        colorMatchWeight = 0f
    )
    val runtime = Runtime.getRuntime()
    System.gc()
    val before = runtime.totalMemory() - runtime.freeMemory()
    val sources = List(tileCount) { index -> MemoryTileSource(organicCutout(index, tileCount, 28), "cut-$index") }
    val analyzer = TileAnalyzer()
    val thumbs = ArrayList<PixelImage>(tileCount)
    val descriptors = ArrayList<TileDescriptor>(tileCount)
    val analyzeMs = measureNanoTime {
        sources.forEach { source ->
            val thumb = source.loadThumbnail(16)
            thumbs += thumb
            descriptors += analyzer.describe(source.identity.toKey(analyzer.algorithmVersion), source.identity.width, source.identity.height, thumb)
        }
    }.ms()
    lateinit var index: TileIndex
    val indexMs = measureNanoTime { index = TileIndex.build(descriptors) }.ms()
    val target = gradient(160, 100)
    val placer = CollagePlacer()
    lateinit var matched: Pair<com.intrusivethots.mosaic.engine.match.MosaicPlan, com.intrusivethots.mosaic.engine.match.MatchStats>
    val matchMs = measureNanoTime {
        matched = placer.place(target, descriptors, index, config, descriptors.map { it.key.token() })
    }.ms()
    val layout = planCollageOutput(target.width, target.height, config, preview = true)
    val renderMs = measureNanoTime {
        MosaicRenderer().render(matched.first, descriptors, thumbs, layout, config, RowSink { _, _ -> }, target = target)
    }.ms()
    System.gc()
    val after = runtime.totalMemory() - runtime.freeMemory()
    val naive = pieceCount.toLong() * tileCount * 12
    val result = BenchResult(
        tiles = tileCount,
        columns = pieceCount,
        rows = matched.first.placements.size,
        loadMs = 0.0,
        analyzeMs = analyzeMs,
        indexMs = indexMs,
        matchMs = matchMs,
        renderMs = renderMs,
        totalMs = analyzeMs + indexMs + matchMs + renderMs,
        probes = matched.second.probes,
        comparisons = matched.second.comparisons,
        heapMb = (after - before).coerceAtLeast(0) / (1024.0 * 1024.0),
        note = "coverage %.0f%%".format(matched.first.coverage * 100f)
    )
    check(result.probes < naive) { "Collage probes ${result.probes} were not below a full piece scan ($naive)." }
    println("collage $tileCount tiles / $pieceCount pieces: total ${"%.1f".format(result.totalMs)} ms, probes ${result.probes} vs $naive, ${result.note}")
    return result
}

private suspend fun writeCollageSample() {
    val target = portrait(280, 180)
    val count = 240
    val tiles = (0 until count).map { index -> MemoryTileSource(organicCutout(index, count, 36), "sample-$index") }
    val config = collageConfig(pieceCount = 320, seed = 4).copy(candidateCount = 12, descriptorMaxEdge = 28)
    val coordinator = com.intrusivethots.mosaic.engine.coord.GenerationCoordinator()
    val result = coordinator.generate(target, tiles, config, preview = false)
    val image = result.image ?: error("Sample collage produced no image.")
    val gridConfig = MosaicConfig(
        gridColumns = 28,
        gridRows = 18,
        linkAspectToGrid = false,
        renderMode = RenderMode.ORIGINAL,
        randomSeed = 4,
        descriptorMaxEdge = 28,
        candidateCount = 12,
        customOutputWidth = target.width,
        customOutputHeight = target.height,
        lockOutputAspect = true
    )
    val grid = coordinator.generate(target, tiles, gridConfig, preview = false)
    val gridImage = grid.image ?: error("Sample grid produced no image.")
    val files = listOf(File("docs/images/cutout-collage.png"), File("/opt/cursor/artifacts/cutout-collage.png"))
    writeRowOf(listOf(target, image, gridImage), files)
    writePng(image, listOf(File("docs/images/cutout-collage-output.png"), File("/opt/cursor/artifacts/cutout-collage-output.png")))
    val covered = BooleanArray(image.width * image.height)
    val thumbs = tiles.map { it.loadThumbnail(128) }
    MosaicRenderer().render(
        result.plan,
        result.descriptors,
        thumbs,
        OutputLayout(1, 1, image.width, image.height, false),
        config,
        MemoryRowSink(image.width, image.height),
        target,
        covered
    )
    val delta = meanCellDeltaE(image, target, 14, 9)
    val ssim = luminanceSsim(image, target)
    val maskedDelta = maskedMeanDeltaE(image, target, covered)
    val maskedSsim = maskedLuminanceSsim(image, target, covered)
    val painted = covered.count { it }.toFloat() / covered.size.toFloat()
    val note = buildString {
        appendLine()
        appendLine("Sample collage on the portrait scene, 240 organic cutouts, 320 requested, seed 4.")
        appendLine("${result.plan.placements.size} pieces were placed. The background is the target's mean color.")
        appendLine("docs/images/cutout-collage.png shows the target, the collage, and a grid mosaic of the same library.")
        appendLine("The previous sample painted 59% of pixels (masked ΔE 0.0989, masked SSIM 0.6873).")
        appendLine("That run used the target photo as the underlayer and stopped when a coarse grid filled.")
        appendLine("Masked scores count only pixels a cutout painted. Whole-image scores include the flat background.")
        appendLine()
        appendLine("| | Whole ΔE | Whole SSIM | Masked ΔE | Masked SSIM | Pixels painted |")
        appendLine("| --- | ---: | ---: | ---: | ---: | ---: |")
        appendLine("| Previous | 0.0475 | 0.7655 | 0.0989 | 0.6873 | 59% |")
        append("| This run | ${"%.4f".format(delta)} | ${"%.4f".format(ssim)} | ")
        append("${"%.4f".format(maskedDelta)} | ${"%.4f".format(maskedSsim)} | ")
        append("${"%.0f".format(painted * 100f)}% |")
        appendLine()
        appendLine()
    }
    File("docs/benchmarks/results.md").appendText(note)
    File("engine/build/reports/benchmarks/results.md").appendText(note)
    println(note)
}

private fun renderReport(results: List<BenchResult>, shape: BenchResult, collage: List<BenchResult>): String = buildString {
    appendLine("# Engine benchmarks")
    appendLine()
    appendLine("JVM run with synthetic tiles (unique hues, 16 px thumbnails, 12 px render cells).")
    appendLine("One unmeasured 100-tile pass runs first so the numbers below are not dominated by JIT warmup.")
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
    appendLine("Probes are candidate color checks inside OKLab bins, capped per cell so a large library is not scanned in full.")
    appendLine("The full-library column is cells × tiles, which is what the 1.x matcher did.")
    appendLine()
    appendLine("## Non-square cells with orientation matching")
    appendLine()
    appendLine("500 tiles (250 landscape, 250 portrait), 16:9 cells, rotation mode Match orientation. Same candidate cap as the table above.")
    appendLine()
    val naive = shape.columns.toLong() * shape.rows * shape.tiles
    appendLine("| Tiles | Grid | Analyze ms | Match ms | Render ms | Total ms | Probes | Full scan |")
    appendLine("| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |")
    append("| ${shape.tiles} | ${shape.columns}×${shape.rows} | ")
    append("%.1f".format(shape.analyzeMs)).append(" | ")
    append("%.1f".format(shape.matchMs)).append(" | ")
    append("%.1f".format(shape.renderMs)).append(" | ")
    append("%.1f".format(shape.totalMs)).append(" | ")
    append("${shape.probes} | $naive |")
    appendLine()
    appendLine()
    appendLine("## Cutout collage")
    appendLine()
    appendLine("Each placement queries the OKLab index once, then keeps a candidate only when its masked color lowers error")
    appendLine("without spoiling pixels that are already close. Full scan is requested pieces × cutouts × 12 angles.")
    appendLine()
    appendLine("| Cutouts | Requested | Placed | Analyze ms | Index ms | Match ms | Render ms | Total ms | Probes | Full scan | Note |")
    appendLine("| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |")
    collage.forEach { result ->
        val naiveScan = result.columns.toLong() * result.tiles * 12
        append("| ${result.tiles} | ${result.columns} | ${result.rows} | ")
        append("%.1f".format(result.analyzeMs)).append(" | ")
        append("%.1f".format(result.indexMs)).append(" | ")
        append("%.1f".format(result.matchMs)).append(" | ")
        append("%.1f".format(result.renderMs)).append(" | ")
        append("%.1f".format(result.totalMs)).append(" | ")
        append("${result.probes} | $naiveScan | ${result.note} |")
        appendLine()
    }
}

private suspend fun writeComparison() {
    val comparison = compareMatchers()
    writeSideBySide(
        comparison.legacy,
        comparison.modern,
        listOf(
            File("docs/images/matching-comparison.png"),
            File("/opt/cursor/artifacts/matching-comparison.png")
        )
    )
    val note = buildString {
        appendLine("## Matching quality")
        appendLine()
        appendLine("Portrait scene, 120 photo-like tiles, ${COMPARISON_COLUMNS}×${COMPARISON_ROWS} grid, seed $COMPARISON_SEED.")
        appendLine("Both sides use the original tile pixels and the same renderer. Left is average-RGB selection. Right is the default OKLab matcher.")
        appendLine()
        appendLine("| Matcher | Mean OKLab ΔE | Luminance SSIM |")
        appendLine("| --- | ---: | ---: |")
        append("| Average RGB | ${"%.4f".format(comparison.legacyDeltaE)} | ${"%.4f".format(comparison.legacySsim)} |")
        appendLine()
        append("| OKLab default | ${"%.4f".format(comparison.modernDeltaE)} | ${"%.4f".format(comparison.modernSsim)} |")
        appendLine()
    }
    File("docs/benchmarks/results.md").appendText("\n$note")
    File("engine/build/reports/benchmarks/results.md").appendText("\n$note")
    println(note)
}

private fun Long.ms(): Double = this / 1_000_000.0
