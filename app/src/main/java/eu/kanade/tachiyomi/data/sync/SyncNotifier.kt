package eu.kanade.tachiyomi.data.sync

import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notificationManager
import uy.kohesive.injekt.injectLazy
import yokai.i18n.MR
import yokai.util.lang.getString
import android.R as AR

class SyncNotifier(private val context: Context) {

    private val preferences: PreferencesHelper by injectLazy()

    private val progressNotificationBuilder = context.notificationBuilder(
        Notifications.CHANNEL_SYNC_PROGRESS,
    ) {
        setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
        setSmallIcon(R.drawable.ic_sync_24dp)
        setAutoCancel(false)
        setOngoing(true)
        setOnlyAlertOnce(true)
    }

    private val completeNotificationBuilder = context.notificationBuilder(
        Notifications.CHANNEL_SYNC_COMPLETE,
    ) {
        setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
        setSmallIcon(R.drawable.ic_sync_24dp)
        setAutoCancel(true)
    }

    private fun NotificationCompat.Builder.show(id: Int) {
        context.notificationManager.notify(id, build())
    }

    fun showSyncProgress(
        content: String = "",
        progress: Int = 0,
        maxAmount: Int = 100,
    ): NotificationCompat.Builder {
        return progressNotificationBuilder.apply {
            setContentTitle(context.getString(MR.strings.syncing_library))
            if (!preferences.hideNotificationContent().get()) {
                setContentText(content)
            }
            if (maxAmount > 0 && progress >= 0) {
                setProgress(maxAmount, progress, false)
            } else {
                setProgress(0, 0, true)
            }
            clearActions()
            addAction(
                R.drawable.ic_close_24dp,
                context.getString(AR.string.cancel),
                NotificationReceiver.cancelSyncPendingBroadcast(context, Notifications.ID_SYNC_PROGRESS),
            )
            show(Notifications.ID_SYNC_PROGRESS)
        }
    }

    fun showSyncError(error: String?) {
        context.notificationManager.cancel(Notifications.ID_SYNC_PROGRESS)
        completeNotificationBuilder.apply {
            setContentTitle(context.getString(MR.strings.sync_error))
            setContentText(error ?: "Unknown error")
            show(Notifications.ID_SYNC_COMPLETE)
        }
    }

    fun showSyncSuccess(message: String?) {
        context.notificationManager.cancel(Notifications.ID_SYNC_PROGRESS)
    }
}
