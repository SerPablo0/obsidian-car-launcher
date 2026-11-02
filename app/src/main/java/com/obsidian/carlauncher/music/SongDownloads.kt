package com.obsidian.carlauncher.music

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.obsidian.carlauncher.R
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Saves songs one at a time, in the background, so they keep going after the Music screen
 * closes. The audio is fetched in 512 KB pieces with a browser's identity, as the NewPipe app
 * downloads, because YouTube slows down or cuts off one long request.
 */
object SongDownloads {

    sealed class State {
        object Waiting : State()
        object Preparing : State()
        class Saving(val fraction: Float) : State()
        class Failed(val reason: String) : State()
    }

    private const val BLOCK_SIZE = 512 * 1024L
    private const val ATTEMPTS = 3
    private const val PROGRESS_INTERVAL_MS = 250L
    private const val COVER_LIMIT_BYTES = 2 * 1024 * 1024

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Main thread only. Songs that finished are no longer listed: their file says so. */
    private val states = HashMap<String, State>()
    private val listeners = mutableListOf<() -> Unit>()

    /** Songs saved since the app started; the Music screen re-reads the folder when it changes. */
    var finished = 0
        private set

    fun state(songId: String): State? = states[songId]

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /** Main thread. Does nothing for a song that is already on its way. */
    fun enqueue(context: Context, song: Music.Song) {
        val current = states[song.id]
        if (current != null && current !is State.Failed) return
        val appContext = context.applicationContext
        set(song.id, State.Waiting)
        worker.execute {
            try {
                download(appContext, song)
                main.post {
                    states.remove(song.id)
                    finished++
                    notifyListeners()
                    Toast.makeText(appContext, appContext.getString(R.string.music_saved, song.title), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                val reason = describe(appContext, e)
                main.post {
                    set(song.id, State.Failed(reason))
                    Toast.makeText(appContext, appContext.getString(R.string.music_download_failed, song.title, reason), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun download(context: Context, song: Music.Song) {
        main.post { set(song.id, State.Preparing) }
        Music.ensureNewPipe()
        val info = StreamInfo.getInfo(ServiceList.YouTube, song.url)
        val stream = pickAudio(info.audioStreams) ?: throw IOException(context.getString(R.string.music_no_audio))
        val extension = if (stream.format == MediaFormat.M4A) "m4a" else stream.format?.suffix ?: "webm"

        val folder = Music.folder()
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException(context.getString(R.string.music_storage_needed))
        val target = freeName(folder, Music.fileName(song), extension)
        val part = File(folder, ".${target.name}.part")
        try {
            part.outputStream().buffered().use { out -> fetch(stream.content, out) { fraction -> main.post { set(song.id, State.Saving(fraction)) } } }
            if (!part.renameTo(target)) throw IOException("rename failed")
        } finally {
            part.delete()
        }
        song.coverUrl?.let { saveCover(it, Music.LocalSong(target, song.title, song.artist).cover) }
        Music.remember(context, song.id, target)
    }

    /** AAC in m4a plays everywhere, the head unit's own player included; Opus only when that is all there is. */
    private fun pickAudio(streams: List<AudioStream>): AudioStream? {
        val usable = streams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        // Videos dubbed in several languages list each track; the original is the song.
        val original = usable.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }.ifEmpty { usable }
        return original.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate }
            ?: original.maxByOrNull { it.averageBitrate }
    }

    private fun fetch(url: String, out: OutputStream, onProgress: (Float) -> Unit) {
        var done = 0L
        var total = -1L
        var lastReport = 0L
        val buffer = ByteArray(64 * 1024)
        while (total < 0 || done < total) {
            var attempt = 1
            while (true) {
                val blockStart = done
                try {
                    val connection = open(url, done, done + BLOCK_SIZE - 1)
                    try {
                        val code = connection.responseCode
                        if (code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
                        if (total < 0) {
                            total = if (code == HttpURLConnection.HTTP_OK) connection.contentLengthLong else totalFrom(connection.getHeaderField("Content-Range"))
                            if (total <= 0) throw IOException("unknown size")
                        }
                        connection.inputStream.use { input ->
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                                done += read
                                val now = SystemClock.uptimeMillis()
                                if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                                    lastReport = now
                                    onProgress(done.toFloat() / total)
                                }
                            }
                        }
                        // A server that ignores the range sends the whole file at once.
                        if (code == HttpURLConnection.HTTP_OK) done = total
                    } finally {
                        connection.disconnect()
                    }
                    break
                } catch (e: IOException) {
                    // A piece cut short continues from where it stopped; only failing again
                    // and again without getting any further gives up.
                    if (done > blockStart) attempt = 1 else if (++attempt > ATTEMPTS) throw e
                }
            }
        }
        onProgress(1f)
    }

    private fun open(url: String, start: Long, end: Long): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = NewPipeHttp.TIMEOUT_MS
            readTimeout = NewPipeHttp.TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", NewPipeHttp.USER_AGENT)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Range", "bytes=$start-$end")
        }

    /** "bytes 0-524287/3456789" → 3456789. */
    private fun totalFrom(contentRange: String?): Long =
        contentRange?.substringAfterLast('/', "")?.trim()?.toLongOrNull() ?: -1L

    private fun freeName(folder: File, base: String, extension: String): File {
        var file = File(folder, "$base.$extension")
        var copy = 2
        while (file.exists()) file = File(folder, "$base ($copy).$extension").also { copy++ }
        return file
    }

    /** The cover is a nicety: a failure here never fails the song. */
    private fun saveCover(url: String, target: File) {
        try {
            val folder = target.parentFile ?: return
            if (!folder.isDirectory) {
                folder.mkdirs()
                File(folder, ".nomedia").createNewFile()
            }
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = NewPipeHttp.TIMEOUT_MS
                readTimeout = NewPipeHttp.TIMEOUT_MS
                setRequestProperty("User-Agent", NewPipeHttp.USER_AGENT)
            }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK || connection.contentLengthLong > COVER_LIMIT_BYTES) return
                connection.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            target.delete()
        }
    }

    private fun describe(context: Context, e: Exception): String = when (e) {
        is ReCaptchaException -> context.getString(R.string.music_youtube_check)
        is java.net.UnknownHostException, is java.net.SocketTimeoutException -> context.getString(R.string.music_offline)
        else -> e.message?.take(120) ?: e.javaClass.simpleName
    }

    private fun set(songId: String, state: State) {
        states[songId] = state
        notifyListeners()
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it() }
    }
}
