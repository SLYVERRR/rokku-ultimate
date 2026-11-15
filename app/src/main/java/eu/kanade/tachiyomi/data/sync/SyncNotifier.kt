package eu.kanade.tachiyomi.data.sync

import android.content.Context
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import uy.kohesive.injekt.injectLazy
import yokai.i18n.MR
import yokai.util.lang.getString

class SyncNotifier(private val context: Context) {

    private val preferences: SecurityPreferences by injectLazy()

    private val progressNotificationBuilder = context.notificationBuilder(
        Notifications.CHANNEL_SYNC_PROGRESS,
    ) {
        setSmallIcon(R.drawable.ic_sync_24dp)
        setAutoCancel(false)
        setOngoing(true)
        setOnlyAlertOnce(true)
    }

    private val completeNotificationBuilder = context.notificationBuilder(
        Notifications.CHANNEL_SYNC_COMPLETE,
    ) {
        setSmallIcon(R.drawable.ic_sync_24dp)
        setAutoCancel(true)
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
                context.getString(MR.strings.action_cancel),
                NotificationReceiver.cancelSyncPendingBroadcast(context, Notifications.ID_SYNC_PROGRESS),
            )
        }
    }

    fun showSyncError(error: String?) {
        context.cancelNotification(Notifications.ID_SYNC_PROGRESS)
        completeNotificationBuilder.apply {
            setContentTitle(context.getString(MR.strings.sync_error))
            setContentText(error ?: "Unknown error")
            context.notify(Notifications.ID_SYNC_COMPLETE, build())
        }
    }

    fun showSyncSuccess(message: String?) {
        context.cancelNotification(Notifications.ID_SYNC_PROGRESS)
        completeNotificationBuilder.apply {
            setContentTitle(context.getString(MR.strings.sync_complete))
            setContentText(message ?: context.getString(MR.strings.sync_complete))
            context.notify(Notifications.ID_SYNC_COMPLETE, build())
        }
    }
}
