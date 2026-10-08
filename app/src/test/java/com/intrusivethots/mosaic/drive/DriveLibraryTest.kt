package com.intrusivethots.mosaic.drive

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

class DriveLibraryTest {
    @Test
    fun jsonKeepsEscapesAndDriveFields() {
        val parsed = parseJson("""{"name":"a\"b","files":[{"id":"1","size":"12"}],"nextPageToken":"next"}""")
        assertEquals("a\"b", parsed.string("name"))
        assertEquals("next", parsed.string("nextPageToken"))
        assertEquals("1", parsed.array("files").first().string("id"))
        assertEquals(12L, parsed.array("files").first().long("size"))
        val about = parseJson("""{"user":{"emailAddress":"a@b.c","displayName":"Ada"}}""")
        assertEquals("Ada", about.obj("user")?.string("displayName"))
    }

    @Test
    fun responsesClassifyOfflineExpiredAndRevoked() {
        assertNull(classifyDriveResponse(200, "", false))
        assertEquals(DriveProblem.OFFLINE, classifyDriveResponse(200, "", true)?.problem)
        assertEquals(DriveProblem.UNAUTHORIZED, classifyDriveResponse(401, "", false)?.problem)
        val revoked = classifyDriveResponse(403, """{"error":"insufficientPermissions"}""", false)
        assertEquals(DriveProblem.REVOKED, revoked?.problem)
        val failed = classifyDriveResponse(403, """{"error":{"message":"nope"}}""", false)
        assertEquals(DriveProblem.FAILED, failed?.problem)
        assertEquals("nope", failed?.message)
    }

    @Test
    fun listUrlEncodesTheFolderQueryAndPage() {
        val url = listUrl("abc'd", "next token")
        assertTrue(url.contains("pageSize=40"))
        assertTrue(url.contains("pageToken="))
        assertFalse(url.contains("next token"))
        assertFalse(url.contains("'abc'd'"))
        assertTrue(url.contains("in+parents") || url.contains("in%20parents"))
    }

    @Test
    fun libraryImagesSkipSvgAndAcceptPhotos() {
        assertFalse(isLibraryImage("image/svg+xml", "icon.svg"))
        assertTrue(isLibraryImage("image/jpeg", "a"))
        assertTrue(isLibraryImage("", "Photo.HEIC"))
        assertFalse(isLibraryImage("text/plain", "notes.txt"))
        assertTrue(mosaicExportName("My Shot").endsWith(".png"))
        assertFalse(mosaicExportName("My Shot").contains(" "))
    }

    @Test
    fun restClientListsDownloadsAndUploads() = runBlocking {
        val transport = ScriptTransport()
        val client = RestDriveClient(transport)
        transport.queue.add(DriveBytes(200, """{"user":{"emailAddress":"a@b.c","displayName":"Ada"}}""".toByteArray()))
        assertEquals("Ada", client.about("tok").displayName)
        transport.queue.add(DriveBytes(401, ByteArray(0)))
        try {
            client.list("tok", "root", null)
            error("expected")
        } catch (failure: DriveFailure) {
            assertEquals(DriveProblem.UNAUTHORIZED, failure.problem)
        }
        val dest = tempFile("photo")
        transport.queue.add(DriveBytes(200, ByteArray(0)))
        client.download("tok", "file", dest) {}
        assertEquals(2, dest.length())
        val png = tempFile("mosaic")
        png.writeBytes(byteArrayOf(1, 2, 3, 4))
        transport.queue.add(DriveBytes(200, """{"id":"u","name":"Shot.png","mimeType":"image/png"}""".toByteArray()))
        client.upload("tok", "parent-1", "Shot.png", "image/png", png)
        val body = transport.uploaded.toString(Charsets.UTF_8)
        assertTrue(body.contains("Shot.png"))
        assertTrue(body.contains("parent-1"))
    }

