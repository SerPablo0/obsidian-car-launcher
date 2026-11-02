package com.obsidian.carlauncher.music

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.Image
import java.io.File
import java.util.Locale

/**
 * Songs found on YouTube Music and the ones saved for offline listening. Saved songs are
 * ordinary audio files in Music/Obsidian, so the head unit's own music player plays them too.
 */
object Music {

    /** A YouTube Music search result. */
    class Song(
        val id: String,
        val url: String,
        val title: String,
        val artist: String,
        val durationSec: Long,
        val thumbnailUrl: String?,
        /** The largest picture offered, saved as the cover of a downloaded song. */
        val coverUrl: String?,
    )

    /** A song saved on the head unit. */
    class LocalSong(val file: File, val title: String, val artist: String) {
        val cover: File get() = File(coversFolder(), file.nameWithoutExtension + ".jpg")
    }

    private val AUDIO_EXTENSIONS = setOf("m4a", "webm", "opus", "mp3")
    private const val PREFS = "music"
    private const val KEY_ID_PREFIX = "id:"
    private const val MAX_NAME_LENGTH = 120

    @Volatile
    private var initialised = false

    /** Blocking network call: run off the main thread. */
    fun search(query: String): List<Song> {
        ensureNewPipe()
        val service = ServiceList.YouTube
        val handler = service.searchQHFactory.fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")
        return SearchInfo.getInfo(service, handler).relatedItems.filterIsInstance<StreamInfoItem>().map { item ->
            Song(
                id = videoId(item.url),
                url = item.url,
                title = item.name,
                artist = item.uploaderName.orEmpty(),
                durationSec = item.duration,
                thumbnailUrl = pick(item.thumbnails, preferredHeight = THUMB_HEIGHT),
                coverUrl = pick(item.thumbnails, preferredHeight = COVER_HEIGHT),
            )
        }
    }

    fun ensureNewPipe() {
        if (initialised) return
        synchronized(this) {
            if (initialised) return
            val locale = Locale.getDefault()
            NewPipe.init(NewPipeHttp, Localization.fromLocale(locale), ContentCountry(locale.country.ifEmpty { "US" }))
            initialised = true
        }
    }

    fun folder(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Obsidian")

    /** Pictures for the song list; .nomedia keeps them out of the gallery and the music scan. */
    fun coversFolder(): File = File(folder(), ".covers")

    fun hasStorageAccess(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Newest first. */
    fun savedSongs(): List<LocalSong> =
        folder().listFiles { file -> file.isFile && !file.name.startsWith(".") && file.extension.lowercase() in AUDIO_EXTENSIONS }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .map(::localSong)

    /** The saved file of a search result, if it was downloaded and is still there. */
    fun savedFile(context: Context, songId: String): File? {
        val name = prefs(context).getString(KEY_ID_PREFIX + songId, null) ?: return null
        return File(folder(), name).takeIf { it.exists() }
    }

    fun remember(context: Context, songId: String, file: File) {
        prefs(context).edit().putString(KEY_ID_PREFIX + songId, file.name).apply()
        MediaScannerConnection.scanFile(context, arrayOf(file.path), null, null)
    }

    fun delete(context: Context, song: LocalSong) {
        song.file.delete()
        song.cover.delete()
        val prefs = prefs(context)
        val keys = prefs.all.filterValues { it == song.file.name }.keys
        prefs.edit().apply { keys.forEach(::remove) }.apply()
        // Scanning a path that no longer exists removes it from the media library.
        MediaScannerConnection.scanFile(context, arrayOf(song.file.path), null, null)
    }

    /** "Artist - Title", which the head unit's player shows as the name of the song. */
    fun fileName(song: Song): String {
        val raw = if (song.artist.isBlank()) song.title else "${song.artist} - ${song.title}"
        return raw.replace(Regex("""[\\/:*?"<>|\x00-\x1F]"""), " ").replace(Regex("\\s+"), " ").trim().take(MAX_NAME_LENGTH)
    }

    fun formatDuration(seconds: Long): String =
        if (seconds <= 0) "" else String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)

    private fun localSong(file: File): LocalSong {
        val name = file.nameWithoutExtension
        val split = name.indexOf(" - ")
        return if (split > 0) {
            LocalSong(file, title = name.substring(split + 3), artist = name.substring(0, split))
        } else {
            LocalSong(file, title = name, artist = "")
        }
    }

    private fun videoId(url: String): String = Uri.parse(url).getQueryParameter("v") ?: url

    /** The smallest picture at least [preferredHeight] tall, else the largest there is. */
    private fun pick(images: List<Image>, preferredHeight: Int): String? {
        val known = images.filter { it.height > 0 }.sortedBy { it.height }
        return (known.firstOrNull { it.height >= preferredHeight } ?: known.lastOrNull() ?: images.firstOrNull())?.url
    }

    private const val THUMB_HEIGHT = 120
    private const val COVER_HEIGHT = 400

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
