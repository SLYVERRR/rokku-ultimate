package eu.kanade.domain.sync

import eu.kanade.tachiyomi.core.preference.Preference
import eu.kanade.tachiyomi.core.preference.PreferenceStore
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import java.util.UUID

class SyncPreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun syncService() = preferenceStore.getInt("sync_service", 0)
    fun syncInterval() = preferenceStore.getInt("sync_interval", 0)

    fun clientHost() = preferenceStore.getString("sync_syncyomi_host", "")
    fun clientAPIKey() = preferenceStore.getString("sync_syncyomi_api_key", "")

    fun webDavUrl() = preferenceStore.getString("sync_webdav_url", "")
    fun webDavUsername() = preferenceStore.getString("sync_webdav_username", "")
    fun webDavPassword() = preferenceStore.getString("sync_webdav_password", "")
    fun webDavFolder() = preferenceStore.getString("sync_webdav_folder", "yokai")

    fun lastSyncTimestamp() = preferenceStore.getLong(Preference.appStateKey("last_sync_timestamp"), 0L)
    fun lastSyncEtag() = preferenceStore.getString(Preference.appStateKey("sync_etag"), "")

    fun uniqueDeviceID(): String {
        val pref = preferenceStore.getString(Preference.appStateKey("unique_device_id"), "")
        var id = pref.get()
        if (id.isBlank()) {
            id = UUID.randomUUID().toString()
            pref.set(id)
        }
        return id
    }

    fun syncLibraryEntries() = preferenceStore.getBoolean("sync_library_entries", true)
    fun syncCategories() = preferenceStore.getBoolean("sync_categories", true)
    fun syncChapters() = preferenceStore.getBoolean("sync_chapters", true)
    fun syncTracking() = preferenceStore.getBoolean("sync_tracking", true)
    fun syncHistory() = preferenceStore.getBoolean("sync_history", true)
    fun syncAppPrefs() = preferenceStore.getBoolean("sync_app_prefs", true)
    fun syncSourcePrefs() = preferenceStore.getBoolean("sync_source_prefs", true)
    fun syncExtensionRepos() = preferenceStore.getBoolean("sync_extension_repos", true)

    fun isSyncEnabled(): Boolean = syncService().get() != 0

    fun getBackupOptions(): BackupOptions {
        return BackupOptions(
            libraryEntries = syncLibraryEntries().get(),
            categories = syncCategories().get(),
            chapters = syncChapters().get(),
            tracking = syncTracking().get(),
            history = syncHistory().get(),
            appPrefs = syncAppPrefs().get(),
            sourcePrefs = syncSourcePrefs().get(),
            customInfo = true,
            readManga = true,
            includePrivate = false,
            extensionRepos = syncExtensionRepos().get(),
        )
    }
}
