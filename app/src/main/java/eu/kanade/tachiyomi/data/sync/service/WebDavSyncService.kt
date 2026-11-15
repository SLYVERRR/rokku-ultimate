package eu.kanade.tachiyomi.data.sync.service

import android.content.Context
import co.touchlab.kermit.Logger
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.sync.SyncNotifier
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.PUT
import eu.kanade.tachiyomi.network.await
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class WebDavSyncService(
    context: Context,
    json: Json,
    syncPreferences: SyncPreferences,
    private val notifier: SyncNotifier,
    private val protoBuf: ProtoBuf = Injekt.get(),
) : SyncService(context, json, syncPreferences) {

    private class WebDavException(message: String?) : Exception(message)

    private val url: String = syncPreferences.webDavUrl().get().trim()
    private val folder: String = syncPreferences.webDavFolder().get().trim('/')
    private val username: String = syncPreferences.webDavUsername().get().trim()
    private val password: String = syncPreferences.webDavPassword().get()
    private val credentials: String = Credentials.basic(username, password)

    private fun buildWebDavFileUrl(fileName: String = "backup.proto"): String {
        val cleanBase = url.trimEnd('/')
        return if (folder.isNotEmpty()) "$cleanBase/$folder/$fileName" else "$cleanBase/$fileName"
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .build()
    }

    private fun validateSettings(): Boolean {
        if (url.isEmpty() || !url.startsWith("http")) {
            notifier.showSyncError("Invalid WebDAV URL. Please check your settings.")
            return false
        }
        if (username.isBlank() || password.isBlank()) {
            notifier.showSyncError("Username or password cannot be empty.")
            return false
        }
        return true
    }

    private suspend fun ensureFolderExists() {
        if (folder.isEmpty() || !validateSettings()) return
        val baseUrl = url.trimEnd('/')
        val folderParts = folder.split('/').filter { it.isNotEmpty() }
        if (folderParts.isEmpty()) return

        var currentPath = baseUrl
        for (part in folderParts) {
            currentPath = "$currentPath/$part"
            if (!createSingleFolder(currentPath)) {
                throw WebDavException("Failed to create folder: $currentPath")
            }
        }
    }

    private suspend fun createSingleFolder(folderUrl: String): Boolean {
        val request = Request.Builder()
            .url(folderUrl)
            .method("MKCOL", null)
            .header("Authorization", credentials)
            .header("Content-Length", "0")
            .build()

        val response = client.newCall(request).await()
        val success = response.isSuccessful || response.code == 405 || response.code == 409
        response.close()
        return success
    }

    override suspend fun doSync(syncData: SyncData): Backup? {
        ensureFolderExists()
        try {
            val (remoteData, etag) = pullSyncData()

            val finalSyncData = if (remoteData != null) {
                Logger.d { "Updating remote WebDAV data with ETag($etag)" }
                mergeSyncData(syncData, remoteData)
            } else {
                Logger.d { "Uploading initial WebDAV data with ETag($etag)" }
                syncData
            }

            pushSyncData(finalSyncData, etag)
            return finalSyncData.backup
        } catch (e: Exception) {
            Logger.e(e) { "WebDAV sync error: ${e.message}" }
            notifier.showSyncError(e.message)
            return null
        }
    }

    private suspend fun pullSyncData(): Pair<SyncData?, String> {
        if (!validateSettings()) return Pair(null, "")

        val lastETag = syncPreferences.lastSyncEtag().get()
        val headersBuilder = Headers.Builder().add("Authorization", credentials)
        if (lastETag.isNotEmpty()) headersBuilder.add("If-None-Match", lastETag)

        val requestUrl = buildWebDavFileUrl()
        val request = GET(requestUrl, headers = headersBuilder.build())
        val response = client.newCall(request).await()

        return when (response.code) {
            304 -> Pair(null, lastETag) // Not modified

            404 -> Pair(null, "") // File not yet created

            else -> {
                if (response.isSuccessful) {
                    val newETag = response.headers["ETag"]?.trim('"') ?: ""
                    val rawBytes = response.body.byteStream().use { it.readBytes() }
                    val bytes = if (rawBytes.size >= 2 && rawBytes[0] == 0x1f.toByte() && rawBytes[1] == 0x8b.toByte()) {
                        try {
                            GZIPInputStream(rawBytes.inputStream()).use { it.readBytes() }
                        } catch (e: Exception) {
                            Logger.e(e) { "Failed to decompress gzipped WebDAV backup, using raw bytes" }
                            rawBytes
                        }
                    } else {
                        rawBytes
                    }
                    try {
                        val backup = protoBuf.decodeFromByteArray(Backup.serializer(), bytes)
                        Pair(SyncData(backup = backup), newETag)
                    } catch (e: Exception) {
                        Logger.e(e) { "Invalid backup format from WebDAV" }
                        Pair(null, "")
                    }
                } else {
                    val body = response.body.string()
                    throw WebDavException("Failed to download from WebDAV: $body")
                }
            }
        }
    }

    private suspend fun pushSyncData(syncData: SyncData, eTag: String) {
        val backup = syncData.backup ?: return
        if (!validateSettings()) return

        val byteArray = protoBuf.encodeToByteArray(Backup.serializer(), backup)
        if (byteArray.isEmpty()) throw IllegalStateException("Backup data is empty")

        val compressedBytes = ByteArrayOutputStream().use { bos ->
            GZIPOutputStream(bos).use { gzos ->
                gzos.write(byteArray)
            }
            bos.toByteArray()
        }

        val body = compressedBytes.toRequestBody("application/octet-stream".toMediaType())
        val headersBuilder = Headers.Builder().add("Authorization", credentials)
        if (eTag.isNotEmpty()) headersBuilder.add("If-Match", eTag)

        val requestUrl = buildWebDavFileUrl()
        val request = PUT(requestUrl, headers = headersBuilder.build(), body = body)
        val response = client.newCall(request).await()

        when {
            response.isSuccessful -> {
                val newETag = response.headers["ETag"]?.trim('"') ?: ""
                if (newETag.isNotEmpty()) syncPreferences.lastSyncEtag().set(newETag)
                Logger.i { "WebDAV sync upload completed successfully" }
            }

            response.code == 412 -> {
                val message = "Sync conflict detected. Another device modified the remote backup. Retrying next sync."
                Logger.w { message }
                notifier.showSyncError(message)
            }

            else -> {
                val bodyStr = response.body.string()
                throw WebDavException("WebDAV upload failed: $bodyStr")
            }
        }
    }
}
