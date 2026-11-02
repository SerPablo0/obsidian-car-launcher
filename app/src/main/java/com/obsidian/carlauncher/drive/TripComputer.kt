package com.obsidian.carlauncher.drive

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import com.obsidian.carlauncher.AppLauncher
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Speedometer and trip computer, fed by the head unit's own GPS.
 *
 * Only running totals are kept (distance, time, top speed); positions are never saved or
 * sent anywhere. GPS runs only while the screen is on: switching the car off puts the head
 * unit to sleep, and a receiver left running then could drain the car battery.
 *
 * A drive ends when the screen stays off for 90 s or more, the same rule that recognises a
 * car start in [com.obsidian.carlauncher.system.AutoStart], so the short blackout while the
 * engine is cranked does not split a drive. Main thread only.
 */
object TripComputer {

    enum class GpsState { DISABLED, NO_PERMISSION, OFF, WAITING, OK }

    class Trip(
        /** Wall-clock time the car first moved; 0 while it has not moved yet. */
        val startedAt: Long,
        val distanceM: Double,
        /** From the first movement on, stops at lights included, as in a car's own trip computer. */
        val durationMs: Long,
        val maxKmh: Int,
    ) {
        val hasMoved: Boolean get() = startedAt > 0

        /** Null until the trip is long enough for an average to mean something. */
        val averageKmh: Int?
            // metres per millisecond × 3600 = km/h
            get() = if (durationMs >= MIN_AVERAGE_MS) (distanceM * 3600 / durationMs).roundToInt() else null
    }

    const val REQUEST_LOCATION = 41
    /** Driving mode opens by itself above this speed. */
    const val AUTO_DRIVE_KMH = 20

    private const val PREFS = "trip_computer"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LIMIT = "speed_limit"
    private const val KEY_BEEP = "beep"
    private const val KEY_AUTO_DRIVE = "auto_drive"
    private const val KEY_ASKED = "asked_permission"
    private const val KEY_DRIVE_ID = "drive_id"
    private const val KEY_DRIVE_STARTED = "drive_started"
    private const val KEY_DRIVE_DISTANCE = "drive_distance"
    private const val KEY_DRIVE_DURATION = "drive_duration"
    private const val KEY_DRIVE_MAX = "drive_max"
    private const val KEY_LAST_ACTIVE = "last_active"
    private const val KEY_PREV_STARTED = "prev_started"
    private const val KEY_PREV_DISTANCE = "prev_distance"
    private const val KEY_PREV_DURATION = "prev_duration"
    private const val KEY_PREV_MAX = "prev_max"
    private const val KEY_TOTAL = "total_distance"

    private const val GPS_INTERVAL_MS = 1_000L
    private const val TICK_MS = 1_000L
    private const val FIX_TIMEOUT_MS = 5_000L
    /** A longer step between ticks means the system was asleep; that is not driving time. */
    private const val MAX_TICK_STEP_MS = 5_000L
    /** Screen off at least this long means the car was switched off. */
    private const val NEW_DRIVE_GAP_MS = 90_000L
    private const val SAVE_EVERY_MS = 30_000L
    private const val MS_TO_KMH = 3.6f
    /** GPS drifts by a few km/h while parked; below this the car is standing still. */
    private const val STANDSTILL_KMH = 4f
    private const val MAX_ACCURACY_M = 25f
    /** Faster than this is a jump in the GPS solution, not driving. */
    private const val MAX_PLAUSIBLE_KMH = 250f
    /** A gap in fixes (a tunnel) is bridged in a straight line, up to this long. */
    private const val MAX_GAP_S = 180.0
    /** The trip clock starts once the car has really left, not at the first GPS wobble. */
    private const val START_DISTANCE_M = 50.0
    /** Shorter drives (moving the car in the driveway) do not replace the last trip. */
    private const val MIN_TRIP_M = 200.0
    private const val MIN_AVERAGE_MS = 60_000L
    private const val LIMIT_HYSTERESIS_KMH = 5
    private const val BEEP_GAP_MS = 30_000L
    private const val BEEP_RELEASE_MS = 1_000L

