package com.intrusivethots.mosaic.drive

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

internal data class DriveCrumb(val id: String, val name: String)

internal data class DriveUiState(
    val configured: Boolean = false,
    val connected: Boolean = false,
    val email: String = "",
    val displayName: String = "",
    val busy: Boolean = false,
    val progressLabel: String = "",
    val error: String = "",
    val browsing: Boolean = false,
    val exporting: Boolean = false,
    val folderId: String = DRIVE_ROOT,
    val folderName: String = "My Drive",
    val crumbs: List<DriveCrumb> = emptyList(),
    val entries: List<DriveEntry> = emptyList(),
    val selected: Set<String> = emptySet(),
    val nextPageToken: String? = null
) {
    val accountLabel: String
        get() {
            val who = listOf(displayName, email).filter { it.isNotBlank() }.joinToString(" · ")
            return if (connected && who.isNotBlank()) "Connected as $who" else if (connected) "Connected" else "Not connected"
        }
}

internal class DriveViewModel(application: Application) : AndroidViewModel(application) {
    private val auth = GoogleDriveAuth(application)
    private val cache = DriveImageCache(DriveImageCache.directory(application.filesDir))
    private val client = RestDriveClient(HttpDriveTransport())
    private val library = DriveLibrary(DriveSession(client, auth), client, cache)
    private val _state = MutableStateFlow(DriveUiState(configured = auth.configured()))
    val state = _state.asStateFlow()
    private val _thumbs = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val thumbs = _thumbs.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()
    private val _consents = MutableSharedFlow<android.app.PendingIntent>(extraBufferCapacity = 1)
    val consents = _consents.asSharedFlow()
    private var work: Job? = null
    private var afterConsent: (suspend () -> Unit)? = null
    private var pendingExport: PreparedExport? = null

    init {
        viewModelScope.launch { restore() }
    }

    fun connect() = work {
        _state.update { it.copy(busy = true, error = "") }
        ensureScope(DRIVE_READ_SCOPE) { markConnected() }
        _state.update { it.copy(busy = false) }
    }

    fun disconnect() = work {
        _state.update { it.copy(busy = true, error = "") }
        withContext(Dispatchers.IO) { auth.disconnect() }
        releaseTemp()
        _state.value = DriveUiState(configured = auth.configured())
        say("Disconnected from Google Drive.")
    }

    fun openLibrary() = work {
        _state.update { it.copy(busy = true, error = "", exporting = false) }
        ensureScope(DRIVE_READ_SCOPE) {
            markConnected()
            if (_state.value.connected) showBrowser(export = false)
        }
    }

    fun browseToSave(title: String, path: String?, bitmap: Bitmap?) = work {
        val prepared = prepare(title, path, bitmap) ?: return@work
        pendingExport = prepared
        _state.update { it.copy(busy = true, error = "", exporting = true) }
        ensureScope(DRIVE_READ_SCOPE) {
            markConnected()
            if (_state.value.connected) showBrowser(export = true)
        }
    }

    fun copyToAppFolder(title: String, path: String?, bitmap: Bitmap?) = work {
        val prepared = prepare(title, path, bitmap) ?: return@work
        pendingExport = prepared
        _state.update { it.copy(busy = true, error = "") }
        ensureScope(DRIVE_FILE_SCOPE) { uploadApp() }
    }

    fun completeConsent(intent: Intent) = work {
        _state.update { it.copy(busy = true, error = "") }
        val outcome = auth.complete(intent)
        val token = outcome.token
        if (token == null) {
            afterConsent = null
            _state.update { it.copy(busy = false, error = outcome.message.orEmpty()) }
            say(outcome.message ?: "Google Drive did not finish connecting.")
            return@work
        }
        val next = afterConsent
        afterConsent = null
        if (next == null) markConnected() else next()
    }

    fun consentCancelled() {
        afterConsent = null
        _state.update { it.copy(busy = false, progressLabel = "") }
        say("Google Drive was not connected.")
    }

    fun closeBrowser() {
        work?.cancel()
        releaseTemp()
        afterConsent = null
        _state.update { it.copy(browsing = false, exporting = false, busy = false, progressLabel = "", selected = emptySet()) }
    }

