package eu.kanade.tachiyomi.data.sync

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import co.touchlab.kermit.Logger
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.isOnline
import eu.kanade.tachiyomi.util.system.jobIsRunning
import eu.kanade.tachiyomi.util.system.tryToSetForeground
import eu.kanade.tachiyomi.util.system.withIOContext
import eu.kanade.tachiyomi.util.system.workManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class SyncDataJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val notifier = SyncNotifier(context)

    override suspend fun doWork(): Result {
        if (tags.contains(TAG_AUTO)) {
            if (!context.isOnline()) {
                return Result.retry()
            }
            if (context.workManager.jobIsRunning(TAG_MANUAL)) {
                return Result.retry()
            }
        }

        tryToSetForeground()

        return withIOContext {
            try {
                SyncManager(context).syncData()
                Result.success()
            } catch (e: Exception) {
                Logger.e(e) { "SyncDataJob failed" }
                notifier.showSyncError(e.message)
                Result.success()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_SYNC_PROGRESS,
            notifier.showSyncProgress().build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    companion object {
        private const val TAG_JOB = "SyncDataJob"
        private const val TAG_AUTO = "SyncDataJob:auto"
        private const val TAG_MANUAL = "SyncDataJob:manual"

        fun isRunning(context: Context): Boolean {
            return context.workManager.jobIsRunning(TAG_JOB)
        }

        fun setupTask(context: Context, prefInterval: Int? = null) {
            val syncPreferences = Injekt.get<SyncPreferences>()
            val interval = prefInterval ?: syncPreferences.syncInterval().get()

            if (interval > 0 && syncPreferences.isSyncEnabled()) {
                val request = PeriodicWorkRequestBuilder<SyncDataJob>(
                    interval.toLong(),
                    TimeUnit.MINUTES,
                    15,
                    TimeUnit.MINUTES,
                )
                    .addTag(TAG_JOB)
                    .addTag(TAG_AUTO)
                    .build()

                context.workManager.enqueueUniquePeriodicWork(
                    TAG_AUTO,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
            } else {
                context.workManager.cancelUniqueWork(TAG_AUTO)
            }
        }

        fun startNow(context: Context, manual: Boolean = true) {
            val wm = context.workManager
            if (wm.jobIsRunning(TAG_JOB)) {
                return
            }
            val tag = if (manual) TAG_MANUAL else TAG_AUTO
            val request = OneTimeWorkRequestBuilder<SyncDataJob>()
                .addTag(TAG_JOB)
                .addTag(tag)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            wm.enqueueUniqueWork(tag, ExistingWorkPolicy.KEEP, request)
        }

        fun stop(context: Context) {
            val wm = context.workManager
            wm.cancelUniqueWork(TAG_JOB)
            wm.cancelUniqueWork(TAG_AUTO)
            wm.cancelUniqueWork(TAG_MANUAL)
        }
    }
}
