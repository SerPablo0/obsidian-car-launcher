package com.obsidian.carlauncher

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import com.obsidian.carlauncher.maintenance.Maintenance
import com.obsidian.carlauncher.apps.AppDrawer
import com.obsidian.carlauncher.bar.BarControl
import com.obsidian.carlauncher.drive.DriveActivity
import com.obsidian.carlauncher.drive.SpeedCard
import com.obsidian.carlauncher.drive.TripComputer
import com.obsidian.carlauncher.media.NowPlayingController
import com.obsidian.carlauncher.settings.SettingsActivity
import com.obsidian.carlauncher.system.AutoStart
import com.obsidian.carlauncher.system.BootOptimizer

class HomeActivity : Activity() {

    private lateinit var nowPlaying: NowPlayingController
    private lateinit var drawer: AppDrawer
    private lateinit var idleClock: IdleClock
    private lateinit var speedCard: SpeedCard
    private lateinit var appliedAccent: Accent
    /** Between onResume and onPause; driving mode only opens by itself over the home screen. */
    private var inFront = false

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedAccent = Accent.current(this)
        setTheme(appliedAccent.theme)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        drawer = AppDrawer(this, findViewById(R.id.drawer), homeContent = findViewById(R.id.home))
        nowPlaying = NowPlayingController(this, findViewById(R.id.now_playing))
        val shortcuts = Shortcuts(this, findViewById(R.id.shortcuts), apps = { drawer.apps }, onAllApps = drawer::open)
        shortcuts.bind()
        // Custom shortcuts need the app list; refresh once it arrives and after installs or removals.
        drawer.onAppsLoaded = shortcuts::bind
        idleClock = IdleClock(findViewById(R.id.idle_clock), nowPlaying = nowPlaying::playingLine)

        BootOptimizer.runIfNeeded(this)
        AutoStart.runIfNeeded(this)
        TripComputer.start(this)
        speedCard = SpeedCard(this, findViewById(R.id.speed_block), onDriving = ::autoOpenDriveMode)
        TripComputer.requestPermissionOnce(this)

        val clockBlock = findViewById<View>(R.id.clock_block)
        clockBlock.setOnClickListener { openDriveMode() }
        clockBlock.setOnLongClickListener { anchor ->
            showSystemMenu(anchor)
            true
        }
    }

    override fun onStart() {
        super.onStart()
        nowPlaying.start()
        speedCard.start()
        // Gives the bar service time to bind after boot before judging that it is missing.
        root.postDelayed(barGuard, BAR_GUARD_DELAY_MS)
    }

    override fun onStop() {
        root.removeCallbacks(barGuard)
        nowPlaying.stop()
        speedCard.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // A new accent chosen in settings: the theme is applied in onCreate, so rebuild.
        if (Accent.current(this) != appliedAccent) {
            recreate()
            return
        }
        inFront = true
        idleClock.resume()
        renderMaintenanceBanner()
        // Location access may have been changed in Android's settings meanwhile.
        TripComputer.refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == TripComputer.REQUEST_LOCATION) TripComputer.refresh()
    }

    private fun openDriveMode() {
        AppLauncher.start(this, Intent(this, DriveActivity::class.java))
    }

    private fun autoOpenDriveMode() {
        if (!inFront) return
        inFront = false
        TripComputer.onDriveModeShown()
        openDriveMode()
    }

    private fun renderMaintenanceBanner() {
        val banner = findViewById<TextView>(R.id.maintenance_banner)
        val urgent = Maintenance.mostUrgent(this)
        if (urgent == null) {
            banner.visibility = View.GONE
            return
        }
        banner.text = Maintenance.reminderText(this, urgent)
        banner.visibility = View.VISIBLE
        banner.setOnClickListener {
            AppLauncher.start(this, Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onPause() {
        inFront = false
        idleClock.pause()
        super.onPause()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        idleClock.onUserInteraction()
    }

    override fun onDestroy() {
        drawer.release()
        super.onDestroy()
    }

    private val root: View get() = findViewById(R.id.root)

    private val barGuard = Runnable { BarControl.guard(this) }

    // Home is delivered here while we are already running: return to the clean home screen.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN) {
            drawer.close(animate = hasWindowFocus())
            // The Home button counts as activity: drop the clock screen and start the idle timer again.
            idleClock.pause()
            idleClock.resume()
        }
    }

    // A launcher never finishes on Back; Back only closes the app drawer.
    override fun onBackPressed() {
        if (drawer.isOpen) drawer.close(animate = true)
    }

    private fun showSystemMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(R.string.menu_drive_mode).setOnMenuItemClickListener {
                openDriveMode()
                true
            }
            menu.add(R.string.menu_obsidian_settings).setOnMenuItemClickListener {
                AppLauncher.start(this@HomeActivity, Intent(this@HomeActivity, SettingsActivity::class.java))
            }
            menu.add(R.string.menu_home_app).setOnMenuItemClickListener {
                AppLauncher.start(this@HomeActivity, Intent(Settings.ACTION_HOME_SETTINGS))
            }
            menu.add(R.string.menu_android_settings).setOnMenuItemClickListener {
                AppLauncher.start(this@HomeActivity, Intent(Settings.ACTION_SETTINGS))
            }
            menu.add(R.string.menu_car_settings).setOnMenuItemClickListener {
                AppLauncher.startPackage(this@HomeActivity, CAR_SETTINGS_PACKAGE)
            }
        }.show()
    }

    private companion object {
        const val CAR_SETTINGS_PACKAGE = "com.android.car.setting"
        const val BAR_GUARD_DELAY_MS = 10_000L
    }
}

/** Starts activities from the home screen without ever crashing it. */
object AppLauncher {

    fun start(activity: Activity, intent: Intent): Boolean = try {
        activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: RuntimeException) {
        false
    }

    fun startPackage(activity: Activity, packageName: String): Boolean {
        val intent = activity.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return start(activity, intent)
    }

    fun startComponent(activity: Activity, component: ComponentName): Boolean =
        start(activity, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component))
}
