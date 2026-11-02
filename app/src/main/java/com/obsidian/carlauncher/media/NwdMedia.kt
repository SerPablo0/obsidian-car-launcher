package com.obsidian.carlauncher.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * What the NWD radio, Bluetooth music and USB music apps are playing.
 *
 * They do not publish Android media sessions; instead they broadcast
 * com.nwd.action.send_media_play_info / send_media_play_time. The extra names come
 * from strings in those apps (extra_media_name, extra_psname, extra_frequence, ...);
 * which ones each source fills is not documented, so parsing is deliberately lenient
 * and the raw extras are kept for the settings page's diagnostics.
 */
object NwdMedia {

    class Info(
        val title: String,
        val subtitle: String,
        val isRadio: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        /** Uptime of the last play-time broadcast; 0 until one arrives. */
        val lastTickAt: Long,
    ) {
        /**
         * The NWD music app announces its last track at every boot without playing it, so a
         * track name alone proves nothing. Only a source whose play time keeps ticking is playing.
         */
        val isPlaying: Boolean get() = lastTickAt > 0 && SystemClock.uptimeMillis() - lastTickAt < TICK_TIMEOUT_MS
    }

    private const val ACTION_INFO = "com.nwd.action.send_media_play_info"
    private const val ACTION_TIME = "com.nwd.action.send_media_play_time"
    /** Play-time broadcasts come about once a second while playing. */
    const val TICK_TIMEOUT_MS = 4_000L

    private const val PREFS = "nwd_media"
    private const val KEY_LAST_INFO = "last_info"
    private const val KEY_LAST_TIME = "last_time"

    var info: Info? = null
        private set

    private val listeners = mutableListOf<() -> Unit>()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val extras = intent.extras ?: return
            saveDiagnostics(context, intent.action.orEmpty(), extras)
            info = when (intent.action) {
                ACTION_INFO -> parseInfo(extras)
                ACTION_TIME -> info?.let { parseTime(it, extras) }
                else -> info
            }
            listeners.toList().forEach { it() }
        }
    }

    /**
     * Listens for the whole life of the process (the launcher, which Android keeps alive as
     * the home app), so a source that started while another app was in front is still known.
     * Main thread only; safe to call repeatedly.
     */
    fun ensureRegistered(context: Context) {
        if (registered) return
        registered = true
        val filter = IntentFilter().apply {
            addAction(ACTION_INFO)
            addAction(ACTION_TIME)
        }
        context.applicationContext.registerReceiver(receiver, filter, null, Handler(Looper.getMainLooper()))
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /** The most recent raw broadcasts, shown in settings so the parsing can be refined. */
    fun diagnostics(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return listOfNotNull(prefs.getString(KEY_LAST_INFO, null), prefs.getString(KEY_LAST_TIME, null)).joinToString("\n\n")
    }

    private fun saveDiagnostics(context: Context, action: String, extras: Bundle) {
        val key = if (action == ACTION_TIME) KEY_LAST_TIME else KEY_LAST_INFO
        val dump = extras.keySet().sorted().joinToString("\n") { "$it = ${extras.get(it)}" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(key, "${action.substringAfterLast('.')}:\n$dump")
            .apply()
    }

    private fun parseInfo(extras: Bundle): Info? {
        val frequency = extras.text("extra_frequence", "extra_radio_frequence", "extra_freq")
        val station = extras.text("extra_psname", "extra_rds")
        val isRadio = frequency != null || station != null || extras.text("extra_band") != null
        val title = if (isRadio) {
            station ?: frequency?.let(::formatFrequency)
        } else {
            extras.text("extra_media_name", "extra_title", "extra_avrcp_id3_title")
        } ?: return null
        val subtitle = if (isRadio) {
            listOfNotNull(extras.text("extra_band"), frequency?.let(::formatFrequency).takeIf { station != null }).joinToString(" · ")
        } else {
            extras.text("extra_media_artist", "extra_artist", "extra_avrcp_id3_artist").orEmpty()
        }
        return Info(
            title = title,
            subtitle = subtitle,
            isRadio = isRadio,
            positionMs = extras.millis("extra_media_current_position", "extra_playtime"),
            durationMs = extras.millis("extra_meida_duration", "extra_media_total_time"),
            // A new track keeps the tick of the one before, so a source that was already playing stays shown.
            lastTickAt = info?.lastTickAt ?: 0,
        )
    }

    private fun parseTime(current: Info, extras: Bundle) = Info(
        title = current.title,
        subtitle = current.subtitle,
        isRadio = current.isRadio,
        positionMs = extras.millis("extra_media_current_position", "extra_playtime", "extra_widgetcurtime")
            .takeIf { it > 0 } ?: current.positionMs,
        durationMs = extras.millis("extra_meida_duration", "extra_media_total_time", "extra_widgettotaltime")
            .takeIf { it > 0 } ?: current.durationMs,
        lastTickAt = SystemClock.uptimeMillis(),
    )

    private fun Bundle.text(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> get(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "0" } }

    /** Accepts seconds or milliseconds: values under a day in seconds are scaled up. */
    private fun Bundle.millis(vararg keys: String): Long {
        val value = keys.firstNotNullOfOrNull { key -> get(key)?.toString()?.toLongOrNull() } ?: return 0
        return if (value in 1 until 86_400) value * 1000 else value
    }

    /** NWD sends FM as e.g. 9850 (98.50 MHz) or 98.5, and AM as e.g. 1026 (kHz). */
    private fun formatFrequency(raw: String): String {
        val number = raw.toDoubleOrNull() ?: return raw
        return when {
            number in 8_750.0..10_800.0 -> "%.1f MHz".format(number / 100)
            number in 87.5..108.0 -> "%.1f MHz".format(number)
            else -> "${number.toLong()} kHz"
        }
    }
}
