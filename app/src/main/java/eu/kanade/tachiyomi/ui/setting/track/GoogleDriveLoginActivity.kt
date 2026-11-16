package eu.kanade.tachiyomi.ui.setting.track

import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.sync.service.GoogleDriveSyncService
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.toast
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.i18n.MR
import yokai.util.lang.getString

class GoogleDriveLoginActivity : BaseOAuthLoginActivity() {

    override fun handleResult(data: Uri?) {
        val code = data?.getQueryParameter("code")
        val error = data?.getQueryParameter("error")

        if (code != null) {
            lifecycleScope.launchIO {
                try {
                    val syncPreferences = Injekt.get<SyncPreferences>()
                    val success = GoogleDriveSyncService.exchangeCodeForTokens(code, syncPreferences)
                    runOnUiThread {
                        if (success) {
                            val email = syncPreferences.googleDriveUserEmail().get()
                            val msg = if (email.isNotEmpty()) {
                                getString(MR.strings.pref_google_drive_signed_in_as, email)
                            } else {
                                getString(MR.strings.google_drive_login_success)
                            }
                            toast(msg, Toast.LENGTH_LONG)
                        } else {
                            toast(getString(MR.strings.google_drive_login_failed, "Failed to exchange authorization code"), Toast.LENGTH_LONG)
                        }
                        returnToSettings()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        toast(getString(MR.strings.google_drive_login_failed, e.message ?: "Unknown error"), Toast.LENGTH_LONG)
                        returnToSettings()
                    }
                }
            }
        } else {
            if (error != null) {
                toast(getString(MR.strings.google_drive_login_failed, error), Toast.LENGTH_LONG)
            }
            returnToSettings()
        }
    }
}
