package com.obsidian.carlauncher.music

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Loads song pictures from the web or from saved covers, small and cached, off the main thread. */
object Thumbnails {

    private const val TARGET_PX = 160
    private const val CACHE_BYTES = 6 * 1024 * 1024

    private val workers = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** [source] is a URL or a file path; null clears the view. */
    fun load(view: ImageView, source: String?) {
        view.tag = source
        view.setImageDrawable(null)
        if (source == null) return
        cache.get(source)?.let {
            view.setImageBitmap(it)
            return
        }
        workers.execute {
            val bitmap = try {
                decode(read(source))
            } catch (e: Exception) {
                null
            }
            if (bitmap != null) cache.put(source, bitmap)
            main.post {
                // The row may have been reused for another song meanwhile.
                if (bitmap != null && view.tag == source) view.setImageBitmap(bitmap)
            }
        }
    }

    private fun read(source: String): ByteArray? {
        if (!source.startsWith("http")) return File(source).takeIf { it.exists() }?.readBytes()
        val connection = (URL(source).openConnection() as HttpURLConnection).apply {
            connectTimeout = NewPipeHttp.TIMEOUT_MS
            readTimeout = NewPipeHttp.TIMEOUT_MS
            setRequestProperty("User-Agent", NewPipeHttp.USER_AGENT)
        }
        return try {
            if (connection.responseCode == HttpURLConnection.HTTP_OK) connection.inputStream.use { it.readBytes() } else null
        } finally {
            connection.disconnect()
        }
    }

    private fun decode(bytes: ByteArray?): Bitmap? {
        if (bytes == null) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_PX && bounds.outHeight / (sample * 2) >= TARGET_PX) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
