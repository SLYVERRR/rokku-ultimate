package eu.kanade.tachiyomi.data.sync.service

import android.content.Context
import co.touchlab.kermit.Logger
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.sync.SyncNotifier
import eu.kanade.tachiyomi.network.await
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.ConnectionPool
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class GoogleDriveSyncService(
    context: Context,
    json: Json,
    syncPreferences: SyncPreferences,
    private val notifier: SyncNotifier,
    private val protoBuf: ProtoBuf = Injekt.get(),
) : SyncService(context, json, syncPreferences) {

    @Serializable
    private data class GoogleTokenResponse(
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long? = null,
        val scope: String? = null,
        @SerialName("token_type") val tokenType: String? = null,
        val error: String? = null,
        @SerialName("error_description") val errorDescription: String? = null,
    )

    @Serializable
    private data class GoogleAboutResponse(
        val user: GoogleUser? = null,
    )

    @Serializable
    private data class GoogleUser(
        val displayName: String? = null,
        val emailAddress: String? = null,
    )

    @Serializable
    private data class GoogleDriveFile(
        val id: String = "",
        val name: String = "",
        val mimeType: String = "",
        val modifiedTime: String? = null,
        val version: String? = null,
        val md5Checksum: String? = null,
        val appProperties: Map<String, String>? = null,
    )

    @Serializable
    private data class GoogleDriveFileList(
        val files: List<GoogleDriveFile> = emptyList(),
    )

    companion object {
        const val CLIENT_ID = "1072944905499-vm2v2i5dvn0a0d2o4ca36i1vge8cvbn0.apps.googleusercontent.com"
        val CLIENT_SECRET: String by lazy {
            String(java.util.Base64.getDecoder().decode("djZWM2ZLVl96V1U3aXcxRHJwTzFya25Y"))
        }
        const val REDIRECT_URI = "eu.kanade.google.oauth:/oauth2redirect"
        const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth?client_id=$CLIENT_ID&redirect_uri=$REDIRECT_URI&response_type=code&scope=https://www.googleapis.com/auth/drive.file&access_type=offline&prompt=consent"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val DRIVE_API_URL = "https://www.googleapis.com/drive/v3"
        const val DRIVE_UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3"

        private val jsonParser = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
                .build()
        }

        fun getAuthUrl(): String = AUTH_URL

        suspend fun exchangeCodeForTokens(code: String, prefs: SyncPreferences): Boolean {
            val body = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .add("code", code)
                .add("grant_type", "authorization_code")
                .add("redirect_uri", REDIRECT_URI)
                .build()

            val request = Request.Builder()
                .url(TOKEN_URL)
                .post(body)
                .build()

            val response = client.newCall(request).await()
            val responseBody = response.body.string()
            if (!response.isSuccessful) {
                Logger.e { "Failed to exchange code: $responseBody" }
                return false
            }

            val tokenResp = jsonParser.decodeFromString<GoogleTokenResponse>(responseBody)
            val accessToken = tokenResp.accessToken ?: return false
            val refreshToken = tokenResp.refreshToken

            prefs.googleDriveAccessToken().set(accessToken)
            if (!refreshToken.isNullOrEmpty()) {
                prefs.googleDriveRefreshToken().set(refreshToken)
            }

            try {
                val userReq = Request.Builder()
                    .url("$DRIVE_API_URL/about?fields=user(emailAddress,displayName)")
                    .header("Authorization", "Bearer $accessToken")
                    .build()
                val userResp = client.newCall(userReq).await()
                if (userResp.isSuccessful) {
                    val about = jsonParser.decodeFromString<GoogleAboutResponse>(userResp.body.string())
                    val email = about.user?.emailAddress ?: ""
                    if (email.isNotEmpty()) {
                        prefs.googleDriveUserEmail().set(email)
                    }
                }
            } catch (e: Exception) {
                Logger.w(e) { "Failed to fetch user email" }
            }

            return true
        }
    }

    private fun String.encodeUrl(): String = URLEncoder.encode(this, "UTF-8")

    private suspend fun ensureValidAccessToken(): String {
        val refreshToken = syncPreferences.googleDriveRefreshToken().get().trim()
        if (refreshToken.isEmpty()) {
            throw IllegalStateException("Google Drive is not connected. Please sign in in Settings.")
        }

        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("client_secret", CLIENT_SECRET)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .build()

        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(body)
            .build()

        val response = client.newCall(request).await()
        val responseBody = response.body.string()
        if (!response.isSuccessful) {
            throw IllegalStateException("Failed to refresh Google Drive token: $responseBody")
        }

        val tokenResp = jsonParser.decodeFromString<GoogleTokenResponse>(responseBody)
        val accessToken = tokenResp.accessToken ?: throw IllegalStateException("Empty access token received")
        syncPreferences.googleDriveAccessToken().set(accessToken)
        return accessToken
    }

    private suspend fun getOrCreateFolderId(accessToken: String, folderName: String): String {
        val query = "mimeType = 'application/vnd.google-apps.folder' and name = '$folderName' and trashed = false"
        val url = "$DRIVE_API_URL/files?q=${query.encodeUrl()}&fields=files(id,name)"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .build()

        val response = client.newCall(request).await()
        if (response.isSuccessful) {
            val list = jsonParser.decodeFromString<GoogleDriveFileList>(response.body.string())
            if (list.files.isNotEmpty()) {
                return list.files[0].id
            }
        }

        val createJson = """{"name":"$folderName","mimeType":"application/vnd.google-apps.folder"}"""
        val createReq = Request.Builder()
            .url("$DRIVE_API_URL/files")
            .header("Authorization", "Bearer $accessToken")
            .post(createJson.toRequestBody("application/json".toMediaType()))
            .build()

        val createResp = client.newCall(createReq).await()
        if (!createResp.isSuccessful) {
            val err = createResp.body.string()
            throw IllegalStateException("Failed to create Google Drive folder: $err")
        }

        val createdFile = jsonParser.decodeFromString<GoogleDriveFile>(createResp.body.string())
        return createdFile.id
    }

    private suspend fun pullSyncData(accessToken: String, folderId: String): Pair<SyncData?, String> {
        val query = "'$folderId' in parents and name = 'backup.proto' and trashed = false"
        val url = "$DRIVE_API_URL/files?q=${query.encodeUrl()}&fields=files(id,name,version,modifiedTime,md5Checksum,appProperties)"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .build()

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            val err = response.body.string()
            throw IllegalStateException("Failed to query Google Drive backup file: $err")
        }

        val fileList = jsonParser.decodeFromString<GoogleDriveFileList>(response.body.string())
        if (fileList.files.isEmpty()) {
            return Pair(null, "")
        }

        val file = fileList.files[0]
        val version = file.version ?: file.modifiedTime ?: file.id
        val lastVersion = syncPreferences.googleDriveLastVersion().get()

        if (lastVersion.isNotEmpty() && lastVersion == version) {
            // Remote file unmodified since last sync
            return Pair(null, version)
        }

        val downloadReq = Request.Builder()
            .url("$DRIVE_API_URL/files/${file.id}?alt=media")
            .header("Authorization", "Bearer $accessToken")
            .build()

        val downloadResp = client.newCall(downloadReq).await()
        if (!downloadResp.isSuccessful) {
            val err = downloadResp.body.string()
            throw IllegalStateException("Failed to download Google Drive backup file: $err")
        }

        val rawBytes = downloadResp.body.byteStream().use { it.readBytes() }
        val bytes = if (rawBytes.size >= 2 && rawBytes[0] == 0x1f.toByte() && rawBytes[1] == 0x8b.toByte()) {
            try {
                GZIPInputStream(rawBytes.inputStream()).use { it.readBytes() }
            } catch (e: Exception) {
                Logger.e(e) { "Failed to decompress gzipped backup from Google Drive, using raw bytes" }
                rawBytes
            }
        } else {
            rawBytes
        }

        return try {
            val backup = protoBuf.decodeFromByteArray(Backup.serializer(), bytes)
            val deviceId = file.appProperties?.get("deviceId") ?: ""
            Pair(SyncData(deviceId = deviceId, backup = backup), version)
        } catch (e: Exception) {
            Logger.e(e) { "Invalid backup format from Google Drive" }
            Pair(null, "")
        }
    }

    private suspend fun pushSyncData(accessToken: String, folderId: String, syncData: SyncData) {
        val backup = syncData.backup ?: return

        val byteArray = protoBuf.encodeToByteArray(Backup.serializer(), backup)
        if (byteArray.isEmpty()) throw IllegalStateException("Backup data is empty")

        val compressedBytes = ByteArrayOutputStream().use { bos ->
            GZIPOutputStream(bos).use { gzos ->
                gzos.write(byteArray)
            }
            bos.toByteArray()
        }

        val query = "'$folderId' in parents and name = 'backup.proto' and trashed = false"
        val listReq = Request.Builder()
            .url("$DRIVE_API_URL/files?q=${query.encodeUrl()}&fields=files(id)")
            .header("Authorization", "Bearer $accessToken")
            .build()

        val listResp = client.newCall(listReq).await()
        val fileId = if (listResp.isSuccessful) {
            val list = jsonParser.decodeFromString<GoogleDriveFileList>(listResp.body.string())
            list.files.firstOrNull()?.id
        } else {
            null
        }

        val body = compressedBytes.toRequestBody("application/octet-stream".toMediaType())

        if (fileId != null) {
            val updateReq = Request.Builder()
                .url("$DRIVE_UPLOAD_URL/files/$fileId?uploadType=media")
                .header("Authorization", "Bearer $accessToken")
                .patch(body)
                .build()

            val updateResp = client.newCall(updateReq).await()
            if (!updateResp.isSuccessful) {
                val err = updateResp.body.string()
                throw IllegalStateException("Failed to update Google Drive backup: $err")
            }

            val metaJson = """{"appProperties":{"deviceId":"${syncData.deviceId}"}}"""
            val metaReq = Request.Builder()
                .url("$DRIVE_API_URL/files/$fileId?fields=version,modifiedTime")
                .header("Authorization", "Bearer $accessToken")
                .patch(metaJson.toRequestBody("application/json".toMediaType()))
                .build()

            val metaResp = client.newCall(metaReq).await()
            if (metaResp.isSuccessful) {
                val updatedFile = jsonParser.decodeFromString<GoogleDriveFile>(metaResp.body.string())
                val newVer = updatedFile.version ?: updatedFile.modifiedTime ?: ""
                if (newVer.isNotEmpty()) syncPreferences.googleDriveLastVersion().set(newVer)
            }
        } else {
            val createJson = """{"name":"backup.proto","parents":["$folderId"],"appProperties":{"deviceId":"${syncData.deviceId}"}}"""
            val createReq = Request.Builder()
                .url("$DRIVE_API_URL/files")
                .header("Authorization", "Bearer $accessToken")
                .post(createJson.toRequestBody("application/json".toMediaType()))
                .build()

            val createResp = client.newCall(createReq).await()
            if (!createResp.isSuccessful) {
                val err = createResp.body.string()
                throw IllegalStateException("Failed to create Google Drive backup file metadata: $err")
            }

            val newFile = jsonParser.decodeFromString<GoogleDriveFile>(createResp.body.string())
            val newFileId = newFile.id

            val uploadReq = Request.Builder()
                .url("$DRIVE_UPLOAD_URL/files/$newFileId?uploadType=media")
                .header("Authorization", "Bearer $accessToken")
                .patch(body)
                .build()

            val uploadResp = client.newCall(uploadReq).await()
            if (!uploadResp.isSuccessful) {
                val err = uploadResp.body.string()
                throw IllegalStateException("Failed to upload content to Google Drive backup: $err")
            }

            val uploadedFile = jsonParser.decodeFromString<GoogleDriveFile>(uploadResp.body.string())
            val newVer = uploadedFile.version ?: uploadedFile.modifiedTime ?: ""
            if (newVer.isNotEmpty()) syncPreferences.googleDriveLastVersion().set(newVer)
        }
        Logger.i { "Google Drive sync upload completed successfully" }
    }

    override suspend fun doSync(syncData: SyncData): Backup? {
        val refreshToken = syncPreferences.googleDriveRefreshToken().get().trim()
        if (refreshToken.isEmpty()) {
            notifier.showSyncError("Google Drive is not connected. Please sign in in Settings.")
            return null
        }

        try {
            val accessToken = ensureValidAccessToken()
            val folderName = syncPreferences.googleDriveFolder().get().trim().ifEmpty { "Rokku" }
            val folderId = getOrCreateFolderId(accessToken, folderName)

            val (remoteData, version) = pullSyncData(accessToken, folderId)

            val finalSyncData = if (remoteData != null) {
                Logger.d { "Updating remote Google Drive data with version($version)" }
                mergeSyncData(syncData, remoteData)
            } else {
                Logger.d { "Uploading initial Google Drive data" }
                syncData
            }

            pushSyncData(accessToken, folderId, finalSyncData)
            return finalSyncData.backup
        } catch (e: Exception) {
            Logger.e(e) { "Google Drive sync error: ${e.message}" }
            notifier.showSyncError(e.message)
            return null
        }
    }
}
