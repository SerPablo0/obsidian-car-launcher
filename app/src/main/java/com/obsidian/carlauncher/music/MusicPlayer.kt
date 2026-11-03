package com.obsidian.carlauncher.music

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import java.io.IOException

/**
 * Plays saved songs. It lives as long as the app process, which the system keeps running for
 * the bar and the media listener, so music goes on after the Music screen closes. Its media
 * session lets the home screen's now-playing card and the media keys control it.
 */
object MusicPlayer {

    enum class Repeat { OFF, ALL, ONE }

    private const val RESTART_THRESHOLD_MS = 3_000
    /** The longest screen-off still taken for the blackout while the engine is cranked. */
    private const val BLACKOUT_MAX_MS = 20_000L
    private const val PREFS = "music_player"
    private const val KEY_SHUFFLE = "shuffle"
    private const val KEY_REPEAT = "repeat"
    private const val COVER_MAX_PX = 512

    private var appContext: Context? = null
    private var screenWatched = false
    /** When switching the car off paused the music; 0 otherwise. */
    private var pausedForSleepAt = 0L
    private var player: MediaPlayer? = null
    private var session: MediaSession? = null
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false
    private var queue: List<Music.LocalSong> = emptyList()
    /** The order songs play in, as indices into [queue]; shuffled while [shuffle] is on. */
    private var order: List<Int> = emptyList()
    /** Where playback is in [order]. */
    private var position = -1
    private var settingsLoaded = false
    private val listeners = mutableListOf<() -> Unit>()

    var shuffle = false
        private set
    var repeat = Repeat.OFF
        private set

    val current: Music.LocalSong? get() = order.getOrNull(position)?.let(queue::getOrNull)
    val isPlaying: Boolean get() = player?.isPlaying == true
    val positionMs: Int get() = player?.currentPosition ?: 0
    val durationMs: Int get() = player?.duration ?: 0

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /** Reads the saved shuffle and repeat choices; cheap after the first call. */
    fun loadSettings(context: Context) {
        if (settingsLoaded) return
        settingsLoaded = true
        val prefs = prefs(context)
        shuffle = prefs.getBoolean(KEY_SHUFFLE, false)
        repeat = Repeat.values().getOrNull(prefs.getInt(KEY_REPEAT, 0)) ?: Repeat.OFF
    }

    /** Main thread. */
    fun play(context: Context, songs: List<Music.LocalSong>, start: Int) {
        appContext = context.applicationContext
        loadSettings(context)
        watchScreen(context.applicationContext)
        queue = songs
        arrange(start)
        load(position, autoplay = true)
    }

    /** Every song in random order, starting anywhere. */
    fun playShuffled(context: Context, songs: List<Music.LocalSong>) {
        if (songs.isEmpty()) return
        loadSettings(context)
        if (!shuffle) toggleShuffle(context)
        play(context, songs, songs.indices.random())
    }

    fun toggleShuffle(context: Context) {
        loadSettings(context)
        shuffle = !shuffle
        prefs(context).edit().putBoolean(KEY_SHUFFLE, shuffle).apply()
        // The song playing now stays; the rest are put in the new order around it.
        order.getOrNull(position)?.let(::arrange)
        notifyListeners()
    }

    fun cycleRepeat(context: Context) {
        loadSettings(context)
        repeat = Repeat.values()[(repeat.ordinal + 1) % Repeat.values().size]
        prefs(context).edit().putInt(KEY_REPEAT, repeat.ordinal).apply()
        notifyListeners()
    }

    fun seekTo(positionMs: Int) {
        player?.seekTo(positionMs)
        publishState()
    }

    /** Sets [order] and [position] so that the song at [startIndex] of [queue] plays now. */
    private fun arrange(startIndex: Int) {
        if (shuffle) {
            order = listOf(startIndex) + queue.indices.filter { it != startIndex }.shuffled()
            position = 0
        } else {
            order = queue.indices.toList()
            position = startIndex
        }
    }

