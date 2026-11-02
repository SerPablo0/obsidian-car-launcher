package com.obsidian.carlauncher.system

import android.content.Context
import android.os.Handler
import android.os.Looper
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.util.concurrent.Executors

/**
 * Runs shell commands as root through the head unit's own adbd.
 *
 * The NWD firmware ships with adbd running as root and listening on TCP 5555
 * (ro.debuggable=1, service.adb.tcp.port=5555). We connect to it over loopback
 * with this app's own key; the first connection shows the system's
 * "Allow USB debugging?" dialog, and "Always allow" makes later connections silent.
 */
object RootShell {

    class Result(val output: String, val exitCode: Int) {
        val ok: Boolean get() = exitCode == 0
    }

    private const val HOST = "127.0.0.1"
    private const val PORT = 5555
    private const val CONNECT_TIMEOUT_MS = 3_000
    // Long enough for the user to answer the first-time authorization dialog.
    private const val SOCKET_TIMEOUT_MS = 60_000

    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Runs [commands] in one root shell on a background thread; [onDone] gets null if adbd was unreachable. */
    fun runAsync(context: Context, commands: List<String>, onDone: (Result?) -> Unit = {}) {
        val appContext = context.applicationContext
        worker.execute {
            val result = runBlocking(appContext, commands)
            mainHandler.post { onDone(result) }
        }
    }

    /** Must not be called on the main thread. */
    fun runBlocking(context: Context, commands: List<String>): Result? = try {
        Dadb.create(HOST, PORT, keyPair(context), CONNECT_TIMEOUT_MS, SOCKET_TIMEOUT_MS).use { adb ->
            val response = adb.shell(commands.joinToString(" ; "))
            Result(response.allOutput.trim(), response.exitCode)
        }
    } catch (e: Exception) {
        null
    }

    private fun keyPair(context: Context): AdbKeyPair {
        val privateKey = File(context.filesDir, "adbkey")
        val publicKey = File(context.filesDir, "adbkey.pub")
        if (!privateKey.exists() || !publicKey.exists()) AdbKeyPair.generate(privateKey, publicKey)
        return AdbKeyPair.read(privateKey, publicKey)
    }
}