    fun cancelWork() {
        work?.cancel()
    }

    fun back() {
        val crumbs = _state.value.crumbs
        if (crumbs.isEmpty()) {
            closeBrowser()
            return
        }
        val parent = crumbs.last()
        work { load(parent.id, parent.name, crumbs.dropLast(1), append = false) }
    }

    fun open(entry: DriveEntry) {
        if (!entry.folder) {
            toggle(entry.id)
            return
        }
        val crumbs = _state.value.crumbs + DriveCrumb(_state.value.folderId, _state.value.folderName)
        work { load(entry.id, entry.name, crumbs, append = false) }
    }

    fun loadMore() {
        if (_state.value.nextPageToken.isNullOrBlank() || _state.value.busy) return
        val state = _state.value
        work { load(state.folderId, state.folderName, state.crumbs, append = true) }
    }

    fun toggle(id: String) {
        _state.update { state ->
            val selected = if (id in state.selected) state.selected - id else state.selected + id
            state.copy(selected = selected)
        }
    }

    fun addSelected(pinned: Set<String>, onReady: (List<Uri>) -> Unit) {
        val chosen = _state.value.entries.filter { it.image && it.id in _state.value.selected }
        if (chosen.isEmpty()) {
            say("Select one or more photos.")
            return
        }
        work {
            _state.update { it.copy(busy = true, error = "") }
            val files = withContext(Dispatchers.IO) {
                library.download(chosen, pinned) { index, name ->
                    val text = if (name.isEmpty()) "" else "Downloading ${index + 1} of ${chosen.size} · $name"
                    _state.update { it.copy(progressLabel = text) }
                }
            }
            finishImport(files, onReady, truncated = false)
        }
    }

    fun addFolder(pinned: Set<String>, limit: Int, onReady: (List<Uri>) -> Unit) {
        val folderId = _state.value.folderId
        work {
            _state.update { it.copy(busy = true, error = "") }
            val imported = withContext(Dispatchers.IO) {
                library.folderImages(folderId, pinned, limit = limit) { index, label ->
                    _state.update { it.copy(progressLabel = "Downloading ${index + 1} · $label") }
                }
            }
            finishImport(imported.files, onReady, imported.truncated)
        }
    }

    fun saveHere() {
        val prepared = pendingExport
        if (prepared == null) {
            say("Render a mosaic before saving it.")
            return
        }
        val folderId = _state.value.folderId
        work {
            _state.update { it.copy(busy = true, error = "") }
            ensureScope(DRIVE_FILE_SCOPE) { uploadInto(folderId, prepared) }
        }
    }

    private suspend fun restore() {
        if (!auth.configured()) return
        val outcome = try {
            auth.connect(listOf(DRIVE_READ_SCOPE), interactive = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return
        }
        if (outcome.token != null) markConnected()
    }

    private suspend fun showBrowser(export: Boolean) {
        _state.update { it.copy(browsing = true, exporting = export) }
        load(DRIVE_ROOT, "My Drive", emptyList(), append = false)
    }

    private suspend fun load(id: String, name: String, crumbs: List<DriveCrumb>, append: Boolean) {
        val pageToken = if (append) _state.value.nextPageToken else null
        _state.update { it.copy(busy = true, progressLabel = "Opening $name", error = "") }
        val page = withContext(Dispatchers.IO) { library.list(id, pageToken) }
        _state.update { state ->
            val entries = if (append) (state.entries + page.entries).distinctBy { it.id } else page.entries
            state.copy(
                busy = false,
                progressLabel = "",
                browsing = true,
                folderId = id,
                folderName = name,
                crumbs = crumbs,
                entries = entries,
                nextPageToken = page.nextPageToken,
                selected = if (append) state.selected else emptySet()
            )
        }
        refreshThumbs()
    }

    private suspend fun refreshThumbs() {
        val images = _state.value.entries.filter { it.image && it.thumbnailLink.isNotBlank() }.take(THUMB_CAP)
        val next = HashMap<String, Bitmap>()
        for (entry in images) {
            coroutineContext.ensureActive()
            val bytes = quiet { library.thumbnail(entry.thumbnailLink) } ?: continue
            val bitmap = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } ?: continue
            next[entry.id] = bitmap
        }
        _thumbs.value = next
    }

