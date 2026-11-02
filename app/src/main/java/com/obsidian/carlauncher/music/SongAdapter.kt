package com.obsidian.carlauncher.music

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.obsidian.carlauncher.R
import java.io.File

/** One line of the Music screen: a search result, or a song saved on the head unit. */
class SongRow(
    val title: String,
    val artist: String,
    val duration: String,
    /** A web picture or a saved cover; null shows the music note. */
    val picture: String?,
    /** Set for search results. */
    val song: Music.Song?,
    /** The saved file, once there is one. */
    val file: File?,
)

class SongAdapter(
    private val onTap: (SongRow) -> Unit,
    private val onLongPress: (SongRow) -> Unit,
) : RecyclerView.Adapter<SongAdapter.Holder>() {

    var rows: List<SongRow> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val picture: ImageView = view.findViewById(R.id.song_picture)
        val placeholder: View = view.findViewById(R.id.song_placeholder)
        val title: TextView = view.findViewById(R.id.song_title)
        val subtitle: TextView = view.findViewById(R.id.song_subtitle)
        val action: ImageButton = view.findViewById(R.id.song_action)
        val progress: TextView = view.findViewById(R.id.song_progress)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
        return Holder(view).also { it.picture.clipToOutline = true }
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        val context = holder.itemView.context
        holder.title.text = row.title
        holder.subtitle.text = listOf(row.artist, row.duration).filter { it.isNotBlank() }.joinToString(" · ")
        holder.placeholder.visibility = if (row.picture == null) View.VISIBLE else View.GONE
        Thumbnails.load(holder.picture, row.picture)

        val playing = row.file != null && MusicPlayer.current?.file == row.file && MusicPlayer.isPlaying
        val state = row.song?.let { SongDownloads.state(it.id) }
        holder.progress.visibility = View.GONE
        holder.action.visibility = View.VISIBLE
        holder.action.isEnabled = true
        when {
            row.file != null -> {
                holder.action.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
                holder.action.contentDescription = context.getString(if (playing) R.string.cd_pause else R.string.cd_play)
            }
            state is SongDownloads.State.Saving -> {
                holder.action.visibility = View.INVISIBLE
                holder.progress.visibility = View.VISIBLE
                holder.progress.text = context.getString(R.string.music_percent, (state.fraction * 100).toInt())
            }
            state is SongDownloads.State.Waiting || state is SongDownloads.State.Preparing -> {
                holder.action.visibility = View.INVISIBLE
                holder.progress.visibility = View.VISIBLE
                holder.progress.setText(R.string.music_preparing)
            }
            else -> {
                holder.action.setImageResource(R.drawable.ic_download)
                holder.action.contentDescription = context.getString(R.string.cd_download)
            }
        }
        holder.itemView.setOnClickListener { onTap(row) }
        holder.action.setOnClickListener { onTap(row) }
        holder.itemView.setOnLongClickListener {
            onLongPress(row)
            true
        }
    }
}
