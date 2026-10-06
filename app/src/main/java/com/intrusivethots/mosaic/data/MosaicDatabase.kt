package com.intrusivethots.mosaic.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ProjectEntity::class], version = 2, exportSchema = false)
abstract class MosaicDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE projects ADD COLUMN targetQuarterTurns INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE projects ADD COLUMN tileRotations TEXT NOT NULL DEFAULT ''")
    }
}
