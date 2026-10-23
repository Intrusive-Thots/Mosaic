package com.intrusivethots.mosaic.drive

import java.io.File
import java.net.URLEncoder

internal interface DriveClient {
    suspend fun about(token: String): DriveAccount

    suspend fun list(token: String, folderId: String, pageToken: String?): DrivePage

    suspend fun thumbnail(token: String, link: String): ByteArray?

    suspend fun download(token: String, fileId: String, dest: File, onBytes: (Long) -> Unit)

    suspend fun createFolder(token: String, name: String): DriveEntry

    suspend fun findFolder(token: String, name: String): DriveEntry?

    suspend fun upload(token: String, parentId: String, name: String, mime: String, file: File): DriveEntry
}

internal interface DriveTransport {
    fun get(url: String, token: String): DriveBytes

    fun post(url: String, token: String, body: ByteArray, contentType: String): DriveBytes

    fun download(url: String, token: String, dest: File, onBytes: (Long) -> Unit): DriveBytes

    fun upload(url: String, token: String, contentType: String, writeBody: (java.io.OutputStream) -> Unit): DriveBytes
}

internal class DriveBytes(val code: Int, val body: ByteArray, val ioFailure: Boolean = false)

internal class RestDriveClient(private val transport: DriveTransport) : DriveClient {
    override suspend fun about(token: String): DriveAccount {
        val response = transport.get("$ROOT/about?fields=user(emailAddress,displayName)", token)
        val failure = classifyDriveResponse(response.code, response.text(), response.ioFailure)
        if (failure != null) throw failure
        val user = parseJson(response.text()).obj("user")
        return DriveAccount(
            email = user?.string("emailAddress").orEmpty(),
            displayName = user?.string("displayName").orEmpty()
        )
    }

    override suspend fun list(token: String, folderId: String, pageToken: String?): DrivePage {
        val response = transport.get(listUrl(folderId, pageToken), token)
        val failure = classifyDriveResponse(response.code, response.text(), response.ioFailure)
        if (failure != null) throw failure
        return parsePage(response.text())
    }

    override suspend fun thumbnail(token: String, link: String): ByteArray? {
        if (link.isBlank()) return null
        val response = transport.get(link, token)
        if (response.ioFailure || response.code !in 200..299 || response.body.isEmpty()) return null
        return response.body
    }

    override suspend fun download(token: String, fileId: String, dest: File, onBytes: (Long) -> Unit) {
        val response = transport.download("$ROOT/files/$fileId?alt=media", token, dest, onBytes)
        val failure = classifyDriveResponse(response.code, response.text(), response.ioFailure)
        if (failure != null) throw failure
    }

    override suspend fun createFolder(token: String, name: String): DriveEntry {
        val body = """{"name":"${jsonEscape(name)}","mimeType":"$DRIVE_FOLDER_MIME"}""".toByteArray()
        val response = transport.post("$ROOT/files?fields=id,name,mimeType", token, body, "application/json; charset=UTF-8")
        val failure = classifyDriveResponse(response.code, response.text(), response.ioFailure)
        if (failure != null) throw failure
        return parseEntry(parseJson(response.text()))
    }

    override suspend fun findFolder(token: String, name: String): DriveEntry? {
        val safe = name.replace("'", "")
        val query = "name = '$safe' and mimeType = '$DRIVE_FOLDER_MIME' and trashed = false"
        val url = "$ROOT/files?pageSize=1&fields=${encode("files(id,name,mimeType)")}&q=${encode(query)}"
        val response = transport.get(url, token)
        val failure = classifyDriveResponse(response.code, response.text(), response.ioFailure)
        if (failure != null) throw failure
        return parsePage(response.text()).entries.firstOrNull()
    }

    override suspend fun upload(token: String, parentId: String, name: String, mime: String, file: File): DriveEntry {
        val boundary = "mosaic${file.length()}${name.length}"
        val meta = """{"name":"${jsonEscape(name)}","parents":["${jsonEscape(parentId)}"]}"""
        val head = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$boundary\r\nContent-Type: $mime\r\n\r\n"
        val tail = "\r\n--$boundary--\r\n"
        val response = transport.upload(
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,mimeType",
            token,
            "multipart/related; boundary=$boundary"
        ) { output ->
            output.write(head.toByteArray())
            file.inputStream().use { input -> input.copyTo(output) }
            output.write(tail.toByteArray())
        }
        val failure = classifyDriveResponse(response.code, response.text(), response.ioFailure)
        if (failure != null) throw failure
        return parseEntry(parseJson(response.text()))
    }

    private fun DriveBytes.text(): String = body.toString(Charsets.UTF_8)
}

internal fun listUrl(folderId: String, pageToken: String?): String {
    val safeId = folderId.replace("'", "")
    val query = "'$safeId' in parents and trashed = false"
    val builder = StringBuilder("https://www.googleapis.com/drive/v3/files?pageSize=40")
    builder.append("&orderBy=").append(encode("folder,name"))
    builder.append("&fields=").append(encode("nextPageToken,files(id,name,mimeType,thumbnailLink,modifiedTime,size)"))
    builder.append("&q=").append(encode(query))
    if (!pageToken.isNullOrBlank()) builder.append("&pageToken=").append(encode(pageToken))
    return builder.toString()
}

internal fun parsePage(json: String): DrivePage {
    val root = parseJson(json)
    val entries = root.array("files").map { parseEntry(it) }
    return DrivePage(entries, root.string("nextPageToken"))
}

private fun parseEntry(value: JsonValue): DriveEntry = DriveEntry(
    id = value.string("id").orEmpty(),
    name = value.string("name").orEmpty(),
    mimeType = value.string("mimeType").orEmpty(),
    modifiedTime = value.string("modifiedTime").orEmpty(),
    size = value.long("size"),
    thumbnailLink = value.string("thumbnailLink").orEmpty()
)

private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

private const val ROOT = "https://www.googleapis.com/drive/v3"
