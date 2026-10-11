package com.intrusivethots.mosaic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.intrusivethots.mosaic.drive.DRIVE_FOLDER_MIME
import com.intrusivethots.mosaic.drive.DriveEntry
import com.intrusivethots.mosaic.drive.FolderImage
import com.intrusivethots.mosaic.ui.components.DriveThumbGrid
import com.intrusivethots.mosaic.ui.components.FolderGrid
import com.intrusivethots.mosaic.ui.theme.MosaicTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * The Files folder grid and the in-app Drive grid. Both list names, and the folder grid
 * can take a subset, every image, or a single picture.
 */
@RunWith(AndroidJUnit4::class)
class FolderGridTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun folderGridSelectsASubsetThenEveryImage() {
        val images = stickerFiles()
        var chosen = emptyList<Uri>()
        composeRule.setContent {
            MosaicTheme {
                FolderGrid(images, single = false, onDismiss = {}, onConfirm = { chosen = it }, onChangeFolder = {})
            }
        }
        composeRule.onNodeWithText("025-Pikachu.png").assertIsDisplayed()
        composeRule.onNodeWithText("001-Bulbasaur.png").assertIsDisplayed()
        composeRule.onNodeWithText("Select all").assertIsDisplayed()
        composeRule.onNodeWithText("Change folder").assertIsDisplayed()
        composeRule.onNode(hasContentDescription("Select 025-Pikachu.png")).performClick()
        composeRule.onNode(hasContentDescription("Select 004-Charmander.png")).performClick()
        composeRule.onNodeWithText("Add selected").performClick()
        assertEquals(listOf(images[1].uri, images[2].uri), chosen)

        chosen = emptyList()
        composeRule.onNodeWithText("Select all").performClick()
        composeRule.onNodeWithText("Add selected").performClick()
        assertEquals(images.map { it.uri }, chosen)
    }

    @Test
    fun folderGridUsesOneImageAsThePicture() {
        val images = stickerFiles()
        var chosen = emptyList<Uri>()
        composeRule.setContent {
            MosaicTheme {
                FolderGrid(images, single = true, onDismiss = {}, onConfirm = { chosen = it })
            }
        }
        composeRule.onNodeWithText("Use as picture").assertIsDisplayed()
        composeRule.onNodeWithText("Select all").assertDoesNotExist()
        composeRule.onNode(hasContentDescription("Select 025-Pikachu.png")).performClick()
        composeRule.onNode(hasContentDescription("Select 001-Bulbasaur.png")).performClick()
        composeRule.onNodeWithText("Use as picture").performClick()
        assertEquals(listOf(images[0].uri), chosen)
    }

    @Test
    fun driveGridShowsNamesAndReportsTheTappedImage() {
        var tapped = ""
        val pikachu = DriveEntry("25", "025-Pikachu.png", "image/png", "", 1L, "")
        val folder = DriveEntry("box", "Pokemon Gen 1 (151)", DRIVE_FOLDER_MIME, "", 0L, "")
        composeRule.setContent {
            MosaicTheme {
                DriveThumbGrid(
                    entries = listOf(folder, pikachu),
                    selected = setOf("25"),
                    thumbs = emptyMap(),
                    onEntry = { tapped = it.name },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        composeRule.onNodeWithText("025-Pikachu.png").assertIsDisplayed()
        composeRule.onNodeWithText("Pokemon Gen 1 (151)").assertIsDisplayed()
        composeRule.onNode(hasContentDescription("Select 025-Pikachu.png")).performClick()
        assertEquals("025-Pikachu.png", tapped)
        composeRule.onNode(hasContentDescription("Pokemon Gen 1 (151)")).performClick()
        assertEquals("Pokemon Gen 1 (151)", tapped)
    }

    private fun stickerFiles(): List<FolderImage> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val names = listOf("001-Bulbasaur.png", "025-Pikachu.png", "004-Charmander.png", "007-Squirtle.png")
        return names.mapIndexed { index, name ->
            val file = File(context.cacheDir, name)
            val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint().apply { color = Color.rgb(40 + index * 60, 180, 40) }
            canvas.drawCircle(24f, 24f, 16f, paint)
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            FolderImage(Uri.fromFile(file), name)
        }
    }
}
