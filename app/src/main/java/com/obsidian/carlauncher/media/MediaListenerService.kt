package com.obsidian.carlauncher.media

import android.service.notification.NotificationListenerService
import com.obsidian.carlauncher.drive.TripComputer

/**
 * Never inspects notifications. Android only hands out the list of active media
 * sessions to apps with an enabled notification listener, so this service exists
 * purely to hold that grant for [NowPlayingController].
 */
class MediaListenerService : NotificationListenerService() {

    // The system binds this service from boot too, so the trip computer runs even with the Obsidian bar off.
    override fun onListenerConnected() {
        super.onListenerConnected()
        TripComputer.start(this)
    }
}
