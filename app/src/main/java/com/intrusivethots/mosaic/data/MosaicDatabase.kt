package com.intrusivethots.mosaic.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ProjectEntity::class], version = 1, exportSchema = false)
abstract class MosaicDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
}
