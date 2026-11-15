package eu.kanade.tachiyomi.data.sync.service

import android.content.Context
import co.touchlab.kermit.Logger
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionRepo
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.max

@Serializable
data class SyncData(
    val deviceId: String = "",
    val backup: Backup? = null,
)

abstract class SyncService(
    val context: Context,
    val json: Json,
    val syncPreferences: SyncPreferences,
) {
    abstract suspend fun doSync(syncData: SyncData): Backup?

    protected fun mergeSyncData(localSyncData: SyncData, remoteSyncData: SyncData): SyncData {
        val mergedCategoriesList = mergeCategoriesLists(
            localSyncData.backup?.backupCategories,
            remoteSyncData.backup?.backupCategories,
        )

        val mergedMangaList = mergeMangaLists(
            localSyncData.backup?.backupManga,
            remoteSyncData.backup?.backupManga,
            localSyncData.backup?.backupCategories ?: emptyList(),
            remoteSyncData.backup?.backupCategories ?: emptyList(),
            mergedCategoriesList,
        )

        val mergedSourcesList = mergeSourcesLists(
            localSyncData.backup?.backupSources,
            remoteSyncData.backup?.backupSources,
        )

        val mergedPreferencesList = mergePreferencesLists(
            localSyncData.backup?.backupPreferences,
            remoteSyncData.backup?.backupPreferences,
        )

        val mergedSourcePreferencesList = mergeSourcePreferencesLists(
            localSyncData.backup?.backupSourcePreferences,
            remoteSyncData.backup?.backupSourcePreferences,
        )

        val mergedExtensionReposList = mergeExtensionReposLists(
            localSyncData.backup?.backupExtensionRepo,
            remoteSyncData.backup?.backupExtensionRepo,
        )

        val mergedBackup = Backup(
            backupManga = mergedMangaList,
            backupCategories = mergedCategoriesList,
            backupSources = mergedSourcesList,
            backupPreferences = mergedPreferencesList,
            backupSourcePreferences = mergedSourcePreferencesList,
            backupExtensionRepo = mergedExtensionReposList,
        )

        return SyncData(
            deviceId = syncPreferences.uniqueDeviceID(),
            backup = mergedBackup,
        )
    }

    private fun mergeCategoriesLists(
        localCategoriesList: List<BackupCategory>?,
        remoteCategoriesList: List<BackupCategory>?,
    ): List<BackupCategory> {
        if (localCategoriesList == null) return remoteCategoriesList ?: emptyList()
        if (remoteCategoriesList == null) return localCategoriesList

        val merged = mutableMapOf<String, BackupCategory>()
        localCategoriesList.forEach { merged[it.name] = it }
        remoteCategoriesList.forEach { remote ->
            val existing = merged[remote.name]
            if (existing == null) {
                merged[remote.name] = remote
            } else if (remote.order > existing.order) {
                merged[remote.name] = remote
            }
        }
        return merged.values.toList()
    }

    private fun mergeMangaLists(
        localMangaList: List<BackupManga>?,
        remoteMangaList: List<BackupManga>?,
        localCategories: List<BackupCategory>,
        remoteCategories: List<BackupCategory>,
        mergedCategories: List<BackupCategory>,
    ): List<BackupManga> {
        val localList = localMangaList.orEmpty()
        val remoteList = remoteMangaList.orEmpty()

        fun mangaKey(manga: BackupManga): String = "${manga.source}|${manga.url}"

        val localMap = localList.associateBy { mangaKey(it) }
        val remoteMap = remoteList.associateBy { mangaKey(it) }

        val localCatByOrder = localCategories.associateBy { it.order }
        val remoteCatByOrder = remoteCategories.associateBy { it.order }
        val mergedCatByName = mergedCategories.associateBy { it.name }

        fun remapCategories(manga: BackupManga, catMap: Map<Int, BackupCategory>): List<Int> {
            return manga.categories.mapNotNull { order ->
                catMap[order]?.let { mergedCatByName[it.name]?.order }
            }
        }

        val allKeys = (localMap.keys + remoteMap.keys).distinct()
        return allKeys.mapNotNull { key ->
            val local = localMap[key]
            val remote = remoteMap[key]

            when {
                local != null && remote == null -> {
                    local.copy(categories = remapCategories(local, localCatByOrder))
                }
                local == null && remote != null -> {
                    remote.copy(categories = remapCategories(remote, remoteCatByOrder))
                }
                local != null && remote != null -> {
                    val mergedChapters = mergeChapters(local.chapters, remote.chapters)
                    val mergedHistory = mergeHistories(local.history, remote.history)
                    val mergedTracking = mergeTracking(local.tracking, remote.tracking)

                    val localMappedCats = remapCategories(local, localCatByOrder)
                    val remoteMappedCats = remapCategories(remote, remoteCatByOrder)
                    val mergedCats = (localMappedCats + remoteMappedCats).distinct()

                    local.copy(
                        favorite = local.favorite || remote.favorite,
                        dateAdded = if (local.dateAdded > 0) local.dateAdded else remote.dateAdded,
                        viewer_flags = local.viewer_flags ?: remote.viewer_flags,
                        chapterFlags = if (local.chapterFlags != 0) local.chapterFlags else remote.chapterFlags,
                        customTitle = local.customTitle ?: remote.customTitle,
                        customArtist = local.customArtist ?: remote.customArtist,
                        customAuthor = local.customAuthor ?: remote.customAuthor,
                        customDescription = local.customDescription ?: remote.customDescription,
                        customGenre = local.customGenre ?: remote.customGenre,
                        memo = local.memo?.takeIf { it.isNotBlank() } ?: remote.memo,
                        categories = mergedCats,
                        chapters = mergedChapters,
                        history = mergedHistory,
                        tracking = mergedTracking,
                    )
                }
                else -> null
            }
        }
    }

    private fun mergeChapters(
        localChapters: List<BackupChapter>,
        remoteChapters: List<BackupChapter>,
    ): List<BackupChapter> {
        val localMap = localChapters.associateBy { it.url }
        val remoteMap = remoteChapters.associateBy { it.url }

        val allUrls = (localMap.keys + remoteMap.keys).distinct()
        return allUrls.mapNotNull { url ->
            val local = localMap[url]
            val remote = remoteMap[url]

            when {
                local != null && remote == null -> local
                local == null && remote != null -> remote
                local != null && remote != null -> {
                    local.copy(
                        read = local.read || remote.read,
                        bookmark = local.bookmark || remote.bookmark,
                        lastPageRead = max(local.lastPageRead, remote.lastPageRead),
                        pagesLeft = if (local.pagesLeft > 0) local.pagesLeft else remote.pagesLeft,
                        dateFetch = max(local.dateFetch, remote.dateFetch),
                        dateUpload = max(local.dateUpload, remote.dateUpload),
                        memo = local.memo?.takeIf { it.isNotBlank() } ?: remote.memo,
                    )
                }
                else -> null
            }
        }
    }

    private fun mergeHistories(
        localHistory: List<BackupHistory>,
        remoteHistory: List<BackupHistory>,
    ): List<BackupHistory> {
        val localMap = localHistory.associateBy { it.url }
        val remoteMap = remoteHistory.associateBy { it.url }

        val allUrls = (localMap.keys + remoteMap.keys).distinct()
        return allUrls.mapNotNull { url ->
            val local = localMap[url]
            val remote = remoteMap[url]

            when {
                local != null && remote == null -> local
                local == null && remote != null -> remote
                local != null && remote != null -> {
                    BackupHistory(
                        url = url,
                        lastRead = max(local.lastRead, remote.lastRead),
                        readDuration = max(local.readDuration, remote.readDuration),
                    )
                }
                else -> null
            }
        }
    }

    private fun mergeTracking(
        localTracking: List<BackupTracking>,
        remoteTracking: List<BackupTracking>,
    ): List<BackupTracking> {
        val localMap = localTracking.associateBy { it.syncId }
        val remoteMap = remoteTracking.associateBy { it.syncId }

        val allSyncIds = (localMap.keys + remoteMap.keys).distinct()
        return allSyncIds.mapNotNull { syncId ->
            val local = localMap[syncId]
            val remote = remoteMap[syncId]

            when {
                local != null && remote == null -> local
                local == null && remote != null -> remote
                local != null && remote != null -> {
                    local.copy(
                        lastChapterRead = max(local.lastChapterRead, remote.lastChapterRead),
                        score = max(local.score, remote.score),
                        status = max(local.status, remote.status),
                        startedReadingDate = if (local.startedReadingDate > 0) local.startedReadingDate else remote.startedReadingDate,
                        finishedReadingDate = max(local.finishedReadingDate, remote.finishedReadingDate),
                    )
                }
                else -> null
            }
        }
    }

    private fun mergeSourcesLists(
        localSources: List<BackupSource>?,
        remoteSources: List<BackupSource>?,
    ): List<BackupSource> {
        val map = mutableMapOf<Long, BackupSource>()
        localSources.orEmpty().forEach { map[it.sourceId] = it }
        remoteSources.orEmpty().forEach { if (!map.containsKey(it.sourceId)) map[it.sourceId] = it }
        return map.values.toList()
    }

    private fun mergePreferencesLists(
        localPrefs: List<BackupPreference>?,
        remotePrefs: List<BackupPreference>?,
    ): List<BackupPreference> {
        val map = mutableMapOf<String, BackupPreference>()
        remotePrefs.orEmpty().forEach { map[it.key] = it }
        localPrefs.orEmpty().forEach { map[it.key] = it }
        return map.values.toList()
    }

    private fun mergeSourcePreferencesLists(
        localPrefs: List<BackupSourcePreferences>?,
        remotePrefs: List<BackupSourcePreferences>?,
    ): List<BackupSourcePreferences> {
        val localMap = localPrefs.orEmpty().associateBy { it.sourceKey }
        val remoteMap = remotePrefs.orEmpty().associateBy { it.sourceKey }

        val allKeys = (localMap.keys + remoteMap.keys).distinct()
        return allKeys.mapNotNull { key ->
            val local = localMap[key]
            val remote = remoteMap[key]

            when {
                local != null && remote == null -> local
                local == null && remote != null -> remote
                local != null && remote != null -> {
                    val mergedIndividual = mergePreferencesLists(local.prefs, remote.prefs)
                    BackupSourcePreferences(key, mergedIndividual)
                }
                else -> null
            }
        }
    }

    private fun mergeExtensionReposLists(
        localRepos: List<BackupExtensionRepo>?,
        remoteRepos: List<BackupExtensionRepo>?,
    ): List<BackupExtensionRepo> {
        val map = mutableMapOf<String, BackupExtensionRepo>()
        localRepos.orEmpty().forEach { map[it.baseUrl] = it }
        remoteRepos.orEmpty().forEach { if (!map.containsKey(it.baseUrl)) map[it.baseUrl] = it }
        return map.values.toList()
    }
}