    private suspend fun markConnected() {
        val account = try {
            withContext(Dispatchers.IO) { library.about() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: DriveFailure) {
            onFailure(failure)
            return
        }
        auth.save(account)
        _state.update {
            it.copy(connected = true, email = account.email, displayName = account.displayName, error = "", busy = false)
        }
    }

    private suspend fun uploadApp() {
        val prepared = pendingExport ?: return
        _state.update { it.copy(progressLabel = "Saving to the Mosaic folder") }
        withContext(Dispatchers.IO) { library.exportToAppFolder(prepared.file.name, prepared.file) }
        releaseTemp()
        _state.update { it.copy(busy = false, progressLabel = "") }
        say("Saved to the Mosaic folder in Google Drive.")
    }

    private suspend fun uploadInto(folderId: String, prepared: PreparedExport) {
        _state.update { it.copy(progressLabel = "Saving to Google Drive") }
        val uploaded = withContext(Dispatchers.IO) { library.exportTo(folderId, prepared.file.name, prepared.file) }
        releaseTemp()
        _state.update { it.copy(busy = false, browsing = false, exporting = false, progressLabel = "") }
        val text = if (uploaded.usedAppFolder) {
            "Drive can only save into folders this app created, so it went to the Mosaic folder."
        } else {
            "Saved to Google Drive."
        }
        say(text)
    }

    private fun finishImport(files: List<File>, onReady: (List<Uri>) -> Unit, truncated: Boolean) {
        val uris = files.map { share(it) }
        if (uris.isNotEmpty()) onReady(uris)
        _state.update { it.copy(busy = false, browsing = uris.isEmpty(), progressLabel = "", selected = emptySet()) }
        if (uris.isEmpty()) {
            say("No photos were found in that folder.")
            return
        }
        val tail = if (truncated) " This folder has more; open a smaller folder for the rest." else ""
        say("Added ${uris.size} photos from Google Drive.$tail")
    }

    private suspend fun prepare(title: String, path: String?, bitmap: Bitmap?): PreparedExport? {
        val prepared = prepareExport(getApplication<Application>().cacheDir, title, path, bitmap)
        if (prepared == null) say("Render a mosaic before saving it.")
        return prepared
    }

    private fun share(file: File): Uri {
        val app = getApplication<Application>()
        return FileProvider.getUriForFile(app, "${app.packageName}.files", file)
    }

    private suspend fun ensureScope(scope: String, block: suspend () -> Unit) {
        val outcome = auth.connect(listOf(scope), interactive = true)
        val consent = outcome.consent
        when {
            consent != null -> {
                afterConsent = block
                _state.update { it.copy(busy = false) }
                _consents.emit(consent)
            }
            outcome.token != null -> block()
            else -> {
                _state.update { it.copy(busy = false, error = outcome.message.orEmpty()) }
                say(outcome.message ?: "Google Drive is not connected.")
            }
        }
    }

    private fun work(block: suspend () -> Unit) {
        work?.cancel()
        work = viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(busy = false, progressLabel = "") }
            } catch (failure: DriveFailure) {
                onFailure(failure)
            }
        }
    }

    private fun onFailure(failure: DriveFailure) {
        val signedOut = failure.problem == DriveProblem.REVOKED || failure.problem == DriveProblem.UNAUTHORIZED
        _state.update {
            it.copy(busy = false, progressLabel = "", error = driveMessage(failure), connected = if (signedOut) false else it.connected)
        }
        say(driveMessage(failure))
    }

    private fun say(text: String) {
        _messages.tryEmit(text)
    }

    private fun releaseTemp() {
        val prepared = pendingExport
        if (prepared != null && prepared.temporary) prepared.file.delete()
        pendingExport = null
    }

    private suspend fun <T> quiet(block: suspend () -> T): T? {
        return try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            null
        }
    }

    private companion object {
        const val THUMB_CAP = 40
    }
}
