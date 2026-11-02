package com.obsidian.carlauncher.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.obsidian.carlauncher.HomeActivity

/**
 * Opens the home screen as soon as a new version of this app is installed. The in-app update
 * installs through a shell that dies together with the old version, so it cannot do it itself,
 * and the head unit then showed Recents. Living in the new version, this also covers an update
 * started by an older one. Starting from the background is allowed because the app holds the
 * overlay permission the bar needs.
 */
class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            context.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setClass(context, HomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: RuntimeException) {
            // The Home button still brings it back.
        }
    }
}
