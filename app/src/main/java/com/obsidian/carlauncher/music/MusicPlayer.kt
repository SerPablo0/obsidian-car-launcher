package com.obsidian.carlauncher.music

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import java.io.IOException

/**
 * Plays saved songs. It lives as long as the app process, which the system keeps running for
 * the bar and the media listener, so music goes on after the Music screen closes. Its media
 * session lets the home screen's now-playing card and the media keys control it.
 */
object MusicPlayer {

    private const val RESTART_THRESHOLD_MS = 3_000

    private var appContext: Context? = null
    private var player: MediaPlayer? = null
    private var session: MediaSession? = null
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false
    private var queue: List<Music.LocalSong> = emptyList()
    private var index = -1
    private val listeners = mutableListOf<() -> Unit>()

    val current: Music.LocalSong? get() = queue.getOrNull(index)
    val isPlaying: Boolean get() = player?.isPlaying == true

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /** Main thread. */
    fun play(context: Context, songs: List<Music.LocalSong>, start: Int) {
        appContext = context.applicationContext
        queue = songs
        load(start, autoplay = true)
    }

    fun toggle() = if (isPlaying) pause() else resume()

    fun next() {
        if (index + 1 < queue.size) load(index + 1, autoplay = true) else stopAtEnd()
    }

    fun previous() {
        val mediaPlayer = player ?: return
        if (mediaPlayer.currentPosition > RESTART_THRESHOLD_MS || index == 0) {
            mediaPlayer.seekTo(0)
            publishState()
        } else {
            load(index - 1, autoplay = true)
        }
    }

    /** A deleted song cannot keep playing. */
    fun forget(song: Music.LocalSong) {
        if (current?.file != song.file) return
        release()
        queue = emptyList()
        index = -1
        notifyListeners()
    }

    private fun load(newIndex: Int, autoplay: Boolean) {
        val context = appContext ?: return
        val song = queue.getOrNull(newIndex) ?: return
        player?.release()
        index = newIndex
        player = try {
            MediaPlayer().apply {
                setAudioAttributes(AUDIO_ATTRIBUTES)
                setDataSource(song.file.path)
                setOnCompletionListener { next() }
                prepare()
            }
        } catch (e: IOException) {
            null
        }
        if (player == null) {
            // An unreadable file is skipped rather than stopping the list.
            if (newIndex + 1 < queue.size) load(newIndex + 1, autoplay) else notifyListeners()
            return
        }
        session(context).setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, song.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, song.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, player?.duration?.toLong() ?: 0L)
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

    private val AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
}
