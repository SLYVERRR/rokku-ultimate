package yokai.presentation.settings.screen

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Help
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import co.touchlab.kermit.Logger
import dev.icerock.moko.resources.StringResource
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.core.storage.preference.collectAsState
import eu.kanade.tachiyomi.data.backup.BackupFileValidator
import eu.kanade.tachiyomi.data.backup.create.BackupCreatorJob
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.sync.SyncDataJob
import eu.kanade.tachiyomi.data.sync.service.GoogleDriveSyncService
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.util.compose.LocalDialogHostState
import eu.kanade.tachiyomi.util.compose.currentOrThrow
import eu.kanade.tachiyomi.util.relativeTimeSpanString
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.launchNonCancellableIO
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.withUIContext
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import yokai.domain.backup.BackupPreferences
import yokai.domain.storage.StorageManager
import yokai.domain.storage.StoragePreferences
import yokai.i18n.MR
import yokai.presentation.component.ToolTipButton
import yokai.presentation.component.preference.Preference
import yokai.presentation.component.preference.storageLocationText
import yokai.presentation.component.preference.widget.BasePreferenceWidget
import yokai.presentation.component.preference.widget.PrefsHorizontalPadding
import yokai.presentation.settings.ComposableSettings
import yokai.presentation.settings.screen.data.StorageInfo
import yokai.presentation.settings.screen.data.awaitCreateBackup
import yokai.presentation.settings.screen.data.awaitRestoreBackup
import yokai.presentation.settings.screen.data.storageLocationPicker
import yokai.util.lang.getString

object SettingsDataScreen : ComposableSettings() {

    private fun readResolve() = SettingsDataScreen

    @Composable
    override fun getTitleRes(): StringResource = MR.strings.data_and_storage

    @Composable
    override fun RowScope.AppBarAction() {
        val context = LocalContext.current

        ToolTipButton(
            toolTipLabel = stringResource(MR.strings.help),
            icon = Icons.AutoMirrored.Outlined.Help,
            buttonClicked = { context.openInBrowser(BACKUPS_HELP_URL) },
        )
    }

    @Composable
    override fun getPreferences(): List<Preference> {
        val storagePreferences: StoragePreferences by injectLazy()
        val backupPreferences: BackupPreferences by injectLazy()
        val syncPreferences: SyncPreferences by injectLazy()

        return persistentListOf(
            getStorageLocationPreference(storagePreferences = storagePreferences),
            getBackupAndRestoreGroup(backupPreferences = backupPreferences),
            getSyncGroup(syncPreferences = syncPreferences),
            getDataGroup(),
        )
    }