    /** Current speed rounded to km/h, or null without a recent fix. */
    var speedKmh: Int? = null
        private set
    var gpsState = GpsState.DISABLED
        private set
    /** The last finished drive, or null before the first one. */
    var previous: Trip? = null
        private set
    val current: Trip get() = Trip(driveStartedAt, driveDistanceM, driveDurationMs, driveMaxKmh)
    val isOverLimit: Boolean get() = speedLimit > 0 && (speedKmh ?: 0) > speedLimit

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()
    private var appContext: Context? = null
    private var loaded = false

    private var enabled = true
    private var speedLimit = 0
    private var beepEnabled = true
    private var autoDrive = true

    private var driveId = 0L
    private var driveStartedAt = 0L
    private var driveDistanceM = 0.0
    private var driveDurationMs = 0L
    private var driveMaxKmh = 0
    private var lastActiveAt = 0L
    /** Everything ever measured; the maintenance odometer adds it to the typed reading. */
    private var totalM = 0.0

    private var screenOn = true
    private var listening = false
    private var lastFix: Location? = null
    private var lastFixAt = 0L
    private var lastRawKmh = 0f
    private var lastTickAt = 0L
    private var lastSaveAt = 0L
    private var warned = false
    private var lastBeepAt = -BEEP_GAP_MS
    /** Drive in which driving mode was already shown; it opens by itself once per drive. */
    private var driveModeShownIn = -1L

