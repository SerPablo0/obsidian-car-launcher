package com.obsidian.carlauncher.media

import android.service.notification.NotificationListenerService

/**
 * Never inspects notifications. Android only hands out the list of active media
 * sessions to apps with an enabled notification listener, so this service exists
 * purely to hold that grant for [NowPlayingController].
 */
class MediaListenerService : NotificationListenerService()
