package com.obsidian.carlauncher.system

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Checks GitHub Releases for a newer build and installs it through the root shell. */
object Updater {

    class Release(val version: String, val apkUrl: String, val notes: String)

    private const val LATEST_RELEASE = "https://api.github.com/repos/SerPablo0/obsidian-car-launcher/releases/latest"
    private const val APK_NAME = "ObsidianLauncher.apk"
    private const val TIMEOUT_MS = 15_000

    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** [onDone] gets the latest release, or null with a short reason. */
    fun check(onDone: (Release?, String?) -> Unit) {
        worker.execute {
            val outcome = try {
                val json = JSONObject(httpGet(LATEST_RELEASE).bufferedReader().use { it.readText() })
                val assets = json.getJSONArray("assets")
                val apk = (0 until assets.length()).map(assets::getJSONObject).firstOrNull { it.getString("name") == APK_NAME }
                if (apk == null) {
                    null to "no APK in the latest release"
                } else {
                    Release(json.getString("tag_name").removePrefix("v"), apk.getString("browser_download_url"), json.optString("body")) to null
                }
            } catch (e: Exception) {
                null to (e.message ?: e.javaClass.simpleName)
            }
            mainHandler.post { onDone(outcome.first, outcome.second) }
        }
    }

    /** Compares dotted versions numerically: 0.10.0 is newer than 0.9.3. */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val l = local.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val diff = r.getOrElse(i) { 0 } - l.getOrElse(i) { 0 }
            if (diff != 0) return diff > 0
        }
        return false
    }

    /**
     * Downloads the APK into app storage and installs it with `pm install -r`.
     * On success the system replaces this app and restarts it, so [onDone] usually
     * only runs on failure.
     */
    fun install(context: Context, release: Release, onDone: (Boolean, String?) -> Unit) {
        val appContext = context.applicationContext
        worker.execute {
            val file = File(appContext.cacheDir, APK_NAME)
            val error = try {
                httpGet(release.apkUrl).use { input -> file.outputStream().use { input.copyTo(it) } }
                // adbd runs as root, so it can read the file straight from our cache directory.
                val result = RootShell.runBlocking(appContext, listOf("pm install -r '${file.absolutePath}'"))
                when {
                    result == null -> "system access is not available"
                    !result.ok || !result.output.contains("Success") -> result.output.ifBlank { "install failed" }
                    else -> null
                }
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            file.delete()
            mainHandler.post { onDone(error == null, error) }
        }
    }

    private fun httpGet(url: String) = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        instanceFollowRedirects = true
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "ObsidianLauncher")
        if (responseCode !in 200..299) throw IllegalStateException("HTTP $responseCode")
        inputStream
    }
}
