package com.obsidian.carlauncher.media

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Outline
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import com.obsidian.carlauncher.AppLauncher
import com.obsidian.carlauncher.R
import com.obsidian.carlauncher.system.AutoStart
import com.obsidian.carlauncher.widget.LineProgressView

/**
 * Drives the "now playing" card from the system's active media sessions.
 * Spotify is preferred; any other playing app (YouTube, ...) is shown otherwise,
 * and NWD's radio / Bluetooth / USB players through their own broadcasts ([NwdMedia]).
 */
class NowPlayingController(private val activity: Activity, root: View) {

    private val source: TextView = root.findViewById(R.id.source)
    private val title: TextView = root.findViewById(R.id.title)
    private val artist: TextView = root.findViewById(R.id.artist)
    private val art: ImageView = root.findViewById(R.id.art)
    private val artPlaceholder: View = root.findViewById(R.id.art_placeholder)
    private val progress: LineProgressView = root.findViewById(R.id.progress)
    private val position: TextView = root.findViewById(R.id.position)
    private val duration: TextView = root.findViewById(R.id.duration)
    private val playButton: ImageButton = root.findViewById(R.id.btn_play)

    private val sessionManager = activity.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(activity, MediaListenerService::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var controller: MediaController? = null
    private var shownArt: Bitmap? = null
    private var started = false

    /** NWD's own radio / BT / USB players, which have no media session. */
    private val nwd = NwdMedia
    private val nwdListener: () -> Unit = { render() }
    private val nwdRecheck = Runnable { render() }
    /** True while the card shows an NWD source; the buttons then send media keys instead. */
    private var nwdActive = false

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
        attach(pickSession(sessions.orEmpty()))
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = render()
        override fun onPlaybackStateChanged(state: PlaybackState?) = render()
        override fun onSessionDestroyed() = attach(pickSession(activeSessions()))
    }

    private val progressTicker = object : Runnable {
        override fun run() {
            renderProgress()
            handler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    init {
        art.outlineProvider = RoundedOutline(activity.resources.getDimension(R.dimen.art_radius))
        art.clipToOutline = true

        root.findViewById<View>(R.id.btn_previous).setOnClickListener {
            if (nwdActive) sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) else controller?.transportControls?.skipToPrevious()
        }
        root.findViewById<View>(R.id.btn_next).setOnClickListener {
            if (nwdActive) sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) else controller?.transportControls?.skipToNext()
        }
        playButton.setOnClickListener { togglePlayback() }
        root.setOnClickListener { openPlayer() }
    }