    /**
     * Switching the car off only turns the screen off. Music left playing kept the head unit awake,
     * draining the car battery, and was still going at the next start; so it pauses then. Cranking
     * the engine turns the screen off for a moment too, and the music carries on after that.
     */
    private fun watchScreen(context: Context) {
        if (screenWatched) return
        screenWatched = true
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> if (isPlaying) {
                        pause()
                        abandonFocus()
                        pausedForSleepAt = SystemClock.elapsedRealtime()
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        val pausedAt = pausedForSleepAt
                        pausedForSleepAt = 0L
                        if (pausedAt > 0 && SystemClock.elapsedRealtime() - pausedAt < BLACKOUT_MAX_MS) resume()
                    }
                }
            }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
    }

    fun toggle() = if (isPlaying) pause() else resume()

    /** The Next button: the next song, from the top again at the end when repeating. */
    fun next() {
        when {
            position + 1 < order.size -> load(position + 1, autoplay = true)
            repeat != Repeat.OFF && order.isNotEmpty() -> startOver()
            else -> stopAtEnd()
        }
    }

    fun previous() {
        val mediaPlayer = player ?: return
        when {
            mediaPlayer.currentPosition > RESTART_THRESHOLD_MS -> restartSong()
            position > 0 -> load(position - 1, autoplay = true)
            repeat != Repeat.OFF && order.size > 1 -> load(order.size - 1, autoplay = true)
            else -> restartSong()
        }
    }

    /** A deleted song cannot keep playing. */
    fun forget(song: Music.LocalSong) {
        if (current?.file != song.file) return
        release()
        queue = emptyList()
        order = emptyList()
        position = -1
        notifyListeners()
    }

    /** Covers are small JPEGs; anything larger is scaled down before it goes into the session. */
    private fun coverOf(song: Music.LocalSong): Bitmap? {
        val path = song.cover.takeIf { it.exists() }?.path ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > COVER_MAX_PX) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun onSongEnded() {
        if (repeat == Repeat.ONE) restartSong(play = true) else next()
    }

    /** The end of the list while repeating: a shuffled list is mixed again for the next round. */
    private fun startOver() {
        if (shuffle) order = queue.indices.shuffled()
        load(0, autoplay = true)
    }

    private fun restartSong(play: Boolean = false) {
        player?.seekTo(0)
        if (play) player?.start()
        publishState()
    }

    private fun load(newPosition: Int, autoplay: Boolean) {
        val context = appContext ?: return
        val song = order.getOrNull(newPosition)?.let(queue::getOrNull) ?: return
        player?.release()
        position = newPosition
        player = try {
            MediaPlayer().apply {
                setAudioAttributes(AUDIO_ATTRIBUTES)
                setDataSource(song.file.path)
                setOnCompletionListener { onSongEnded() }
                prepare()
            }
        } catch (e: IOException) {
            null
        }
        if (player == null) {
            // An unreadable file (deleted meanwhile) is skipped rather than stopping the list.
            if (newPosition + 1 < order.size) load(newPosition + 1, autoplay) else notifyListeners()
            return
        }
        session(context).setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, song.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, song.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, player?.duration?.toLong() ?: 0L)
                // The saved cover, for the home screen's now-playing card.
                .apply { coverOf(song)?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                .build(),
        )
        if (autoplay) resume() else publishState()
    }

    private fun resume() {
        val mediaPlayer = player ?: return
        if (!requestFocus()) return
        mediaPlayer.start()
        appContext?.let { session(it).isActive = true }
        publishState()
    }

    private fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
        publishState()
    }

    private fun stopAtEnd() {
        player?.let {
            it.pause()
            it.seekTo(0)
        }
        abandonFocus()
        publishState()
    }

    private fun release() {
        player?.release()
        player = null
        abandonFocus()
        session?.isActive = false
        publishState()
    }

    private fun publishState() {
        val mediaPlayer = player
        val state = when {
            mediaPlayer == null -> PlaybackState.STATE_STOPPED
            mediaPlayer.isPlaying -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP,
                )
                .setState(state, mediaPlayer?.currentPosition?.toLong() ?: 0L, 1f)
                .build(),
        )
        notifyListeners()
    }

    private fun session(context: Context): MediaSession =
        session ?: MediaSession(context, "ObsidianMusic").also {
            it.setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = previous()
                override fun onStop() = pause()
                override fun onSeekTo(pos: Long) {
                    player?.seekTo(pos.toInt())
                    publishState()
                }
            })
            session = it
        }

    private fun requestFocus(): Boolean {
        val audio = appContext?.getSystemService(AudioManager::class.java) ?: return false
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AUDIO_ATTRIBUTES)
            .setOnAudioFocusChangeListener(::onFocusChange)
            .build()
            .also { focusRequest = it }
        return audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        appContext?.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request)
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            // Another player took over for good (the radio, Spotify): stop for it.
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                pause()
                abandonFocus()
            }
            // A navigation prompt or a call: pause, then carry on.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                resumeOnFocusGain = isPlaying
                pause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocusGain) {
                resumeOnFocusGain = false
                resume()
            }
        }
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it() }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
}
