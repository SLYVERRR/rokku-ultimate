package eu.kanade.tachiyomi.data.sync.service

import android.content.Context
import android.os.Build
import co.touchlab.kermit.Logger
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.sync.SyncNotifier
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.PUT
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SyncYomiSyncService(
    context: Context,
    json: Json,
    syncPreferences: SyncPreferences,
    private val notifier: SyncNotifier,
    private val protoBuf: ProtoBuf = Injekt.get(),
) : SyncService(context, json, syncPreferences) {

    companion object {
        private val client by lazy { OkHttpClient() }
    }

    private class SyncYomiException(message: String?) : Exception(message)

    @Serializable
    private data class SyncEvent(
        val event: SyncEventStatus,
        @SerialName("device_name")
        val deviceName: String? = null,
        val message: String? = null,
    )

    @Serializable
    private enum class SyncEventStatus {
        SYNC_STARTED,
        SYNC_SUCCESS,
        SYNC_FAILED,
        SYNC_ERROR,
        SYNC_CANCELLED,
    }

    override suspend fun doSync(syncData: SyncData): Backup? {
        reportSyncEvent(SyncEventStatus.SYNC_STARTED)

        try {
            val (remoteData, etag) = pullSyncData()

            val finalSyncData = if (remoteData != null) {
                Logger.d { "Updating remote SyncYomi data with ETag($etag)" }
                mergeSyncData(syncData, remoteData)
            } else {
                Logger.d { "Uploading initial SyncYomi data with ETag($etag)" }
                syncData
            }

            val success = pushSyncData(finalSyncData, etag)
            if (success) {
                reportSyncEvent(SyncEventStatus.SYNC_SUCCESS)
            } else {
                reportSyncEvent(SyncEventStatus.SYNC_FAILED, "Failed to upload sync data")
                return null
            }

            return finalSyncData.backup
        } catch (e: Exception) {
            if (e is CancellationException) {
                reportSyncEvent(SyncEventStatus.SYNC_CANCELLED, e.message)
                throw e
            }
            Logger.e(e) { "SyncYomi error: ${e.message}" }
            notifier.showSyncError(e.message)
            reportSyncEvent(SyncEventStatus.SYNC_ERROR, e.message)
            return null
        }
    }

    private suspend fun pullSyncData(): Pair<SyncData?, String> {
        val host = syncPreferences.clientHost().get().trimEnd('/')
        val apiKey = syncPreferences.clientAPIKey().get().trim()
        if (host.isEmpty() || !host.startsWith("http")) {
            throw SyncYomiException("Invalid SyncYomi server URL")
        }

        val downloadUrl = "$host/api/sync/content"
        val headersBuilder = Headers.Builder().add("X-API-Token", apiKey)
        val lastETag = syncPreferences.lastSyncEtag().get()
        if (lastETag.isNotEmpty()) {
            headersBuilder.add("If-None-Match", lastETag)
        }

        val downloadRequest = GET(url = downloadUrl, headers = headersBuilder.build())
        val response = client.newCall(downloadRequest).await()

        return when (response.code) {
            304 -> Pair(null, lastETag)
            404 -> Pair(null, "")
            else -> {
                if (response.isSuccessful) {
                    val newETag = response.headers["ETag"]?.trim('"')
                        ?: throw SyncYomiException("Missing ETag from SyncYomi response")
                    val byteArray = response.body.byteStream().use { it.readBytes() }
                    try {
                        val backup = protoBuf.decodeFromByteArray(Backup.serializer(), byteArray)
                        Pair(SyncData(backup = backup), newETag)
                    } catch (e: Exception) {
                        Logger.e(e) { "Bad content response from SyncYomi" }
                        Pair(null, "")
                    }
                } else {
                    val responseBody = response.body.string()
                    notifier.showSyncError("Failed to download sync data: $responseBody")
                    throw SyncYomiException("Failed to download sync data: $responseBody")
                }
            }
        }
    }

    private suspend fun pushSyncData(syncData: SyncData, eTag: String): Boolean {
        val backup = syncData.backup ?: return true
        val host = syncPreferences.clientHost().get().trimEnd('/')
        val apiKey = syncPreferences.clientAPIKey().get().trim()
        val uploadUrl = "$host/api/sync/content"

        val headersBuilder = Headers.Builder().add("X-API-Token", apiKey)
        if (eTag.isNotEmpty()) {
            headersBuilder.add("If-Match", eTag)
        }

        val byteArray = protoBuf.encodeToByteArray(Backup.serializer(), backup)
        if (byteArray.isEmpty()) {
            throw IllegalStateException("Sync backup data is empty")
        }
        val body = byteArray.toRequestBody("application/octet-stream".toMediaType())

        val uploadRequest = PUT(url = uploadUrl, headers = headersBuilder.build(), body = body)
        return client.newCall(uploadRequest).await().use { response ->
            if (response.isSuccessful) {
                val newETag = response.headers["ETag"]?.trim('"')
                    ?: throw SyncYomiException("Missing ETag from response")
                syncPreferences.lastSyncEtag().set(newETag)
                Logger.i { "SyncYomi upload completed successfully" }
                true
            } else if (response.code == 412) {
                Logger.w { "SyncYomi upload conflict (412)" }
                false
            } else {
                val responseBody = response.body.string()
                notifier.showSyncError("Failed to upload sync data: $responseBody")
                false
            }
        }
    }

    private suspend fun reportSyncEvent(event: SyncEventStatus, message: String? = null) {
        withContext(NonCancellable) {
            try {
                val host = syncPreferences.clientHost().get().trimEnd('/')
                val apiKey = syncPreferences.clientAPIKey().get().trim()
                if (host.isEmpty() || !host.startsWith("http")) return@withContext

                val url = "$host/api/sync/event"
                val headers = Headers.Builder().add("X-API-Token", apiKey).build()

                val bodyObj = SyncEvent(
                    event = event,
                    deviceName = Build.MODEL,
                    message = message,
                )

                val jsonBody = json.encodeToString(SyncEvent.serializer(), bodyObj)
                val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = POST(url = url, headers = headers, body = requestBody)

                client.newCall(request).await().close()
            } catch (e: Exception) {
                Logger.e(e) { "Failed to report sync event" }
            }
        }
    }
}
