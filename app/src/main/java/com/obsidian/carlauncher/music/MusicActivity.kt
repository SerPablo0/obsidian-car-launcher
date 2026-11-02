package com.obsidian.carlauncher.music

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.obsidian.carlauncher.Accent
import com.obsidian.carlauncher.AppLauncher
import com.obsidian.carlauncher.R
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors

/** Search YouTube Music, save songs to the head unit, and play the saved ones. */
class MusicActivity : Activity() {

    private sealed class SearchState {
        object Idle : SearchState()
        object Searching : SearchState()
        object Done : SearchState()
        class Failed(val reason: String) : SearchState()
    }

    private lateinit var search: EditText
    private lateinit var tabSearch: TextView
    private lateinit var tabSaved: TextView
    private lateinit var statusBox: View
    private lateinit var status: TextView
    private lateinit var loading: View
    private lateinit var statusAction: Button
    private lateinit var playerBar: View
    private lateinit var playerTitle: TextView
    private lateinit var playerArtist: TextView
    private lateinit var playerPlay: ImageButton

    private val adapter = SongAdapter(onTap = ::onTap, onLongPress = ::onLongPress)
    private val searcher = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val refresh: () -> Unit = { render() }

    private var showingSaved = false
    private var results: List<Music.Song> = emptyList()
    private var searchState: SearchState = SearchState.Idle
    /** Answers to a search that a newer one replaced are ignored. */
    private var searchGeneration = 0
    private var saved: List<Music.LocalSong> = emptyList()
    private var finishedSeen = -1
    private var storageBlocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Accent.current(this).theme)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_music)

        search = findViewById(R.id.music_search)
        tabSearch = findViewById(R.id.music_tab_search)
        tabSaved = findViewById(R.id.music_tab_saved)
        statusBox = findViewById(R.id.music_status_box)
        status = findViewById(R.id.music_status)
        loading = findViewById(R.id.music_loading)
        statusAction = findViewById(R.id.music_status_action)
        playerBar = findViewById(R.id.music_player)
        playerTitle = findViewById(R.id.music_player_title)
        playerArtist = findViewById(R.id.music_player_artist)
        playerPlay = findViewById(R.id.music_player_play)

        findViewById<RecyclerView>(R.id.music_list).apply {
            layoutManager = LinearLayoutManager(this@MusicActivity)
            adapter = this@MusicActivity.adapter
        }
        findViewById<View>(R.id.music_close).setOnClickListener { finish() }
        findViewById<View>(R.id.music_search_go).setOnClickListener { runSearch() }
        search.setOnEditorActionListener { _, actionId, event ->
            // Some keyboards send Enter instead of their search key.
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                runSearch()
                true
            } else {
                false
            }
        }
        tabSearch.setOnClickListener { showSaved(false) }
        tabSaved.setOnClickListener { showSaved(true) }
        findViewById<View>(R.id.music_player_previous).setOnClickListener { MusicPlayer.previous() }
        playerPlay.setOnClickListener { MusicPlayer.toggle() }
        findViewById<View>(R.id.music_player_next).setOnClickListener { MusicPlayer.next() }
        statusAction.setOnClickListener { askStorage() }

        if (!Music.hasStorageAccess(this)) askStorage()
    }

    override fun onStart() {
        super.onStart()
        SongDownloads.addListener(refresh)
        MusicPlayer.addListener(refresh)
        reloadSaved()
        render()
    }

    override fun onStop() {
        SongDownloads.removeListener(refresh)
        MusicPlayer.removeListener(refresh)
        super.onStop()
    }

    override fun onDestroy() {
        searcher.shutdownNow()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQUEST_STORAGE) return
        val denied = grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED
        storageBlocked = denied && !shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        reloadSaved()
        render()
    }

    private fun askStorage() {
        if (storageBlocked) {
            AppLauncher.start(this, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } else {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_STORAGE)
        }
    }

    private fun runSearch() {
        val query = search.text.toString().trim()
        if (query.isEmpty()) return
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
        showingSaved = false
        val generation = ++searchGeneration
        searchState = SearchState.Searching
        render()
        searcher.execute {
            val outcome = runCatching { Music.search(query) }
            main.post {
                if (generation != searchGeneration || isDestroyed) return@post
                outcome.onSuccess {
                    results = it
                    searchState = SearchState.Done
                }
                outcome.onFailure { searchState = SearchState.Failed(describe(it)) }
                render()
            }
        }
    }

    private fun showSaved(saved: Boolean) {
        showingSaved = saved
        if (saved) reloadSaved()
        render()
    }

    private fun reloadSaved() {
        saved = if (Music.hasStorageAccess(this)) Music.savedSongs() else emptyList()
        finishedSeen = SongDownloads.finished
    }

    private fun render() {
        // A download that just finished adds a saved song.
        if (finishedSeen != SongDownloads.finished) reloadSaved()

        tabSearch.setTextColor(tabColor(!showingSaved))
        tabSaved.setTextColor(tabColor(showingSaved))
        tabSaved.text = getString(R.string.music_tab_saved, saved.size)

        adapter.rows = if (showingSaved) {
            saved.map { song ->
                SongRow(song.title, song.artist, "", song.cover.takeIf { it.exists() }?.path, song = null, file = song.file)
            }
        } else {
            results.map { song ->
                SongRow(
                    song.title, song.artist, Music.formatDuration(song.durationSec), song.thumbnailUrl,
                    song = song, file = Music.savedFile(this, song.id),
                )
            }
        }

        val hasStorage = Music.hasStorageAccess(this)
        val searching = !showingSaved && searchState is SearchState.Searching
        val message = when {
            !hasStorage -> getString(R.string.music_storage_needed)
            searching -> getString(R.string.music_searching)
            showingSaved && saved.isEmpty() -> getString(R.string.music_saved_empty)
            showingSaved -> null
            searchState is SearchState.Failed -> getString(R.string.music_search_failed, (searchState as SearchState.Failed).reason)
            searchState is SearchState.Done && results.isEmpty() -> getString(R.string.music_no_results)
            searchState is SearchState.Idle -> getString(R.string.music_start_hint)
            else -> null
        }
        statusBox.visibility = if (message == null) View.GONE else View.VISIBLE
        status.text = message
        loading.visibility = if (searching) View.VISIBLE else View.GONE
        statusAction.visibility = if (hasStorage) View.GONE else View.VISIBLE
        statusAction.setText(R.string.music_storage_allow)

        val current = MusicPlayer.current
        playerBar.visibility = if (current == null) View.GONE else View.VISIBLE
        if (current != null) {
            playerTitle.text = current.title
            playerArtist.text = current.artist
            val playing = MusicPlayer.isPlaying
            playerPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            playerPlay.contentDescription = getString(if (playing) R.string.cd_pause else R.string.cd_play)
        }
    }

    private fun onTap(row: SongRow) {
        val file = row.file
        when {
            file != null -> if (MusicPlayer.current?.file == file) {
                MusicPlayer.toggle()
            } else {
                val songs = Music.savedSongs()
                MusicPlayer.play(this, songs, songs.indexOfFirst { it.file == file }.coerceAtLeast(0))
            }
            !Music.hasStorageAccess(this) -> askStorage()
            row.song != null -> SongDownloads.enqueue(this, row.song)
        }
    }

    private fun onLongPress(row: SongRow) {
        val file = row.file ?: return
        val song = Music.savedSongs().firstOrNull { it.file == file } ?: return
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.music_delete_title, song.title))
            .setPositiveButton(R.string.music_delete) { _, _ ->
                MusicPlayer.forget(song)
                Music.delete(this, song)
                reloadSaved()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun tabColor(selected: Boolean) = if (selected) Accent.color(this) else getColor(R.color.text_secondary)

    private fun describe(e: Throwable): String = when (e) {
        is UnknownHostException, is SocketTimeoutException -> getString(R.string.music_offline)
        is ReCaptchaException -> getString(R.string.music_youtube_check)
        else -> e.message?.take(120) ?: e.javaClass.simpleName
    }

    private companion object {
        const val REQUEST_STORAGE = 51
    }
}
