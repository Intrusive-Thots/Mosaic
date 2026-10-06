package com.intrusivethots.mosaic.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectDaoTest {
    private lateinit var database: MosaicDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MosaicDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insertAndReadBack() = runBlocking {
        val dao = database.projectDao()
        dao.upsert(
            ProjectEntity(
                id = "p1",
                title = "Test",
                dateCreated = 10L,
                previewImagePath = "/tmp/preview.jpg",
                fullImagePath = "/tmp/full.png",
                tileCount = 4,
                columns = 8,
                rows = 8,
                preset = "Balanced",
                missingFiles = false
            )
        )
        val projects = dao.getAll()
        assertEquals(1, projects.size)
        assertEquals("Test", projects.first().title)
        dao.setMissing("p1", true)
        assertEquals(true, dao.getAll().first().missingFiles)
        dao.deleteById("p1")
        assertEquals(0, dao.getAll().size)
    }
}
