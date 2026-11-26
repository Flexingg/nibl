package com.bytelog.app.data.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/**
 * Hears back from Android's package installer about a self-update. It opens the installer's confirmation when the
 * user needs to approve, and reports a failure or a cancel back to [AppUpdater]. On success Android replaces the app
 * and restarts it, so there's nothing more to do.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updater = (context.applicationContext as? com.bytelog.app.ByteLogApplication)?.appUpdater
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Started as the user left the app and Android wants a confirmation: don't pop it up over whatever
                // they're doing; the update stays ready and the home screen offers Install next time
                val app = context.applicationContext as? com.bytelog.app.ByteLogApplication
                if (updater?.installingQuietly == true && app?.inForeground != true) {
                    updater.onConfirmationNeededWhileAway()
                    return
                }
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm != null) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else updater?.onInstallFinished(false, "Android didn't show the install screen.")
            }
            PackageInstaller.STATUS_SUCCESS -> updater?.onInstallFinished(true, null)
            PackageInstaller.STATUS_FAILURE_ABORTED -> updater?.onInstallCancelled()
            else -> updater?.onInstallFinished(false, describe(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)))
        }
    }

    private fun describe(status: Int, message: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
            "Android couldn't install it over this copy of Nibl (it may be signed differently). Install it from the release page instead."
        PackageInstaller.STATUS_FAILURE_STORAGE -> "There isn't enough storage space for the update."
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked the install."
        else -> "The update wasn't installed" + (message?.let { ": $it" } ?: ".")
    }
}
