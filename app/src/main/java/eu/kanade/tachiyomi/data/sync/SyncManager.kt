package eu.kanade.tachiyomi.data.sync

import android.content.Context
import co.touchlab.kermit.Logger
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.restore.BackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.sync.service.SyncData
import eu.kanade.tachiyomi.data.sync.service.SyncService
import eu.kanade.tachiyomi.data.sync.service.SyncYomiSyncService
import eu.kanade.tachiyomi.data.sync.service.WebDavSyncService
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.i18n.MR
import yokai.util.lang.getString

class SyncManager(
    private val context: Context,
    private val syncPreferences: SyncPreferences = Injekt.get(),
    private val json: Json = Injekt.get(),
    private val protoBuf: ProtoBuf = Injekt.get(),
) {
    private val notifier: SyncNotifier = SyncNotifier(context)
    private val backupCreator: BackupCreator = BackupCreator(context)

    enum class SyncServiceType(val value: Int) {
        NONE(0),
        SYNCYOMI(1),
        WEBDAV(2);

        companion object {
            fun fromInt(value: Int) = entries.firstOrNull { it.value == value } ?: NONE
        }
    }

    suspend fun syncData() {
        if (!syncPreferences.isSyncEnabled()) {
            Logger.d { "Sync is disabled; skipping" }
            return
        }

        val serviceType = SyncServiceType.fromInt(syncPreferences.syncService().get())
        val service: SyncService? = when (serviceType) {
            SyncServiceType.SYNCYOMI -> SyncYomiSyncService(context, json, syncPreferences, notifier, protoBuf)
            SyncServiceType.WEBDAV -> WebDavSyncService(context, json, syncPreferences, notifier, protoBuf)
            SyncServiceType.NONE -> null
        }

        if (service == null) {
            Logger.w { "No valid sync service selected" }
            return
        }

        Logger.d { "Starting sync with ${serviceType.name}" }
        notifier.showSyncProgress(context.getString(MR.strings.syncing_library), 0, 100)

        val backupOptions = syncPreferences.getBackupOptions()
        val localBackup = backupCreator.createBackupData(backupOptions)
        val localSyncData = SyncData(
            deviceId = syncPreferences.uniqueDeviceID(),
            backup = localBackup,
        )

        val remoteBackup: Backup? = service.doSync(localSyncData)

        if (remoteBackup == null) {
            Logger.d { "Sync completed or failed without remote changes" }
            return
        }

        if (remoteBackup === localBackup) {
            syncPreferences.lastSyncTimestamp().set(System.currentTimeMillis())
            notifier.showSyncSuccess(context.getString(MR.strings.sync_complete))
            return
        }

        // Apply merged backup into local database
        Logger.d { "Applying merged backup with ${remoteBackup.backupManga.size} manga" }
        val restorer = BackupRestorer(
            context = context,
            notifier = null,
            onProgress = { progress, amount, title ->
                notifier.showSyncProgress(title, progress, amount)
            },
        )

        val restoreOptions = RestoreOptions(
            libraryEntries = backupOptions.libraryEntries,
            categories = backupOptions.categories,
            appPrefs = backupOptions.appPrefs,
            sourcePrefs = backupOptions.sourcePrefs,
            extensionRepos = backupOptions.extensionRepos,
        )

        restorer.restore(remoteBackup, restoreOptions)

        syncPreferences.lastSyncTimestamp().set(System.currentTimeMillis())
        notifier.showSyncSuccess(context.getString(MR.strings.sync_complete))
    }
}
