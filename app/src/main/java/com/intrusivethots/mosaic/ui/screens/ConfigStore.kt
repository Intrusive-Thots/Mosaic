package com.intrusivethots.mosaic.ui.screens

import android.content.SharedPreferences
import com.intrusivethots.mosaic.engine.config.AspectRatioPreset
import com.intrusivethots.mosaic.engine.config.CellAspect
import com.intrusivethots.mosaic.engine.config.CollageBackground
import com.intrusivethots.mosaic.engine.config.CollageSettings
import com.intrusivethots.mosaic.engine.config.CollageStyle
import com.intrusivethots.mosaic.engine.config.HybridStack
import com.intrusivethots.mosaic.engine.config.LayoutMode
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.MosaicKind
import com.intrusivethots.mosaic.engine.config.MosaicStyle
import com.intrusivethots.mosaic.engine.config.OutputMode
import com.intrusivethots.mosaic.engine.config.QualityPreset
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.config.RotationMode
import com.intrusivethots.mosaic.engine.config.SubjectShape
import com.intrusivethots.mosaic.engine.config.TileFit
import com.intrusivethots.mosaic.engine.config.applyTo

internal fun readMosaicConfig(preferences: SharedPreferences): MosaicConfig {
    if (!preferences.contains("cols")) return QualityPreset.BALANCED.applyTo(MosaicConfig())
    val preset = preferences.getString("preset", QualityPreset.BALANCED.name)
        ?.let { runCatching { QualityPreset.valueOf(it) }.getOrNull() }
        ?: QualityPreset.BALANCED
    val base = preset.applyTo(MosaicConfig())
    return base.copy(
        gridColumns = preferences.getInt("cols", base.gridColumns),
        gridRows = preferences.getInt("rows", base.gridRows),
        linkAspectToGrid = preferences.getBoolean("link", base.linkAspectToGrid),
        colorMatchWeight = preferences.getFloat("blend", base.colorMatchWeight),
        allowTileRepetition = preferences.getBoolean("repeat", base.allowTileRepetition),
        maxRepetitionDistance = preferences.getInt("radius", base.maxRepetitionDistance),
        extractSubjectsWithAi = preferences.getBoolean("ai", false),
        randomSeed = preferences.getInt("seed", 1),
        aspectRatio = enumPref(preferences, "aspect", base.aspectRatio),
        mosaicStyle = enumPref(preferences, "style", base.mosaicStyle),
        renderMode = enumPref(preferences, "render", base.renderMode),
        outputMode = enumPref(preferences, "output", base.outputMode),
        tileFit = enumPref(preferences, "fit", base.tileFit),
        cellAspect = enumPref(preferences, "cellAspect", base.cellAspect),
        layoutMode = enumPref(preferences, "layoutMode", base.layoutMode),
        rotationMode = enumPref(preferences, "rotationMode", base.rotationMode),
        targetQuarterTurns = preferences.getInt("targetTurns", 0) and 3,
        targetScale = preferences.getFloat("targetScale", 1f),
        customOutputWidth = preferences.getInt("outW", 0),
        customOutputHeight = preferences.getInt("outH", 0),
        lockOutputAspect = preferences.getBoolean("outLock", true),
        mosaicKind = enumPref(preferences, "kind", MosaicKind.GRID),
        collage = readCollage(preferences, base.collage),
        segmentation = base.segmentation.copy(
            maxExtractedSubjects = preferences.getInt("aiMax", base.segmentation.maxExtractedSubjects),
            minSubjectSizePx = preferences.getInt("aiMin", base.segmentation.minSubjectSizePx),
            allowedShapes = readShapes(preferences)
        )
    )
}

