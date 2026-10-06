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
    val missingFiles: Boolean
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
    val missingFiles: Boolean = false
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
    missingFiles = missingFiles
)
