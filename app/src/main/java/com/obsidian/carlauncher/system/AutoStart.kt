package com.obsidian.carlauncher.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import java.io.File

/** Things to do once each time the car starts: resume music and open a chosen app. */
object AutoStart {

    private const val PREFS = "auto_start"
    private const val KEY_RESUME_MUSIC = "resume_music"
    private const val KEY_LAUNCH_APP = "launch_app"
    private const val KEY_LAST_BOOT = "last_boot_id"

    // Opening the app first lets it settle before audio starts; both wait for Bluetooth and audio to be up.
    private const val LAUNCH_DELAY_MS = 5_000L
    private const val RESUME_DELAY_MS = 8_000L

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
            handler.postDelayed({ resumeMusic(appContext) }, RESUME_DELAY_MS)
        }
    }

    /**
     * A PLAY key with no active session goes to the app that last played media,
     * which Android remembers across reboots, so this resumes Spotify, YouTube Music, etc.
     */
    fun resumeMusic(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java)
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
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