    @Test
    fun expiredTokenIsRetriedOnceThenRevoked() = runBlocking {
        val transport = ScriptTransport()
        transport.queue.add(DriveBytes(401, ByteArray(0)))
        transport.queue.add(DriveBytes(200, """{"files":[{"id":"1","name":"a.jpg","mimeType":"image/jpeg"}]}""".toByteArray()))
        val tokens = FlipTokens()
        val session = DriveSession(RestDriveClient(transport), tokens)
        val page = session.list("root", null)
        assertEquals("1", page.entries.single().id)
        assertEquals(listOf("old", "new"), transport.tokens)
        assertEquals(1, tokens.flips)
        transport.queue.add(DriveBytes(401, ByteArray(0)))
        transport.queue.add(DriveBytes(401, ByteArray(0)))
        val sticky = StickyTokens()
        try {
            DriveSession(RestDriveClient(transport), sticky).list("root", null)
            error("expected")
        } catch (failure: DriveFailure) {
            assertEquals(DriveProblem.REVOKED, failure.problem)
        }
        assertEquals(1, sticky.flips)
    }

    @Test
    fun offlineIsNotRetried() = runBlocking {
        val transport = ScriptTransport()
        transport.queue.add(DriveBytes(0, ByteArray(0), true))
        val tokens = StickyTokens()
        try {
            DriveSession(RestDriveClient(transport), tokens).about()
            error("expected")
        } catch (failure: DriveFailure) {
            assertEquals(DriveProblem.OFFLINE, failure.problem)
        }
        assertEquals(0, tokens.flips)
    }

    @Test
    fun folderWalkPagesSkipsAndStopsAtTheCap() = runBlocking {
        val root = tempDir()
        val client = TreeDrive()
        val library = library(client, root)
        val imported = library.folderImages("root", emptySet(), limit = 20) { _, _ -> }
        assertEquals(listOf("1", "4", "2"), client.downloaded)
        assertFalse(imported.truncated)
        val capped = library.folderImages("root", emptySet(), limit = 2) { _, _ -> }
        assertEquals(2, capped.files.size)
        assertTrue(capped.truncated)
    }

    @Test
    fun cancelStopsAFolderWalk() = runBlocking {
        val client = object : FakeDrive() {
            override suspend fun list(token: String, folderId: String, pageToken: String?): DrivePage {
                coroutineContext.ensureActive()
                delay(60_000)
                error("cancelled")
            }
        }
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            library(client, tempDir()).folderImages("root", emptySet()) { _, _ -> }
        }
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
    }

    @Test
    fun deniedFolderFallsBackToTheAppFolder() = runBlocking {
        val client = ExportDrive()
        val file = tempFile("out")
        file.writeBytes(byteArrayOf(1))
        val uploaded = library(client, tempDir()).exportTo("denied", "A.png", file)
        assertTrue(uploaded.usedAppFolder)
        assertEquals(listOf("mosaic-id"), client.parents)
        client.revoked = true
        try {
            library(client, tempDir()).exportTo("denied", "A.png", file)
            error("expected")
        } catch (failure: DriveFailure) {
            assertEquals(DriveProblem.REVOKED, failure.problem)
        }
        assertEquals(1, client.created)
    }

    @Test
    fun cacheReusesPinsAndDropsPartialFiles() = runBlocking {
        val root = tempDir()
        val cache = DriveImageCache(root, maxBytes = 30)
        var writes = 0
        val first = cache.store("a/b", "mod", emptySet()) { file ->
            writes++
            file.writeBytes(byteArrayOf(1, 2, 3))
        }
        val again = cache.store("a/b", "mod", emptySet()) {
            writes++
            error("rewritten")
        }
        assertEquals(first.absolutePath, again.absolutePath)
        assertEquals(1, writes)
        assertTrue(fileName("a/b", "mod").startsWith("drive-"))
        val kept = cache.store("keep", "1", emptySet()) { it.writeBytes(ByteArray(20)) }
        try {
            cache.store("next", "1", setOf(kept.name)) { it.writeBytes(ByteArray(20)) }
            error("expected")
        } catch (failure: DriveFailure) {
            assertEquals(DriveProblem.FAILED, failure.problem)
        }
        assertTrue(kept.exists())
        assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".part") })
        val room = DriveImageCache(tempDir(), maxBytes = 30)
        val older = room.store("old", "1", emptySet()) { it.writeBytes(ByteArray(20)) }
        val newer = room.store("new", "1", emptySet()) { it.writeBytes(ByteArray(20)) }
        assertFalse(older.exists())
        assertTrue(newer.exists())
        try {
            cache.store("cancel", "1", emptySet()) { throw CancellationException("stop") }
            error("expected")
        } catch (cancelled: CancellationException) {
            assertEquals("stop", cancelled.message)
        }
        assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    private fun library(client: DriveClient, root: File): DriveLibrary {
        val tokens = StickyTokens()
        return DriveLibrary(DriveSession(client, tokens), client, DriveImageCache(root, maxBytes = 200_000))
    }
}

