package com.intrusivethots.mosaic.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val title: String,
    val dateCreated: Long,
    val previewImagePath: String,
    val fullImagePath: String,
    val tileCount: Int,
    val columns: Int,
    val rows: Int,
    val preset: String,
    val missingFiles: Boolean,
    val targetQuarterTurns: Int = 0,
    val tileRotations: String = ""
)

data class MosaicProject(
    val id: String,
    val title: String,
    val dateCreated: Long,
    val previewImagePath: String,
    val fullImagePath: String,
    val tileCount: Int,
    val columns: Int,
    val rows: Int = columns,
    val preset: String = "Balanced",
    val missingFiles: Boolean = false,
    val targetQuarterTurns: Int = 0,
    val tileRotations: String = ""
)

fun ProjectEntity.toProject() = MosaicProject(
    id = id,
    title = title,
    dateCreated = dateCreated,
    previewImagePath = previewImagePath,
    fullImagePath = fullImagePath,
    tileCount = tileCount,
    columns = columns,
    rows = rows,
    preset = preset,
    missingFiles = missingFiles,
    targetQuarterTurns = targetQuarterTurns,
    tileRotations = tileRotations
)