    fun start() {
        started = true
        nwd.ensureRegistered(activity)
        nwd.addListener(nwdListener)
        if (!hasListenerAccess()) {
            renderNoAccess()
            return
        }
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler)
            attach(pickSession(activeSessions()))
        } catch (e: SecurityException) {
            renderNoAccess()
        }
    }

    fun stop() {
        started = false
        nwd.removeListener(nwdListener)
        handler.removeCallbacks(nwdRecheck)
        sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        attach(null)
        handler.removeCallbacks(progressTicker)
    }

    /** "Title · Artist" of what is playing right now, or null. */
    fun playingLine(): String? {
        if (!nwdActive && controller?.isPlaying != true) return null
        return listOf(title.text, artist.text).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { null }
    }

    private fun activeSessions(): List<MediaController> = try {
        sessionManager.getActiveSessions(listenerComponent)
    } catch (e: SecurityException) {
        emptyList()
    }

    private fun pickSession(sessions: List<MediaController>): MediaController? =
        sessions.firstOrNull { it.packageName == SPOTIFY && it.isPlaying }
            ?: sessions.firstOrNull { it.isPlaying }
            ?: sessions.firstOrNull { it.packageName == SPOTIFY }
            ?: sessions.firstOrNull()

    private fun attach(next: MediaController?) {
        if (next?.sessionToken == controller?.sessionToken) {
            render()
            return
        }
        controller?.unregisterCallback(controllerCallback)
        controller = next
        next?.registerCallback(controllerCallback, handler)
        render()
    }

    private fun render() {
        if (!started) return
        val current = controller
        // A playing app session wins; otherwise a live NWD source (radio, BT music) is shown.
        val nwdInfo = nwd.info?.takeIf { it.isPlaying }
        nwdActive = current?.isPlaying != true && nwdInfo != null
        if (nwdActive && nwdInfo != null) {
            renderNwd(nwdInfo)
            return
        }
        setPlaceholder(R.drawable.ic_music)
        val metadata = current?.metadata
        if (current == null || metadata == null) {
            renderIdle()
            return
        }

        source.text = appLabel(current.packageName)
        title.text = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        artist.text = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
        showArt(
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
        )

        val playing = current.isPlaying
        // Remembered so music can resume in the same app after the next start.
        if (playing) AutoStart.rememberPlayer(activity, current.packageName)
        playButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        playButton.contentDescription = activity.getString(if (playing) R.string.cd_pause else R.string.cd_play)

        renderProgress()
        handler.removeCallbacks(progressTicker)
        if (playing) handler.postDelayed(progressTicker, PROGRESS_INTERVAL_MS)
    }

    private fun renderNwd(info: NwdMedia.Info) {
        handler.removeCallbacks(progressTicker)
        // When the ticks stop, nothing else tells us; look again just after they would time out.
        handler.removeCallbacks(nwdRecheck)
        handler.postDelayed(nwdRecheck, NwdMedia.TICK_TIMEOUT_MS + 500)
        source.setText(if (info.isRadio) R.string.now_playing_source_radio else R.string.now_playing_source_nwd)
        title.text = info.title
        artist.text = info.subtitle
        showArt(null)
        setPlaceholder(if (info.isRadio) R.drawable.ic_radio else R.drawable.ic_music)
        // NWD broadcasts only while a source is playing.
        playButton.setImageResource(R.drawable.ic_pause)
        playButton.contentDescription = activity.getString(R.string.cd_pause)
        val hasTimes = info.durationMs > 0
        progress.fraction = if (hasTimes) info.positionMs.toFloat() / info.durationMs else 0f
        position.text = if (hasTimes) formatTime(info.positionMs) else ""
        duration.text = if (hasTimes) formatTime(info.durationMs) else ""
    }

    private fun setPlaceholder(icon: Int) {
        (artPlaceholder as ImageView).setImageResource(icon)
    }

    private fun sendMediaKey(keyCode: Int) {
        val audio = activity.getSystemService(AudioManager::class.java)
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun renderProgress() {
        if (nwdActive) return
        val current = controller ?: return
        val state = current.playbackState ?: return
        val total = current.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        var elapsed = state.position
        if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0) {
            elapsed += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
        }
        if (total > 0) elapsed = elapsed.coerceIn(0L, total)

        progress.fraction = if (total > 0) elapsed.toFloat() / total else 0f
        position.text = formatTime(elapsed)
        duration.text = if (total > 0) formatTime(total) else ""
    }

    private fun renderIdle() {
        source.setText(R.string.now_playing_idle_source)
        title.setText(R.string.now_playing_idle_title)
        artist.setText(R.string.now_playing_idle_subtitle)
        showArt(null)
        playButton.setImageResource(R.drawable.ic_play)
        playButton.contentDescription = activity.getString(R.string.cd_play)
        progress.fraction = 0f
        position.text = ""
        duration.text = ""
        handler.removeCallbacks(progressTicker)
    }

    private fun renderNoAccess() {
        renderIdle()
        title.setText(R.string.now_playing_no_access_title)
        artist.setText(R.string.now_playing_no_access_subtitle)
    }

    private fun showArt(bitmap: Bitmap?) {
        if (bitmap === shownArt) return
        shownArt = bitmap
        art.setImageBitmap(bitmap)
        artPlaceholder.visibility = if (bitmap == null) View.VISIBLE else View.GONE
    }

    private fun togglePlayback() {
        if (nwdActive) {
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            return
        }
        val current = controller
        when {
            // No session yet (e.g. right after start-up): resume the last player, open Spotify only if that fails.
            current == null -> AutoStart.playOrOpen(activity) { AppLauncher.startPackage(activity, SPOTIFY) }
            current.isPlaying -> current.transportControls.pause()
            else -> current.transportControls.play()
        }
    }

    private fun openPlayer() {
        if (nwdActive) {
            AppLauncher.startPackage(activity, if (nwd.info?.isRadio == true) NWD_RADIO else NWD_MUSIC)
            return
        }
        if (!hasListenerAccess()) {
            AppLauncher.start(activity, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return
        }
        val current = controller
        val sessionActivity = current?.sessionActivity
        if (sessionActivity != null) {
            try {
                sessionActivity.send()
                return
            } catch (e: android.app.PendingIntent.CanceledException) {
                // Fall through to launching the app directly.
            }
        }
        AppLauncher.startPackage(activity, current?.packageName ?: SPOTIFY)
    }

    private fun hasListenerAccess(): Boolean {
        val enabled = Settings.Secure.getString(activity.contentResolver, "enabled_notification_listeners")
        return enabled?.split(':')?.any { ComponentName.unflattenFromString(it) == listenerComponent } == true
    }

    private fun appLabel(packageName: String): String = try {
        val pm = activity.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: Exception) {
        packageName
    }

    private val MediaController.isPlaying: Boolean
        get() = playbackState?.state == PlaybackState.STATE_PLAYING

    private class RoundedOutline(private val radius: Float) : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }

    private companion object {
        const val SPOTIFY = "com.spotify.music"
        const val NWD_RADIO = "com.nwd.radio"
        const val NWD_MUSIC = "com.nwd.android.music.ui"
        const val PROGRESS_INTERVAL_MS = 1000L

        fun formatTime(ms: Long): String {
            val totalSeconds = ms / 1000
            return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
        }
    }
}