internal fun writeMosaicConfig(preferences: SharedPreferences, config: MosaicConfig) {
    preferences.edit()
        .putInt("cols", config.gridColumns)
        .putInt("rows", config.gridRows)
        .putBoolean("link", config.linkAspectToGrid)
        .putFloat("blend", config.colorMatchWeight)
        .putBoolean("repeat", config.allowTileRepetition)
        .putInt("radius", config.maxRepetitionDistance)
        .putBoolean("ai", config.extractSubjectsWithAi)
        .putInt("seed", config.randomSeed)
        .putString("preset", config.qualityPreset.name)
        .putString("aspect", config.aspectRatio.name)
        .putString("style", config.mosaicStyle.name)
        .putString("render", config.renderMode.name)
        .putString("output", config.outputMode.name)
        .putString("fit", config.tileFit.name)
        .putString("cellAspect", config.cellAspect.name)
        .putString("layoutMode", config.layoutMode.name)
        .putString("rotationMode", config.rotationMode.name)
        .putInt("targetTurns", config.targetQuarterTurns and 3)
        .putFloat("targetScale", config.targetScale)
        .putInt("outW", config.customOutputWidth)
        .putInt("outH", config.customOutputHeight)
        .putBoolean("outLock", config.lockOutputAspect)
        .putString("kind", config.mosaicKind.name)
        .putInt("pieces", config.collage.pieceCount)
        .putFloat("smin", config.collage.minScale)
        .putFloat("mpiece", config.collage.minPiece)
        .putFloat("smax", config.collage.maxScale)
        .putFloat("rdeg", config.collage.rotationRangeDegrees)
        .putFloat("overlap", config.collage.overlap)
        .putFloat("goal", config.collage.coverageGoal)
        .putString("cbg", config.collage.background.name)
        .putFloat("shapeW", config.collage.shapeWeight)
        .putBoolean("collagePhotos", config.collage.includeSourcePhotos)
        .putBoolean("separate", config.collage.separatePieces)
        .putString("look", config.collage.style.name)
        .putString("stack", config.collage.stack.name)
        .putBoolean("outline", config.collage.outline)
        .putFloat("feather", config.collage.feather)
        .putInt("aiMax", config.segmentation.maxExtractedSubjects)
        .putInt("aiMin", config.segmentation.minSubjectSizePx)
        .putString("shapes", config.segmentation.allowedShapes.joinToString(",") { it.name })
        .apply()
}

private fun readCollage(preferences: SharedPreferences, base: CollageSettings): CollageSettings = base.copy(
    pieceCount = preferences.getInt("pieces", base.pieceCount),
    minScale = preferences.getFloat("smin", base.minScale),
    minPiece = preferences.getFloat("mpiece", base.minPiece),
    maxScale = preferences.getFloat("smax", base.maxScale),
    rotationRangeDegrees = preferences.getFloat("rdeg", base.rotationRangeDegrees),
    overlap = preferences.getFloat("overlap", base.overlap),
    coverageGoal = preferences.getFloat("goal", base.coverageGoal),
    background = enumPref(preferences, "cbg", base.background),
    shapeWeight = preferences.getFloat("shapeW", base.shapeWeight),
    includeSourcePhotos = preferences.getBoolean("collagePhotos", base.includeSourcePhotos),
    separatePieces = preferences.getBoolean("separate", base.separatePieces),
    style = enumPref(preferences, "look", base.style),
    stack = enumPref(preferences, "stack", base.stack),
    outline = preferences.getBoolean("outline", base.outline),
    feather = preferences.getFloat("feather", base.feather)
)

private fun readShapes(preferences: SharedPreferences): Set<SubjectShape> {
    val raw = preferences.getString("shapes", null) ?: return setOf(SubjectShape.TALL, SubjectShape.WIDE, SubjectShape.COMPACT)
    val parsed = raw.split(",").mapNotNull { runCatching { SubjectShape.valueOf(it) }.getOrNull() }.toSet()
    return parsed.ifEmpty { setOf(SubjectShape.TALL, SubjectShape.WIDE, SubjectShape.COMPACT) }
}

private inline fun <reified T : Enum<T>> enumPref(preferences: SharedPreferences, key: String, fallback: T): T {
    return preferences.getString(key, fallback.name)?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback
}
