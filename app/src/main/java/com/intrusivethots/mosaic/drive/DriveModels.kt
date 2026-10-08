package com.intrusivethots.mosaic.drive

/**
 * [DRIVE_READ_SCOPE] is the narrowest scope that can list folders the person already has.
 * `drive.file` only sees files this app created, so it cannot be the library scope.
 * It is a restricted scope: an unpublished Cloud project can grant it only to test users,
 * and production use needs Google's OAuth verification plus a security assessment.
 *
 * [DRIVE_FILE_SCOPE] is non-sensitive. Mosaic requests it only to create the Mosaic folder
 * and upload a finished picture. Writing into any other folder is left to the system picker.
 */
internal const val DRIVE_READ_SCOPE = "https://www.googleapis.com/auth/drive.readonly"
internal const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
internal const val DRIVE_FOLDER_MIME = "application/vnd.google-apps.folder"
internal const val DRIVE_ROOT = "root"

internal class DriveAccount(val email: String, val displayName: String)

internal class DriveEntry(
    val id: String,
    val name: String,
    val mimeType: String,
    val modifiedTime: String,
    val size: Long,
    val thumbnailLink: String
) {
    val folder: Boolean get() = mimeType == DRIVE_FOLDER_MIME
    val image: Boolean get() = isLibraryImage(mimeType, name)
}

internal class DrivePage(val entries: List<DriveEntry>, val nextPageToken: String?)

internal enum class DriveProblem { OFFLINE, UNAUTHORIZED, REVOKED, FAILED }

internal class DriveFailure(val problem: DriveProblem, message: String) : Exception(message)

internal fun driveMessage(failure: DriveFailure): String {
    return when (failure.problem) {
        DriveProblem.OFFLINE -> "You're offline. Drive folders need a connection. Photos already added still work."
        DriveProblem.UNAUTHORIZED, DriveProblem.REVOKED -> "Google Drive access expired or was revoked. Connect again."
        DriveProblem.FAILED -> failure.message ?: "Google Drive could not complete that."
    }
}

internal fun mosaicExportName(title: String): String {
    val clean = title.map { char ->
        if (char.isLetterOrDigit() || char == '-' || char == '_') char else '_'
    }.joinToString("").ifBlank { "Mosaic" }.take(80)
    return "$clean.png"
}

internal fun isLibraryImage(mime: String?, name: String): Boolean {
    val type = mime?.lowercase().orEmpty()
    if (type == "image/svg+xml") return false
    if (type.startsWith("image/")) return true
    val lower = name.lowercase()
    return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
        lower.endsWith(".webp") || lower.endsWith(".heic") || lower.endsWith(".heif") ||
        lower.endsWith(".gif")
}

internal fun classifyDriveResponse(code: Int, body: String, ioFailure: Boolean): DriveFailure? {
    if (ioFailure) return DriveFailure(DriveProblem.OFFLINE, "offline")
    if (code in 200..299) return null
    if (code == 401) return DriveFailure(DriveProblem.UNAUTHORIZED, "unauthorized")
    val revoked = code == 403 && (
        body.contains("insufficientPermissions") ||
            body.contains("ACCESS_TOKEN_SCOPE_INSUFFICIENT") ||
            body.contains("authError")
        )
    if (revoked) return DriveFailure(DriveProblem.REVOKED, "revoked")
    val detail = errorMessage(body) ?: "Drive returned $code."
    return DriveFailure(DriveProblem.FAILED, detail)
}

private fun errorMessage(body: String): String? {
    val parsed = runCatching { parseJson(body) }.getOrNull() ?: return null
    return parsed.obj("error")?.string("message")?.takeIf { it.isNotBlank() }
}
