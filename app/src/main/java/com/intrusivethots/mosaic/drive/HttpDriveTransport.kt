package com.intrusivethots.mosaic.drive

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal class HttpDriveTransport : DriveTransport {
    override fun get(url: String, token: String): DriveBytes {
        val connection = resolve(url, token) ?: return DriveBytes(0, ByteArray(0), true)
        return try {
            val code = connection.responseCode
            DriveBytes(code, readStream(connection, code in 200..299), false)
        } catch (failure: IOException) {
            DriveBytes(0, ByteArray(0), true)
        } finally {
            connection.disconnect()
        }
    }

    override fun post(url: String, token: String, body: ByteArray, contentType: String): DriveBytes {
        return buffered("POST", url, token, body, contentType)
    }

    override fun upload(url: String, token: String, contentType: String, writeBody: (java.io.OutputStream) -> Unit): DriveBytes {
        val connection = open("POST", url, token, follow = true) ?: return DriveBytes(0, ByteArray(0), true)
        return try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            connection.outputStream.use { output -> writeBody(output) }
            val code = connection.responseCode
            DriveBytes(code, readStream(connection, code in 200..299), false)
        } catch (failure: IOException) {
            DriveBytes(0, ByteArray(0), true)
        } finally {
            connection.disconnect()
        }
    }

    override fun download(url: String, token: String, dest: File, onBytes: (Long) -> Unit): DriveBytes {
        val connection = resolve(url, token) ?: return DriveBytes(0, ByteArray(0), true)
        return try {
            readDownload(connection, dest, onBytes)
        } catch (failure: IOException) {
            DriveBytes(0, ByteArray(0), true)
        } finally {
            connection.disconnect()
        }
    }

    private fun resolve(url: String, token: String): HttpURLConnection? {
        var connection = open("GET", url, token, follow = false) ?: return null
        var hops = 0
        while (hops < 4) {
            val code = try {
                connection.responseCode
            } catch (failure: IOException) {
                connection.disconnect()
                return null
            }
            if (code !in 300..399) return connection
            val next = connection.getHeaderField("Location")
            connection.disconnect()
            if (next.isNullOrBlank()) return null
            connection = open("GET", next, token, follow = false) ?: return null
            hops++
        }
        return connection
    }

    private fun readDownload(connection: HttpURLConnection, dest: File, onBytes: (Long) -> Unit): DriveBytes {
        val code = connection.responseCode
        if (code !in 200..299) return DriveBytes(code, readStream(connection, false), false)
        dest.parentFile?.mkdirs()
        connection.inputStream.use { input -> dest.outputStream().use { output -> copyStream(input, output, onBytes) } }
        return DriveBytes(code, ByteArray(0), false)
    }

    private fun copyStream(input: java.io.InputStream, output: java.io.OutputStream, onBytes: (Long) -> Unit) {
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            output.write(buffer, 0, read)
            total += read
            onBytes(total)
        }
    }

    private fun buffered(method: String, url: String, token: String, body: ByteArray?, contentType: String?): DriveBytes {
        val connection = open(method, url, token, follow = true) ?: return DriveBytes(0, ByteArray(0), true)
        return try {
            if (body != null) {
                connection.doOutput = true
                if (contentType != null) connection.setRequestProperty("Content-Type", contentType)
                connection.outputStream.use { output -> output.write(body) }
            }
            val code = connection.responseCode
            DriveBytes(code, readStream(connection, code in 200..299), false)
        } catch (failure: IOException) {
            DriveBytes(0, ByteArray(0), true)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(method: String, url: String, token: String, follow: Boolean): HttpURLConnection? {
        return try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                instanceFollowRedirects = follow
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "*/*")
            }
        } catch (failure: IOException) {
            null
        }
    }

    private fun readStream(connection: HttpURLConnection, success: Boolean): ByteArray {
        val stream = if (success) connection.inputStream else connection.errorStream
        return stream?.use { input -> input.readBytes() } ?: ByteArray(0)
    }
}