    /** Safe to call often: from the bar service at boot and from the home screen. */
    fun start(context: Context) {
        val app = context.applicationContext
        load(app)
        if (appContext == null) {
            appContext = app
            screenOn = app.getSystemService(PowerManager::class.java).isInteractive
            app.registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            })
        }
        refresh()
    }

    /** Re-checks the setting, the permission and the screen; call after any of them changed. */
    fun refresh() {
        val context = appContext ?: return
        when {
            !enabled -> {
                stopGps()
                gpsState = GpsState.DISABLED
            }
            !hasPermission(context) -> {
                stopGps()
                gpsState = GpsState.NO_PERMISSION
            }
            !screenOn -> stopGps()
            else -> startGps(context)
        }
        notifyListeners()
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Asks for location access; once Android stops showing the question, opens the app's settings page instead. */
    fun requestPermission(activity: Activity) {
        val prefs = prefs(activity)
        val androidStoppedAsking = prefs.getBoolean(KEY_ASKED, false) &&
            !activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
        if (androidStoppedAsking) {
            AppLauncher.start(
                activity,
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)),
            )
        } else {
            prefs.edit().putBoolean(KEY_ASKED, true).apply()
            activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_LOCATION)
        }
    }

    /** The first time the home screen starts with the speedometer on, asks without waiting for a tap. */
    fun requestPermissionOnce(activity: Activity) {
        load(activity)
        if (enabled && !hasPermission(activity) && !prefs(activity).getBoolean(KEY_ASKED, false)) requestPermission(activity)
    }

    fun shouldAutoOpenDriveMode(): Boolean =
        autoDrive && listening && (speedKmh ?: 0) >= AUTO_DRIVE_KMH && driveModeShownIn != driveId

    /** Driving mode was opened, by hand or by itself: do not open it by itself again this drive. */
    fun onDriveModeShown() {
        driveModeShownIn = driveId
    }

    fun totalMeters(context: Context): Double {
        load(context)
        return totalM
    }

    fun isEnabled(context: Context): Boolean {
        load(context)
        return enabled
    }

    fun setEnabled(context: Context, value: Boolean) {
        load(context)
        enabled = value
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
        start(context)
    }

    /** Warning speed in km/h, 0 when off. */
    fun speedLimit(context: Context): Int {
        load(context)
        return speedLimit
    }

    fun setSpeedLimit(context: Context, kmh: Int) {
        load(context)
        speedLimit = kmh
        warned = false
        prefs(context).edit().putInt(KEY_LIMIT, kmh).apply()
        notifyListeners()
    }

    fun isBeepEnabled(context: Context): Boolean {
        load(context)
        return beepEnabled
    }

    fun setBeepEnabled(context: Context, value: Boolean) {
        load(context)
        beepEnabled = value
        prefs(context).edit().putBoolean(KEY_BEEP, value).apply()
    }

    fun isAutoDriveEnabled(context: Context): Boolean {
        load(context)
        return autoDrive
    }

    fun setAutoDriveEnabled(context: Context, value: Boolean) {
        load(context)
        autoDrive = value
        prefs(context).edit().putBoolean(KEY_AUTO_DRIVE, value).apply()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn = intent.action == Intent.ACTION_SCREEN_ON
            refresh()
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onFix(location)

        override fun onProviderEnabled(provider: String) {
            gpsState = GpsState.WAITING
            notifyListeners()
        }

        override fun onProviderDisabled(provider: String) {
            gpsState = GpsState.OFF
            speedKmh = null
            notifyListeners()
        }

        @Deprecated("Still called on Android 10")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private val ticker = object : Runnable {
        override fun run() {
            val context = appContext ?: return
            val now = SystemClock.elapsedRealtime()
            val step = (now - lastTickAt).coerceIn(0L, MAX_TICK_STEP_MS)
            lastTickAt = now
            if (driveStartedAt > 0) driveDurationMs += step
            lastActiveAt = System.currentTimeMillis()
            if (now - lastFixAt > FIX_TIMEOUT_MS) {
                speedKmh = null
                gpsState = if (gpsEnabled(context)) GpsState.WAITING else GpsState.OFF
            }
            if (now - lastSaveAt >= SAVE_EVERY_MS) save()
            notifyListeners()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun startGps(context: Context) {
        if (listening) return
        if (!hasPermission(context)) return
        try {
            locationManager(context).requestLocationUpdates(
                LocationManager.GPS_PROVIDER, GPS_INTERVAL_MS, 0f, locationListener, Looper.getMainLooper(),
            )
        } catch (e: SecurityException) {
            gpsState = GpsState.NO_PERMISSION
            return
        } catch (e: IllegalArgumentException) {
            // This device has no GPS provider.
            gpsState = GpsState.OFF
            return
        }
        listening = true
        beginCounting()
        lastFixAt = 0L
        gpsState = if (gpsEnabled(context)) GpsState.WAITING else GpsState.OFF
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, TICK_MS)
    }

    private fun stopGps() {
        if (!listening) return
        listening = false
        handler.removeCallbacks(ticker)
        appContext?.let { locationManager(it).removeUpdates(locationListener) }
        lastFix = null
        lastRawKmh = 0f
        speedKmh = null
        warned = false
        save()
    }

    /** Continues the current drive, or starts a new one when the car was off long enough. */
    private fun beginCounting() {
        val now = System.currentTimeMillis()
        if (driveId == 0L || now - lastActiveAt >= NEW_DRIVE_GAP_MS) {
            if (driveDistanceM >= MIN_TRIP_M) previous = current
            driveId = now
            driveStartedAt = 0L
            driveDistanceM = 0.0
            driveDurationMs = 0L
            driveMaxKmh = 0
        }
        lastActiveAt = now
        lastTickAt = SystemClock.elapsedRealtime()
        save()
    }

    private fun onFix(fix: Location) {
        lastFixAt = SystemClock.elapsedRealtime()
        gpsState = GpsState.OK
        val rawKmh = if (fix.hasSpeed()) fix.speed * MS_TO_KMH else 0f
        val kmh = if (rawKmh < STANDSTILL_KMH) 0 else rawKmh.roundToInt()
        speedKmh = kmh
        if ((!fix.hasAccuracy() || fix.accuracy <= MAX_ACCURACY_M) && rawKmh <= MAX_PLAUSIBLE_KMH) {
            addDistance(fix, rawKmh)
            // One odd reading does not become the top speed: it has to hold for two fixes in a row.
            val steady = min(rawKmh, lastRawKmh).roundToInt()
            if (driveStartedAt > 0 && steady > driveMaxKmh) driveMaxKmh = steady
            lastRawKmh = rawKmh
        }
        checkLimit(kmh)
        notifyListeners()
    }

    private fun addDistance(fix: Location, rawKmh: Float) {
        val before = lastFix
        lastFix = fix
        if (before == null) return
        val seconds = if (before.elapsedRealtimeNanos > 0 && fix.elapsedRealtimeNanos > 0) {
            (fix.elapsedRealtimeNanos - before.elapsedRealtimeNanos) / 1e9
        } else {
            (fix.time - before.time) / 1000.0
        }
        if (seconds <= 0 || seconds > MAX_GAP_S) return
        val meters = before.distanceTo(fix).toDouble()
        val impliedKmh = meters / seconds * MS_TO_KMH
        if (impliedKmh > MAX_PLAUSIBLE_KMH) return
        val moving = if (fix.hasSpeed()) rawKmh >= STANDSTILL_KMH else impliedKmh >= STANDSTILL_KMH * 2
        if (!moving) return
        driveDistanceM += meters
        totalM += meters
        if (driveStartedAt == 0L && driveDistanceM >= START_DISTANCE_M) driveStartedAt = System.currentTimeMillis()
    }

    private fun checkLimit(kmh: Int) {
        val limit = speedLimit
        if (limit <= 0) {
            warned = false
            return
        }
        if (kmh > limit) {
            if (warned) return
            warned = true
            val now = SystemClock.elapsedRealtime()
            if (beepEnabled && now - lastBeepAt >= BEEP_GAP_MS) {
                lastBeepAt = now
                beep()
            }
        } else if (kmh <= limit - LIMIT_HYSTERESIS_KMH) {
            warned = false
        }
    }

    private fun beep() {
        val tone = try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, ToneGenerator.MAX_VOLUME)
        } catch (e: RuntimeException) {
            return
        }
        tone.startTone(ToneGenerator.TONE_PROP_BEEP2)
        handler.postDelayed({ tone.release() }, BEEP_RELEASE_MS)
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it() }
    }

    private fun gpsEnabled(context: Context) = try {
        locationManager(context).isProviderEnabled(LocationManager.GPS_PROVIDER)
    } catch (e: RuntimeException) {
        false
    }

    private fun locationManager(context: Context) = context.getSystemService(LocationManager::class.java)

    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = prefs(context)
        enabled = prefs.getBoolean(KEY_ENABLED, true)
        speedLimit = prefs.getInt(KEY_LIMIT, 0)
        beepEnabled = prefs.getBoolean(KEY_BEEP, true)
        autoDrive = prefs.getBoolean(KEY_AUTO_DRIVE, true)
        driveId = prefs.getLong(KEY_DRIVE_ID, 0L)
        driveStartedAt = prefs.getLong(KEY_DRIVE_STARTED, 0L)
        driveDistanceM = prefs.getDouble(KEY_DRIVE_DISTANCE)
        driveDurationMs = prefs.getLong(KEY_DRIVE_DURATION, 0L)
        driveMaxKmh = prefs.getInt(KEY_DRIVE_MAX, 0)
        lastActiveAt = prefs.getLong(KEY_LAST_ACTIVE, 0L)
        totalM = prefs.getDouble(KEY_TOTAL)
        val previousStart = prefs.getLong(KEY_PREV_STARTED, 0L)
        previous = if (previousStart > 0) {
            Trip(previousStart, prefs.getDouble(KEY_PREV_DISTANCE), prefs.getLong(KEY_PREV_DURATION, 0L), prefs.getInt(KEY_PREV_MAX, 0))
        } else {
            null
        }
    }

    private fun save() {
        val context = appContext ?: return
        lastSaveAt = SystemClock.elapsedRealtime()
        val last = previous
        prefs(context).edit()
            .putLong(KEY_DRIVE_ID, driveId)
            .putLong(KEY_DRIVE_STARTED, driveStartedAt)
            .putDouble(KEY_DRIVE_DISTANCE, driveDistanceM)
            .putLong(KEY_DRIVE_DURATION, driveDurationMs)
            .putInt(KEY_DRIVE_MAX, driveMaxKmh)
            .putLong(KEY_LAST_ACTIVE, lastActiveAt)
            .putDouble(KEY_TOTAL, totalM)
            .putLong(KEY_PREV_STARTED, last?.startedAt ?: 0L)
            .putDouble(KEY_PREV_DISTANCE, last?.distanceM ?: 0.0)
            .putLong(KEY_PREV_DURATION, last?.durationMs ?: 0L)
            .putInt(KEY_PREV_MAX, last?.maxKmh ?: 0)
            .apply()
    }

    private fun SharedPreferences.getDouble(key: String) = java.lang.Double.longBitsToDouble(getLong(key, 0L))

    private fun SharedPreferences.Editor.putDouble(key: String, value: Double) =
        putLong(key, java.lang.Double.doubleToRawLongBits(value))

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