    @Composable
    private fun getStorageLocationPreference(
        storagePreferences: StoragePreferences,
    ): Preference.PreferenceItem.TextPreference {
        val context = LocalContext.current
        val pickStoragePicker = storageLocationPicker(storagePreferences.baseStorageDirectory())

        return Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.storage_location),
            subtitle = storageLocationText(storagePreferences.baseStorageDirectory()),
            onClick = {
                try {
                    pickStoragePicker.launch(null)
                } catch (e: ActivityNotFoundException) {
                    context.toast(MR.strings.file_picker_error)
                }
            },
        )
    }

    @Composable
    private fun getBackupAndRestoreGroup(backupPreferences: BackupPreferences): Preference.PreferenceGroup {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val alertDialog = LocalDialogHostState.currentOrThrow
        val extensionManager = remember { Injekt.get<ExtensionManager>() }
        val storageManager = remember { Injekt.get<StorageManager>() }

        val chooseBackup = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) {
            if (it == null) return@rememberLauncherForActivityResult

            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (e: SecurityException) {
                Logger.e(e) { "Unable to persist backup file URI permission" }
            }

            val results = try {
                Pair(BackupFileValidator().validate(context, it), null)
            } catch (e: Exception) {
                Pair(null, e)
            }

            scope.launch {
                alertDialog.awaitRestoreBackup(
                    context = context,
                    uri = it,
                    pair = results,
                )
            }
        }

        val backupInterval by backupPreferences.backupInterval().collectAsState()
        val lastAutoBackup by backupPreferences.lastAutoBackupTimestamp().collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.backup_and_restore),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(MR.strings.backup_and_restore),
                ) {
                    BasePreferenceWidget(
                        subcomponent = {
                            MultiChoiceSegmentedButtonRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(intrinsicSize = IntrinsicSize.Min)
                                    .padding(horizontal = PrefsHorizontalPadding),
                            ) {
                                SegmentedButton(
                                    modifier = Modifier.fillMaxHeight(),
                                    checked = false,
                                    onCheckedChange = {
                                        if (!BackupRestoreJob.isRunning(context)) {
                                            if (DeviceUtil.isMiui && DeviceUtil.isMiuiOptimizationDisabled()) {
                                                context.toast(MR.strings.restore_miui_warning, Toast.LENGTH_LONG)
                                            }

                                            val dir = storageManager.getBackupsDirectory()
                                            if (dir == null) {
                                                context.toast(MR.strings.invalid_location_generic)
                                                return@SegmentedButton
                                            }

                                            scope.launch {
                                                alertDialog.awaitCreateBackup(
                                                    context = context,
                                                    uri = dir.uri,
                                                )
                                            }
                                        } else {
                                            context.toast(MR.strings.backup_in_progress)
                                        }
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                                ) {
                                    Text(stringResource(MR.strings.create_backup))
                                }
                                SegmentedButton(
                                    modifier = Modifier.fillMaxHeight(),
                                    checked = false,
                                    onCheckedChange = {
                                        if (!BackupRestoreJob.isRunning(context)) {
                                            if (DeviceUtil.isMiui && DeviceUtil.isMiuiOptimizationDisabled()) {
                                                context.toast(MR.strings.restore_miui_warning, Toast.LENGTH_LONG)
                                            }

                                            scope.launch { extensionManager.getExtensionUpdates(true) }
                                            chooseBackup.launch(arrayOf("*/*"))
                                        } else {
                                            context.toast(MR.strings.restore_in_progress)
                                        }
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                                ) {
                                    Text(stringResource(MR.strings.restore_backup))
                                }
                            }
                        },
                    )
                },

                // Automatic backups
                Preference.PreferenceItem.ListPreference(
                    pref = backupPreferences.backupInterval(),
                    title = stringResource(MR.strings.backup_frequency),
                    entries = persistentMapOf(
                        0 to stringResource(MR.strings.manual),
                        6 to stringResource(MR.strings.every_6_hours),
                        12 to stringResource(MR.strings.every_12_hours),
                        24 to stringResource(MR.strings.daily),
                        48 to stringResource(MR.strings.every_2_days),
                        168 to stringResource(MR.strings.weekly),
                    ),
                    onValueChanged = {
                        BackupCreatorJob.setupTask(context, it)
                        true
                    },
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = backupPreferences.numberOfBackups(),
                    title = stringResource(MR.strings.max_auto_backups),
                    entries = (1..5).associateWith { it.toString() }.toImmutableMap(),
                    enabled = backupInterval > 0,
                ),
                Preference.PreferenceItem.InfoPreference(
                    stringResource(MR.strings.backup_info) +
                        "\n\n" +
                        stringResource(MR.strings.last_auto_backup_info, relativeTimeSpanString(lastAutoBackup)),
                ),
            ),
        )
    }

    @Composable
    private fun getSyncGroup(syncPreferences: SyncPreferences): Preference.PreferenceGroup {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val syncService by syncPreferences.syncService().collectAsState()
        val lastSync by syncPreferences.lastSyncTimestamp().collectAsState()

        val syncServiceEntries = persistentMapOf(
            0 to stringResource(MR.strings.pref_sync_service_none),
            1 to stringResource(MR.strings.pref_sync_service_syncyomi),
            2 to stringResource(MR.strings.pref_sync_service_webdav),
            3 to stringResource(MR.strings.pref_sync_service_google_drive),
        )

        val syncIntervalEntries = persistentMapOf(
            0 to stringResource(MR.strings.pref_sync_interval_manual),
            -1 to stringResource(MR.strings.pref_sync_interval_30s),
            30 to stringResource(MR.strings.pref_sync_interval_30m),
            60 to stringResource(MR.strings.pref_sync_interval_1h),
            120 to stringResource(MR.strings.pref_sync_interval_2h),
            360 to stringResource(MR.strings.pref_sync_interval_6h),
            720 to stringResource(MR.strings.pref_sync_interval_12h),
            1440 to stringResource(MR.strings.pref_sync_interval_24h),
        )

        val items = mutableListOf<Preference.PreferenceItem<out Any>>()

        items.add(
            Preference.PreferenceItem.ListPreference(
                pref = syncPreferences.syncService(),
                title = stringResource(MR.strings.pref_sync_service),
                entries = syncServiceEntries,
                onValueChanged = {
                    SyncDataJob.setupTask(context)
                    true
                },
            ),
        )

        if (syncService != 0) {
            val lastSyncStr = if (lastSync == 0L) {
                stringResource(MR.strings.never)
            } else {
                relativeTimeSpanString(lastSync)
            }

            items.add(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.sync_now),
                    subtitle = stringResource(MR.strings.pref_last_sync) + ": " + lastSyncStr,
                    onClick = {
                        if (SyncDataJob.isRunning(context)) {
                            context.toast(MR.strings.sync_in_progress)
                        } else {
                            SyncDataJob.startNow(context, manual = true)
                            context.toast(MR.strings.syncing_library)
                        }
                    },
                ),
            )

            items.add(
                Preference.PreferenceItem.ListPreference(
                    pref = syncPreferences.syncInterval(),
                    title = stringResource(MR.strings.pref_sync_interval),
                    entries = syncIntervalEntries,
                    onValueChanged = { interval ->
                        SyncDataJob.setupTask(context, interval)
                        true
                    },
                ),
            )

            if (syncService == 1) {
                items.add(
                    Preference.PreferenceItem.EditTextPreference(
                        pref = syncPreferences.clientHost(),
                        title = stringResource(MR.strings.pref_syncyomi_host),
                        subtitle = "%s",
                    ),
                )
                items.add(
                    Preference.PreferenceItem.EditTextPreference(
                        pref = syncPreferences.clientAPIKey(),
                        title = stringResource(MR.strings.pref_syncyomi_api_key),
                        subtitle = if (syncPreferences.clientAPIKey().get().isEmpty()) "%s" else "••••••••",
                    ),
                )
            } else if (syncService == 2) {
                items.add(
                    Preference.PreferenceItem.EditTextPreference(
                        pref = syncPreferences.webDavUrl(),
                        title = stringResource(MR.strings.pref_webdav_url),
                        subtitle = "%s",
                    ),
                )
                items.add(
                    Preference.PreferenceItem.EditTextPreference(
                        pref = syncPreferences.webDavUsername(),
                        title = stringResource(MR.strings.pref_webdav_username),
                        subtitle = "%s",
                    ),
                )
                items.add(
                    Preference.PreferenceItem.EditTextPreference(
                        pref = syncPreferences.webDavPassword(),
                        title = stringResource(MR.strings.pref_webdav_password),
                        subtitle = if (syncPreferences.webDavPassword().get().isEmpty()) "%s" else "••••••••",
                    ),
                )
                items.add(
                    Preference.PreferenceItem.EditTextPreference(
                        pref = syncPreferences.webDavFolder(),
                        title = stringResource(MR.strings.pref_webdav_folder),
                        subtitle = "%s",
                    ),
                )
            } else if (syncService == 3) {
                val gdriveEmail by syncPreferences.googleDriveUserEmail().collectAsState()
                val gdriveRefreshToken by syncPreferences.googleDriveRefreshToken().collectAsState()

                if (gdriveRefreshToken.isEmpty()) {
                    items.add(
                        Preference.PreferenceItem.TextPreference(
                            title = stringResource(MR.strings.pref_google_drive_sign_in),
                            subtitle = stringResource(MR.strings.pref_google_drive_sign_in_summary),
                            onClick = {
                                GoogleDriveSyncService.startOAuthServerAndOpenBrowser(context, scope)
                            },
                        ),
                    )
                } else {
                    val emailDisplay = gdriveEmail.ifEmpty { "Connected" }
                    items.add(
                        Preference.PreferenceItem.TextPreference(
                            title = stringResource(MR.strings.pref_sync_service_google_drive),
                            subtitle = stringResource(MR.strings.pref_google_drive_signed_in_as, emailDisplay),
                            onClick = {
                                context.toast(MR.strings.google_drive_login_success)
                            },
                        ),
                    )
                    items.add(
                        Preference.PreferenceItem.EditTextPreference(
                            pref = syncPreferences.googleDriveFolder(),
                            title = stringResource(MR.strings.pref_google_drive_folder),
                            subtitle = "%s",
                        ),
                    )
                    items.add(
                        Preference.PreferenceItem.TextPreference(
                            title = stringResource(MR.strings.pref_google_drive_disconnect),
                            onClick = {
                                syncPreferences.googleDriveAccessToken().set("")
                                syncPreferences.googleDriveRefreshToken().set("")
                                syncPreferences.googleDriveUserEmail().set("")
                                syncPreferences.googleDriveLastVersion().set("")
                                context.toast(MR.strings.google_drive_disconnected)
                            },
                        ),
                    )
                }
            }

            items.add(
                Preference.PreferenceItem.SwitchPreference(
                    pref = syncPreferences.syncLibraryEntries(),
                    title = stringResource(MR.strings.pref_sync_library_entries),
                ),
            )
            items.add(
                Preference.PreferenceItem.SwitchPreference(
                    pref = syncPreferences.syncCategories(),
                    title = stringResource(MR.strings.pref_sync_categories),
                ),
            )
            items.add(
                Preference.PreferenceItem.SwitchPreference(
                    pref = syncPreferences.syncChapters(),
                    title = stringResource(MR.strings.pref_sync_chapters),
                ),
            )
            items.add(
                Preference.PreferenceItem.SwitchPreference(
                    pref = syncPreferences.syncTracking(),
                    title = stringResource(MR.strings.pref_sync_tracking),
                ),
            )
            items.add(
                Preference.PreferenceItem.SwitchPreference(
                    pref = syncPreferences.syncHistory(),
                    title = stringResource(MR.strings.pref_sync_history),
                ),
            )
            items.add(
                Preference.PreferenceItem.SwitchPreference(
                    pref = syncPreferences.syncAppPrefs(),
                    title = stringResource(MR.strings.pref_sync_app_settings),
                ),
            )
            items.add(
                Preference.PreferenceItem.SwitchPreference(
                    pref = syncPreferences.syncSourcePrefs(),
                    title = stringResource(MR.strings.pref_sync_source_settings),
                ),
            )
            items.add(
                Preference.PreferenceItem.SwitchPreference(
                    pref = syncPreferences.syncExtensionRepos(),
                    title = stringResource(MR.strings.pref_sync_extension_repos),
                ),
            )
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.cloud_sync),
            preferenceItems = items.toPersistentList(),
        )
    }

    @Composable
    private fun getDataGroup(): Preference.PreferenceGroup {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        // TODO
        // val libraryPreferences = remember { Injekt.get<LibraryPreferences>() }

        val coverCache = remember { Injekt.get<CoverCache>() }
        val chapterCache = remember { Injekt.get<ChapterCache>() }
        var cacheReadableSizeSema by remember { mutableIntStateOf(0) }
        val cacheReadableSize = remember(cacheReadableSizeSema) { chapterCache.readableSize }
        var onlineCoverCacheSizeSema by remember { mutableIntStateOf(0) }
        val onlineCoverCacheSize = remember(onlineCoverCacheSizeSema) { coverCache.getOnlineCoverCacheSize() }
        var chapterCoverCacheSizeSema by remember { mutableIntStateOf(0) }
        val chapterCoverCacheSize = remember(chapterCoverCacheSizeSema) { coverCache.getChapterCacheSize() }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.storage_usage),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(MR.strings.storage_usage),
                ) {
                    BasePreferenceWidget(
                        subcomponent = {
                            StorageInfo(
                                modifier = Modifier.padding(horizontal = PrefsHorizontalPadding),
                            )
                        },
                    )
                },

                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.clear_chapter_cache),
                    subtitle = stringResource(MR.strings.used_, cacheReadableSize),
                    onClick = {
                        scope.launchNonCancellableIO {
                            try {
                                val deletedFiles = chapterCache.clear()
                                withUIContext {
                                    context.toast(
                                        context.getString(
                                            MR.plurals.cache_cleared,
                                            deletedFiles,
                                            deletedFiles,
                                        ),
                                    )
                                    cacheReadableSizeSema++
                                }
                            } catch (e: Throwable) {
                                Logger.e(e) { "Unable to clear cache" }
                                withUIContext { context.toast(MR.strings.cache_delete_error) }
                            }
                        }
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.clear_cached_covers_non_library),
                    subtitle = stringResource(
                        MR.strings.delete_all_covers__not_in_library_used_,
                        onlineCoverCacheSize,
                    ),
                    onClick = {
                        context.toast(MR.strings.starting_cleanup)
                        scope.launchNonCancellableIO {
                            coverCache.deleteAllCachedCovers()
                            withUIContext { onlineCoverCacheSizeSema++ }
                        }
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.clean_up_cached_covers),
                    subtitle = stringResource(
                        MR.strings.delete_old_covers_in_library_used_,
                        chapterCoverCacheSize,
                    ),
                    onClick = {
                        context.toast(MR.strings.starting_cleanup)
                        scope.launchNonCancellableIO {
                            coverCache.deleteOldCovers()
                            withUIContext { chapterCoverCacheSizeSema++ }
                        }
                    },
                ),
                /*
                Preference.PreferenceItem.SwitchPreference(
                    pref = libraryPreferences.autoClearChapterCache(),
                    title = stringResource(MR.strings.pref_auto_clear_chapter_cache),
                ),
                 */
            ),
        )
    }
}

const val BACKUPS_HELP_URL = "https://rokku-app.github.io/docs/guides/backups"
