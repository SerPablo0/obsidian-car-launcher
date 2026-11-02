package com.obsidian.carlauncher.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.obsidian.carlauncher.media.MediaListenerService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Things to do once each time the car starts: resume music and open a chosen app. */
object AutoStart {

    private const val PREFS = "auto_start"
    private const val KEY_RESUME_MUSIC = "resume_music"
    private const val KEY_LAUNCH_APP = "launch_app"
    private const val KEY_LAST_BOOT = "last_boot_id"
    private const val KEY_LAST_PLAYER = "last_player"

    private const val KEY_RESUME_LOG = "resume_log"

    private const val LAUNCH_DELAY_MS = 5_000L
    // On the head unit a PLAY sent 8 s after boot was lost, while the same key worked when
    // pressed later; so start a little later and retry until a session is actually playing.
    private const val RESUME_FIRST_DELAY_MS = 12_000L
    private const val RESUME_RETRY_MS = 6_000L
    private const val RESUME_ATTEMPTS = 6
    // Spotify needs several seconds to cold start before it reports playing.
    private const val BUTTON_CHECK_MS = 6_000L
    /** Screen off at least this long means the car was switched off. */
    private const val CAR_OFF_MIN_MS = 90_000L

    private val handler = Handler(Looper.getMainLooper())

    fun isResumeMusicEnabled(context: Context) = prefs(context).getBoolean(KEY_RESUME_MUSIC, false)

    fun setResumeMusicEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_RESUME_MUSIC, enabled).apply()
    }

    fun launchApp(context: Context): ComponentName? =
        prefs(context).getString(KEY_LAUNCH_APP, null)?.let(ComponentName::unflattenFromString)

    fun setLaunchApp(context: Context, component: ComponentName?) {
        prefs(context).edit().apply {
            if (component == null) remove(KEY_LAUNCH_APP) else putString(KEY_LAUNCH_APP, component.flattenToString())
        }.apply()
    }

    /** Called by the now-playing card whenever an app session is playing. */
    fun rememberPlayer(context: Context, packageName: String) {
        val prefs = prefs(context)
        if (prefs.getString(KEY_LAST_PLAYER, null) != packageName) {
            prefs.edit().putString(KEY_LAST_PLAYER, packageName).apply()
        }
    }

    /** Called from the home screen's onCreate; acts only on the first start after each real boot. */
    fun runIfNeeded(context: Context) {
        val appContext = context.applicationContext
        val bootId = currentBootId() ?: return
        val prefs = prefs(appContext)
        if (prefs.getString(KEY_LAST_BOOT, null) == bootId) return
        prefs.edit().putString(KEY_LAST_BOOT, bootId).apply()
        runStartActions(appContext, "boot")
    }

    /**
     * The head unit rarely boots: switching the car off only puts it to sleep (it had been up
     * for 26 days). So a screen that comes back after a long time off is a car start too.
     * Screen-off for a moment (the Screen Off app, a short stop) does not count.
     */
    private var screenOffAt = 0L

    fun onScreenOff() {
        screenOffAt = SystemClock.elapsedRealtime()
    }

    fun onScreenOn(context: Context) {
        val offAt = screenOffAt
        screenOffAt = 0L
        if (offAt == 0L) return
        val offSeconds = (SystemClock.elapsedRealtime() - offAt) / 1000
        if (offSeconds * 1000 >= CAR_OFF_MIN_MS) {
            runStartActions(context.applicationContext, "screen woke after ${offSeconds}s off")
        } else {
            log(context.applicationContext, "screen woke after ${offSeconds}s off: too short for a car start")
        }
    }

    private fun runStartActions(context: Context, reason: String) {
        handler.removeCallbacksAndMessages(null)
        prefs(context).edit().putString(KEY_RESUME_LOG, "").apply()
        log(context, "car start detected: $reason")
        launchApp(context)?.let { component ->
            handler.postDelayed({ launch(context, component) }, LAUNCH_DELAY_MS)
        }
        if (isResumeMusicEnabled(context)) {
            handler.postDelayed({ resumeWithRetry(context, attempt = 1) }, RESUME_FIRST_DELAY_MS)
        }
    }

    private fun resumeWithRetry(context: Context, attempt: Int) {
        if (isAnythingPlaying(context)) {
            log(context, "attempt $attempt: already playing, done")
            return
        }
        val method = resume(context, attempt)
        log(context, "attempt $attempt: $method")
        if (attempt < RESUME_ATTEMPTS) {
            handler.postDelayed({ resumeWithRetry(context, attempt + 1) }, RESUME_RETRY_MS)
        } else {
            handler.postDelayed({ log(context, "after last attempt: playing=${isAnythingPlaying(context)}") }, RESUME_RETRY_MS)
        }
    }

    /**
     * For the card's play button when no app has a session yet: try to resume the last player,
     * and call [open] only if nothing starts playing.
     */
    fun playOrOpen(context: Context, open: () -> Unit) {
        val appContext = context.applicationContext
        resume(appContext, attempt = 1)
        handler.postDelayed({ if (!isAnythingPlaying(appContext)) open() }, BUTTON_CHECK_MS)
    }

    /**
     * One resume attempt. A live session of the last player gets play(); otherwise odd attempts
     * send PLAY through the system, which routes it to the app that played last (this is what
     * worked on the head unit), and even attempts send it straight to that app's media button
     * receiver, which can start an app that is not running. Returns what was tried, for the log.
     */
    private fun resume(context: Context, attempt: Int): String {
        val player = prefs(context).getString(KEY_LAST_PLAYER, null)
        if (player != null) {
            activeSessions(context).firstOrNull { it.packageName == player }?.let { session ->
                session.transportControls.play()
                return "session play() on $player"
            }
        }
        if (attempt % 2 == 0 && player != null) {
            val receiver = context.packageManager
                .queryBroadcastReceivers(Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(player), 0)
                .firstOrNull()?.activityInfo
            if (receiver != null) {
                val component = ComponentName(receiver.packageName, receiver.name)
                for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    context.sendBroadcast(
                        Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(component)
                            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY)),
                    )
                }
                return "media button to ${component.flattenToShortString()}"
            }
        }
        val audio = context.getSystemService(AudioManager::class.java)
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
        return "system media key (last player: ${player ?: "unknown"})"
    }

    /** What the last start-up resume did, for the settings page's diagnostics. */
    fun resumeLog(context: Context): String = prefs(context).getString(KEY_RESUME_LOG, null).orEmpty()

    private fun log(context: Context, line: String) {
        val prefs = prefs(context)
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        prefs.edit().putString(KEY_RESUME_LOG, prefs.getString(KEY_RESUME_LOG, "") + "$time  $line\n").apply()
    }

    private fun isAnythingPlaying(context: Context) =
        activeSessions(context).any { it.playbackState?.state == PlaybackState.STATE_PLAYING }

    private fun activeSessions(context: Context) = try {
        context.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(ComponentName(context, MediaListenerService::class.java))
    } catch (e: SecurityException) {
        emptyList()
    }

    private fun launch(context: Context, component: ComponentName) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: RuntimeException) {
            // App removed since it was chosen.
        }
    }

    private fun currentBootId(): String? = try {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    } catch (e: Exception) {
        null
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
