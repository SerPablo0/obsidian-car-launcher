package com.obsidian.carlauncher.drive

import android.app.Activity
import android.content.ComponentName
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import com.obsidian.carlauncher.Accent
import com.obsidian.carlauncher.AppLauncher
import com.obsidian.carlauncher.R
import com.obsidian.carlauncher.drive.TripComputer.GpsState
import com.obsidian.carlauncher.media.MediaListenerService
import com.obsidian.carlauncher.media.NwdMedia

/**
 * Driving mode: the speed in large digits, this trip so far, the clock, media controls and
 * two big shortcuts. Opens by itself once per drive when [TripComputer] says so.
 */
class DriveActivity : Activity() {

    private lateinit var speed: TextView
    private lateinit var gpsState: TextView
    private lateinit var track: TextView
    private lateinit var playButton: ImageButton
    private lateinit var distance: TextView
    private lateinit var duration: TextView
    private lateinit var average: TextView
    private lateinit var topSpeed: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val sessionManager by lazy { getSystemService(MediaSessionManager::class.java) }
    private val listenerComponent by lazy { ComponentName(this, MediaListenerService::class.java) }
    private val tripListener: () -> Unit = { renderTrip() }

    private val mediaTicker = object : Runnable {
        override fun run() {
            renderMedia()
            handler.postDelayed(this, MEDIA_TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Accent.current(this).theme)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_drive)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        speed = findViewById(R.id.drive_speed)
        gpsState = findViewById(R.id.drive_gps_state)
        track = findViewById(R.id.drive_track)
        playButton = findViewById(R.id.drive_play)
        distance = stat(R.id.drive_distance, R.string.drive_stat_distance)
        duration = stat(R.id.drive_duration, R.string.drive_stat_time)
        average = stat(R.id.drive_average, R.string.drive_stat_average)
        topSpeed = stat(R.id.drive_top_speed, R.string.drive_stat_top)

        findViewById<View>(R.id.drive_exit).setOnClickListener { finish() }
        findViewById<View>(R.id.drive_previous).setOnClickListener { sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
        findViewById<View>(R.id.drive_next).setOnClickListener { sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }
        playButton.setOnClickListener {
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            handler.postDelayed({ renderMedia() }, MEDIA_REFRESH_MS)
        }
        findViewById<View>(R.id.drive_maps).setOnClickListener { openMaps() }
        findViewById<View>(R.id.drive_phone).setOnClickListener { AppLauncher.startPackage(this, PHONE_PACKAGE) }
        gpsState.setOnClickListener {
            if (TripComputer.gpsState == GpsState.NO_PERMISSION) TripComputer.requestPermission(this)
        }

        TripComputer.start(this)
        TripComputer.onDriveModeShown()
        NwdMedia.ensureRegistered(this)
    }

    override fun onStart() {
        super.onStart()
        TripComputer.addListener(tripListener)
        // Asked once; after that the state line under the speed offers it again on a tap.
        TripComputer.requestPermissionOnce(this)
        renderTrip()
        handler.post(mediaTicker)
    }

    override fun onStop() {
        TripComputer.removeListener(tripListener)
        handler.removeCallbacksAndMessages(null)
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == TripComputer.REQUEST_LOCATION) TripComputer.refresh()
    }

    private fun stat(@IdRes id: Int, @StringRes label: Int): TextView {
        val item = findViewById<View>(id)
        item.findViewById<TextView>(R.id.stat_label).setText(label)
        return item.findViewById(R.id.stat_value)
    }

    private fun renderTrip() {
        speed.setTextIfChanged(TripComputer.speedKmh?.toString() ?: getString(R.string.drive_speed_none))
        speed.setTextColor(getColor(if (TripComputer.isOverLimit) R.color.warning else R.color.text_primary))

        val message = when (TripComputer.gpsState) {
            GpsState.DISABLED -> R.string.drive_speedometer_off
            GpsState.NO_PERMISSION -> R.string.drive_gps_no_permission
            GpsState.OFF -> R.string.drive_gps_off
            GpsState.WAITING -> R.string.drive_gps_waiting
            GpsState.OK -> null
        }
        gpsState.visibility = if (message == null) View.GONE else View.VISIBLE
        message?.let { gpsState.setTextIfChanged(getString(it)) }

        val trip = TripComputer.current
        distance.setTextIfChanged(TripFormat.distance(this, trip.distanceM))
        duration.setTextIfChanged(TripFormat.duration(this, trip.durationMs))
        average.setTextIfChanged(TripFormat.speed(this, trip.averageKmh))
        topSpeed.setTextIfChanged(TripFormat.speed(this, trip.maxKmh.takeIf { it > 0 }))
    }

    private fun renderMedia() {
        val session = activeSessions().let { sessions -> sessions.firstOrNull { it.isPlaying } ?: sessions.firstOrNull() }
        val nwd = NwdMedia.info?.takeIf { it.isPlaying }
        val playing: Boolean
        val line: String?
        if (session?.isPlaying != true && nwd != null) {
            playing = true
            line = listOf(nwd.title, nwd.subtitle).filter { it.isNotBlank() }.joinToString(" · ")
        } else {
            playing = session?.isPlaying == true
            val metadata = session?.metadata
            line = metadata?.let {
                listOfNotNull(
                    it.getString(MediaMetadata.METADATA_KEY_TITLE),
                    it.getString(MediaMetadata.METADATA_KEY_ARTIST),
                ).filter { text -> text.isNotBlank() }.joinToString(" · ")
            }
        }
        track.setTextIfChanged(line?.ifEmpty { null } ?: getString(R.string.now_playing_idle_title))
        playButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        playButton.contentDescription = getString(if (playing) R.string.cd_pause else R.string.cd_play)
    }

    private fun activeSessions(): List<MediaController> = try {
        sessionManager.getActiveSessions(listenerComponent)
    } catch (e: SecurityException) {
        emptyList()
    }

    private val MediaController.isPlaying: Boolean
        get() = playbackState?.state == PlaybackState.STATE_PLAYING

    private fun sendMediaKey(keyCode: Int) {
        val audio = getSystemService(AudioManager::class.java)
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun openMaps() {
        // The NWD firmware stores the user's chosen navigation app in this system setting.
        val chosen = Settings.System.getString(contentResolver, "navigation_packagename")
        if (chosen != null && AppLauncher.startPackage(this, chosen)) return
        AppLauncher.startPackage(this, GOOGLE_MAPS_PACKAGE)
    }

    private companion object {
        const val MEDIA_TICK_MS = 2_000L
        const val MEDIA_REFRESH_MS = 600L
        const val PHONE_PACKAGE = "com.nwd.android.phone"
        const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
    }
}
