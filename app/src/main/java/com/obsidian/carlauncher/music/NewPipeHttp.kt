package com.obsidian.carlauncher.music

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.net.HttpURLConnection
import java.net.URL

/** NewPipeExtractor's HTTP client, on plain HttpURLConnection so the app needs no OkHttp. */
object NewPipeHttp : Downloader() {

    /** The desktop browser the NewPipe app presents itself as, for YouTube pages and for media files. */
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0"
    const val TIMEOUT_MS = 30_000

    override fun execute(request: Request): Response {
        val connection = URL(request.url()).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.requestMethod = request.httpMethod()
        connection.setRequestProperty("User-Agent", USER_AGENT)
        for ((name, values) in request.headers()) {
            // Android decompresses gzip itself only while the app leaves Accept-Encoding alone.
            if (name.equals("Accept-Encoding", ignoreCase = true)) continue
            values.forEach { connection.addRequestProperty(name, it) }
        }
        request.dataToSend()?.let { body ->
            connection.doOutput = true
            connection.outputStream.use { it.write(body) }
        }
        try {
            val code = connection.responseCode
            // YouTube answers 429 when it wants a captcha, as NewPipe's own client treats it.
            if (code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val headers = connection.headerFields.filterKeys { it != null }
            return Response(code, connection.responseMessage, headers, body, connection.url.toString())
        } finally {
            connection.disconnect()
        }
    }
}
