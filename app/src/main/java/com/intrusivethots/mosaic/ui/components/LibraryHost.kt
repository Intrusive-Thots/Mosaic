package com.intrusivethots.mosaic.ui.components

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.intrusivethots.mosaic.drive.CreatePersistableDocument
import com.intrusivethots.mosaic.drive.DriveImageCache
import com.intrusivethots.mosaic.drive.DriveViewModel
import com.intrusivethots.mosaic.drive.OpenPersistableImages
import com.intrusivethots.mosaic.drive.OpenPersistableTree
import com.intrusivethots.mosaic.drive.collectTreeImages
import com.intrusivethots.mosaic.drive.copyFileToUri
import com.intrusivethots.mosaic.drive.keepReadPermission
import com.intrusivethots.mosaic.drive.keepWritePermission
import com.intrusivethots.mosaic.drive.mosaicExportName
import com.intrusivethots.mosaic.drive.prepareExport
import com.intrusivethots.mosaic.ui.screens.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class LibraryTarget { TILES, STAMPS }

@Composable
fun LibraryHost(main: MainViewModel, content: @Composable (LibrarySources) -> Unit) {
    val drive: DriveViewModel = viewModel()
    val driveState by drive.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var sheet by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf(LibraryTarget.TILES) }
    val targetState = rememberUpdatedState(target)
    var saveTitle by remember { mutableStateOf("Mosaic") }
    var savePath by remember { mutableStateOf<String?>(null) }
    var saveBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val save = rememberDriveSavers(snackbar, saveTitle, savePath, saveBitmap)
    val picked = rememberUpdatedState<(List<Uri>) -> Unit> { uris ->
        val tiles = targetState.value == LibraryTarget.TILES
        val capped = if (tiles) uris.take(400) else uris.take(40)
        if (capped.isEmpty()) return@rememberUpdatedState
        if (tiles) main.addTileImages(capped) else main.extractStampBatch(capped)
    }
    val launchers = rememberLibraryLaunchers(snackbar, picked.value)
    ConsentEffect(drive)
    LaunchedEffect(drive) { drive.messages.collect { snackbar.showSnackbar(it) } }
    val export = LibraryExport(
        driveConnected = driveState.connected,
        onCopyToDrive = { title ->
            val snapshot = main.state.value
            drive.copyToAppFolder(title, snapshot.fullImagePath, snapshot.outputBitmap)
        },
        onSaveToFolder = { title ->
            val snapshot = main.state.value
            saveTitle = title
            savePath = snapshot.fullImagePath
            saveBitmap = snapshot.outputBitmap
            save.launch(mosaicExportName(title))
        },
        onBrowseDrive = { title ->
            val snapshot = main.state.value
            drive.browseToSave(title, snapshot.fullImagePath, snapshot.outputBitmap)
        }
    )
    CompositionLocalProvider(LocalLibraryExport provides export, LocalDriveCard provides { DriveSettingsCard(drive) }) {
        Box(Modifier.fillMaxSize()) {
            content(LibrarySources(
                pickTiles = { target = LibraryTarget.TILES; sheet = true },
                pickStamps = { target = LibraryTarget.STAMPS; sheet = true }
            ))
            SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp))
            if (sheet) {
                SourceSheet(
                    drive = driveState,
                    onDismiss = { sheet = false },
                    onPhotos = {
                        sheet = false
                        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        if (target == LibraryTarget.TILES) launchers.tiles.launch(request) else launchers.stamps.launch(request)
                    },
                    onImages = { sheet = false; launchers.files.launch(arrayOf("image/*")) },
                    onFolder = { sheet = false; launchers.folder.launch(null) },
                    onDrive = { sheet = false; drive.openLibrary() }
                )
            }
            if (driveState.browsing) {
                DriveBrowser(
                    drive = drive,
                    stampCap = target == LibraryTarget.STAMPS,
                    pinned = { main.state.value.tileUris.mapNotNull { DriveImageCache.ownedName(it) }.toSet() },
                    onAdded = picked.value
                )
            }
        }
    }
}

@Composable
private fun ConsentEffect(drive: DriveViewModel) {
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) drive.completeConsent(data) else drive.consentCancelled()
    }
    LaunchedEffect(drive) {
        drive.consents.collect { pending ->
            try {
                consent.launch(IntentSenderRequest.Builder(pending).build())
            } catch (failure: Exception) {
                drive.consentCancelled()
            }
        }
    }
}

private class LibraryLaunchers(
    val tiles: androidx.activity.result.ActivityResultLauncher<PickVisualMediaRequest>,
    val stamps: androidx.activity.result.ActivityResultLauncher<PickVisualMediaRequest>,
    val files: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    val folder: androidx.activity.result.ActivityResultLauncher<Uri?>
)

@Composable
private fun rememberLibraryLaunchers(snackbar: SnackbarHostState, onPicked: (List<Uri>) -> Unit): LibraryLaunchers {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(onPicked)
    val imageContract = remember { OpenPersistableImages() }
    val treeContract = remember { OpenPersistableTree() }
    val tiles = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxItems = 100)) { uris ->
        if (uris.isNotEmpty()) latest.value(uris)
    }
    val stamps = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxItems = 40)) { uris ->
        if (uris.isNotEmpty()) latest.value(uris)
    }
    val files = rememberLauncherForActivityResult(imageContract) { uris ->
        uris.forEach { keepReadPermission(context, it) }
        latest.value(uris)
    }
    val folder = rememberLauncherForActivityResult(treeContract) { uri ->
        if (uri != null) readFolder(context, scope, snackbar, uri, latest.value)
    }
    return LibraryLaunchers(tiles, stamps, files, folder)
}

@Composable
private fun rememberDriveSavers(
    snackbar: SnackbarHostState,
    title: String,
    path: String?,
    bitmap: Bitmap?
): androidx.activity.result.ActivityResultLauncher<String> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val titleState = rememberUpdatedState(title)
    val pathState = rememberUpdatedState(path)
    val bitmapState = rememberUpdatedState(bitmap)
    val contract = remember { CreatePersistableDocument("image/png") }
    return rememberLauncherForActivityResult(contract) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        keepWritePermission(context, uri)
        scope.launch {
            val prepared = withContext(Dispatchers.IO) {
                prepareExport(context.cacheDir, titleState.value, pathState.value, bitmapState.value)
            }
            if (prepared == null) {
                snackbar.showSnackbar("Render a mosaic before saving it.")
                return@launch
            }
            val saved = withContext(Dispatchers.IO) { copyFileToUri(context, prepared.file, uri) }
            if (prepared.temporary) prepared.file.delete()
            snackbar.showSnackbar(if (saved) "Saved to the folder you chose." else "Could not save to that folder.")
        }
    }
}

private fun readFolder(
    context: android.content.Context,
    scope: CoroutineScope,
    snackbar: SnackbarHostState,
    uri: Uri,
    onPicked: (List<Uri>) -> Unit
) {
    keepReadPermission(context, uri)
    scope.launch {
        val read = withContext(Dispatchers.IO) { collectTreeImages(context, uri) }
        read.uris.forEach { keepReadPermission(context, it) }
        onPicked(read.uris)
        if (read.uris.isEmpty()) snackbar.showSnackbar("No photos were found in that folder.")
        else if (read.truncated) snackbar.showSnackbar("That folder has more photos than Mosaic added. Open a smaller folder for the rest.")
    }
}