private class ScriptTransport : DriveTransport {
    val queue = ArrayDeque<DriveBytes>()
    val tokens = ArrayList<String>()
    var uploaded = ByteArray(0)

    override fun get(url: String, token: String): DriveBytes {
        tokens += token
        return queue.removeFirst()
    }

    override fun post(url: String, token: String, body: ByteArray, contentType: String): DriveBytes = queue.removeFirst()

    override fun download(url: String, token: String, dest: File, onBytes: (Long) -> Unit): DriveBytes {
        dest.writeBytes(byteArrayOf(9, 9))
        onBytes(2)
        return queue.removeFirst()
    }

    override fun upload(url: String, token: String, contentType: String, writeBody: (OutputStream) -> Unit): DriveBytes {
        val buffer = ByteArrayOutputStream()
        writeBody(buffer)
        uploaded = buffer.toByteArray()
        return queue.removeFirst()
    }
}

private class FlipTokens : DriveTokens {
    var current = "old"
    var flips = 0
    override suspend fun token(scope: String) = current
    override suspend fun invalidate(scope: String) {
        flips++
        current = "new"
    }
}

private class StickyTokens : DriveTokens {
    var flips = 0
    override suspend fun token(scope: String) = "bad"
    override suspend fun invalidate(scope: String) {
        flips++
    }
}

private open class FakeDrive : DriveClient {
    override suspend fun about(token: String) = DriveAccount("a@b.c", "Ada")
    override suspend fun list(token: String, folderId: String, pageToken: String?) = DrivePage(emptyList(), null)
    override suspend fun thumbnail(token: String, link: String): ByteArray? = null
    override suspend fun download(token: String, fileId: String, dest: File, onBytes: (Long) -> Unit) = Unit
    override suspend fun createFolder(token: String, name: String) = image(name, DRIVE_FOLDER_MIME)
    override suspend fun findFolder(token: String, name: String): DriveEntry? = null
    override suspend fun upload(token: String, parentId: String, name: String, mime: String, file: File) = image(name, mime)
}

private class TreeDrive : FakeDrive() {
    val downloaded = ArrayList<String>()

    override suspend fun list(token: String, folderId: String, pageToken: String?): DrivePage {
        coroutineContext.ensureActive()
        if (folderId == "root" && pageToken == null) {
            return DrivePage(listOf(image("1", "a.jpg"), folder("sub", "Sub")), "p2")
        }
        if (folderId == "root") return DrivePage(listOf(image("2", "b.png"), doc("3", "c.txt")), null)
        return DrivePage(listOf(image("4", "d.webp")), null)
    }

    override suspend fun download(token: String, fileId: String, dest: File, onBytes: (Long) -> Unit) {
        downloaded += fileId
        dest.writeBytes(byteArrayOf(1))
    }
}

private class ExportDrive : FakeDrive() {
    val parents = ArrayList<String>()
    var created = 0
    var revoked = false

    override suspend fun upload(token: String, parentId: String, name: String, mime: String, file: File): DriveEntry {
        if (revoked) throw DriveFailure(DriveProblem.REVOKED, "revoked")
        if (parentId == "denied") throw DriveFailure(DriveProblem.FAILED, "no")
        parents += parentId
        return image("saved", mime)
    }

    override suspend fun createFolder(token: String, name: String): DriveEntry {
        created++
        return folder("mosaic-id", name)
    }
}

private fun image(id: String, name: String) = DriveEntry(id, name, if (name.startsWith("image")) name else "image/jpeg", "t$id", 10, "")

private fun folder(id: String, name: String) = DriveEntry(id, name, DRIVE_FOLDER_MIME, "", 0, "")

private fun doc(id: String, name: String) = DriveEntry(id, name, "text/plain", "", 0, "")

private fun tempDir(): File = File(System.getProperty("java.io.tmpdir"), "drive-${System.nanoTime()}").apply { mkdirs() }

private fun tempFile(name: String): File = File(tempDir(), name)
