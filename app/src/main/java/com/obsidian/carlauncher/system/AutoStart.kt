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

/** Things to do once each time the car starts: resume music and open a chosen app. */
object AutoStart {

    private const val PREFS = "auto_start"
    private const val KEY_RESUME_MUSIC = "resume_music"
    private const val KEY_LAUNCH_APP = "launch_app"
    private const val KEY_LAST_BOOT = "last_boot_id"
    private const val KEY_LAST_PLAYER = "last_player"

    private const val LAUNCH_DELAY_MS = 5_000L
    // On the head unit a PLAY sent 8 s after boot was lost, while the same key later worked;
    // so start a little later and retry until a session is actually playing.
    private const val RESUME_FIRST_DELAY_MS = 10_000L
    private const val RESUME_RETRY_MS = 5_000L
    private const val RESUME_ATTEMPTS = 4

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

    /** Called from the home screen's onCreate; acts only on the first start after each boot. */
    fun runIfNeeded(context: Context) {
        val appContext = context.applicationContext
        val bootId = currentBootId() ?: return
        val prefs = prefs(appContext)
        if (prefs.getString(KEY_LAST_BOOT, null) == bootId) return
        prefs.edit().putString(KEY_LAST_BOOT, bootId).apply()

        launchApp(appContext)?.let { component ->
            handler.postDelayed({ launch(appContext, component) }, LAUNCH_DELAY_MS)
        }
        if (isResumeMusicEnabled(appContext)) {
            handler.postDelayed({ resumeWithRetry(appContext, attempt = 1) }, RESUME_FIRST_DELAY_MS)
        }
    }

    private fun resumeWithRetry(context: Context, attempt: Int) {
        if (isAnythingPlaying(context)) return
        resumeMusic(context)
        if (attempt < RESUME_ATTEMPTS) {
            handler.postDelayed({ resumeWithRetry(context, attempt + 1) }, RESUME_RETRY_MS)
        }
    }

    /**
     * Resumes the app that played last: through its live session if it has one, otherwise by
     * sending PLAY straight to its media button receiver, which starts a stopped app
     * (Spotify resumes from where it was). Without a remembered app, Android routes the key.
     */
    private fun resumeMusic(context: Context) {
        val player = prefs(context).getString(KEY_LAST_PLAYER, null)
        if (player != null) {
            activeSessions(context).firstOrNull { it.packageName == player }?.let { session ->
                session.transportControls.play()
                return
            }
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
                return
            }
        }
        val audio = context.getSystemService(AudioManager::class.java)
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
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
