package com.obsidian.carlauncher.settings

import android.app.Activity
import android.app.ActivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.obsidian.carlauncher.IdleClock
import com.obsidian.carlauncher.R
import com.obsidian.carlauncher.bar.BarControl
import com.obsidian.carlauncher.bar.BarState
import com.obsidian.carlauncher.bar.VolumePopup
import com.obsidian.carlauncher.apps.AppCatalog
import com.obsidian.carlauncher.apps.AppEntry
import com.obsidian.carlauncher.apps.AppPicker
import com.obsidian.carlauncher.media.NwdMedia
import com.obsidian.carlauncher.system.AutoStart
import com.obsidian.carlauncher.system.BootLogo
import java.util.concurrent.Executors
import com.obsidian.carlauncher.system.BootOptimizer
import com.obsidian.carlauncher.system.RootShell
import com.obsidian.carlauncher.system.UnusedApps
import com.obsidian.carlauncher.system.Updater

/** Speed tweaks and the list of NWD apps this car does not need. */
class SettingsActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var memory: TextView
    private lateinit var barEnable: Switch
    private lateinit var barHideNwd: Switch
    private lateinit var barStatus: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val appLoader = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        status = findViewById(R.id.status)
        memory = findViewById(R.id.memory)
        findViewById<View>(R.id.settings_close).setOnClickListener { finish() }

        val tweaks = findViewById<Switch>(R.id.speed_tweaks)
        tweaks.isChecked = BootOptimizer.isEnabled(this)
        tweaks.setOnCheckedChangeListener { _, checked -> BootOptimizer.setEnabled(this, checked) }

        val applyNow = findViewById<Button>(R.id.apply_now)
        bindCpuMode(findViewById(R.id.cpu_mode), onChanged = { applyNow.performClick() })
        applyNow.setOnClickListener {
            applyNow.isEnabled = false
            status.setText(R.string.settings_status_connecting)
            RootShell.runAsync(this, BootOptimizer.speedTweaks(this)) { result ->
                applyNow.isEnabled = true
                renderStatus(result)
                renderMemory()
                val message = when {
                    result == null -> null
                    result.ok -> getString(R.string.settings_applied)
                    else -> getString(R.string.settings_apply_failed, result.output)
                }
                message?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
            }
        }

        barEnable = findViewById(R.id.bar_enable)
        barHideNwd = findViewById(R.id.bar_hide_nwd)
        barStatus = findViewById(R.id.bar_status)
        bindBarSwitches()
        bindVolumeSwitch(findViewById(R.id.volume_popup))
        bindUpdates()
        bindAutoStart()
        findViewById<Switch>(R.id.idle_clock_enabled).apply {
            isChecked = IdleClock.isEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked -> IdleClock.setEnabled(this@SettingsActivity, checked) }
        }
        bindBootLogo(findViewById(R.id.boot_logo))

        bindUnusedApps(findViewById(R.id.unused_list))
    }

    override fun onResume() {
        super.onResume()
        val resumeLog = AutoStart.resumeLog(this)
        findViewById<TextView>(R.id.diagnostics_nwd_media).text = buildString {
            append(NwdMedia.diagnostics(this@SettingsActivity).ifEmpty { getString(R.string.settings_diagnostics_empty) })
            if (resumeLog.isNotEmpty()) append("\n\n").append(getString(R.string.settings_diagnostics_resume)).append('\n').append(resumeLog)
        }
        renderBar()
        renderMemory()
        // A cheap no-op command tells us whether system access is approved.
        RootShell.runAsync(this, listOf("true")) { renderStatus(it) }
    }

    private fun bindUnusedApps(list: LinearLayout) {
        val inflater = LayoutInflater.from(this)
        for (entry in UnusedApps.ENTRIES) {
            if (!UnusedApps.isInstalled(this, entry.packageName)) continue
            val row = inflater.inflate(R.layout.item_toggle, list, false)
            row.findViewById<TextView>(R.id.toggle_title).setText(entry.title)
            row.findViewById<TextView>(R.id.toggle_description).text = buildString {
                append(getString(entry.description))
                if (entry.risky) append('\n').append(getString(R.string.unused_risky_warning))
            }
            val toggle = row.findViewById<Switch>(R.id.toggle_switch)
            // "On" means the app is allowed to run, matching how Android shows it.
            toggle.isChecked = UnusedApps.isEnabled(this, entry.packageName)
            bindToggle(toggle, entry)
            row.setOnClickListener { if (toggle.isEnabled) toggle.toggle() }
            list.addView(row)
        }
        if (list.childCount == 0) list.visibility = View.GONE
    }

    private fun bindToggle(toggle: Switch, entry: UnusedApps.Entry) {
        toggle.setOnCheckedChangeListener { _, checked ->
            toggle.isEnabled = false
            UnusedApps.setEnabled(this, entry.packageName, checked) { ok ->
                toggle.isEnabled = true
                if (!ok) {
                    // Put the switch back without re-running the command.
                    toggle.setOnCheckedChangeListener(null)
                    toggle.isChecked = !checked
                    bindToggle(toggle, entry)
                    Toast.makeText(this, R.string.settings_change_failed, Toast.LENGTH_SHORT).show()
                }
                renderMemory()
            }
        }
    }

    private fun bindBarSwitches() {
        barEnable.setOnCheckedChangeListener { _, checked ->
            barEnable.isEnabled = false
            barStatus.setText(R.string.settings_bar_state_starting)
            BarControl.setServiceEnabled(this, checked) { ok ->
                barEnable.isEnabled = true
                if (!ok) Toast.makeText(this, R.string.settings_change_failed, Toast.LENGTH_SHORT).show()
                // The system binds the service a moment after the setting changes.
                handler.postDelayed(::renderBar, BAR_SETTLE_MS)
            }
        }
        barHideNwd.setOnCheckedChangeListener { _, checked ->
            barHideNwd.isEnabled = false
            BarControl.setNwdBarHidden(this, checked) { ok ->
                if (!ok) {
                    val message = if (checked) R.string.settings_bar_swap_failed else R.string.settings_change_failed
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
                renderBar()
            }
        }
    }

    private fun bindBootLogo(toggle: Switch) {
        toggle.isChecked = BootLogo.isApplied(this)
        toggle.setOnCheckedChangeListener { _, checked ->
            toggle.isEnabled = false
            BootLogo.setApplied(this, checked) { ok ->
                toggle.isEnabled = true
                if (!ok) {
                    setCheckedQuietly(toggle, !checked) { bindBootLogo(toggle) }
                    Toast.makeText(this, R.string.settings_change_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun bindAutoStart() {
        val resume = findViewById<Switch>(R.id.start_resume_music)
        resume.isChecked = AutoStart.isResumeMusicEnabled(this)
        resume.setOnCheckedChangeListener { _, checked -> AutoStart.setResumeMusicEnabled(this, checked) }

        val value = findViewById<TextView>(R.id.start_launch_value)
        val row = findViewById<View>(R.id.start_launch_row)
        var apps: List<AppEntry> = emptyList()

        fun renderLaunchApp() {
            val chosen = AutoStart.launchApp(this)
            value.text = apps.firstOrNull { it.component == chosen }?.label
                ?: chosen?.packageName
                ?: getString(R.string.settings_start_launch_none)
        }

        row.isEnabled = false
        renderLaunchApp()
        appLoader.execute {
            val loaded = AppCatalog.load(this)
            runOnUiThread {
                apps = loaded
                row.isEnabled = true
                renderLaunchApp()
            }
        }
        row.setOnClickListener {
            AppPicker.show(this, R.string.settings_start_pick_title, getString(R.string.settings_start_launch_none), apps) { picked ->
                AutoStart.setLaunchApp(this, picked?.component)
                renderLaunchApp()
            }
        }
    }

    private fun bindCpuMode(group: RadioGroup, onChanged: () -> Unit) {
        val buttons = mapOf(
            BootOptimizer.CpuMode.BALANCED to R.id.cpu_balanced,
            BootOptimizer.CpuMode.RESPONSIVE to R.id.cpu_responsive,
            BootOptimizer.CpuMode.MAXIMUM to R.id.cpu_maximum,
        )
        group.check(buttons.getValue(BootOptimizer.cpuMode(this)))
        group.setOnCheckedChangeListener { _, checkedId ->
            val mode = buttons.entries.firstOrNull { it.value == checkedId }?.key ?: return@setOnCheckedChangeListener
            BootOptimizer.setCpuMode(this, mode)
            // Applied right away as well as at every boot.
            onChanged()
        }
    }

    private fun bindUpdates() {
        val version = findViewById<TextView>(R.id.update_version)
        val status = findViewById<TextView>(R.id.update_status)
        val button = findViewById<Button>(R.id.update_button)
        val installed = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        version.text = getString(R.string.settings_update_version, installed)

        button.setOnClickListener {
            button.isEnabled = false
            status.setText(R.string.settings_update_checking)
            Updater.check { release, error ->
                button.isEnabled = true
                when {
                    release == null -> status.text = getString(R.string.settings_update_failed, error)
                    !Updater.isNewer(release.version, installed) -> status.setText(R.string.settings_update_latest)
                    else -> offerInstall(release, status, button)
                }
            }
        }
    }

    private fun offerInstall(release: Updater.Release, status: TextView, button: Button) {
        status.text = getString(R.string.settings_update_available, release.version, release.notes.trim())
        button.text = getString(R.string.settings_update_install, release.version)
        button.setOnClickListener {
            button.isEnabled = false
            status.setText(R.string.settings_update_installing)
            Updater.install(this, release) { ok, error ->
                // Reached only if the install did not replace and restart the app.
                button.isEnabled = true
                if (!ok) status.text = getString(R.string.settings_update_install_failed, error)
            }
        }
    }

    private fun bindVolumeSwitch(toggle: Switch) {
        toggle.isChecked = VolumePopup.isEnabled(this)
        toggle.setOnCheckedChangeListener { _, checked ->
            toggle.isEnabled = false
            VolumePopup.setEnabled(this, checked) { ok ->
                toggle.isEnabled = true
                if (!ok) {
                    setCheckedQuietly(toggle, !checked) { bindVolumeSwitch(toggle) }
                    Toast.makeText(this, R.string.settings_change_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun renderBar() {
        val serviceOn = BarControl.isServiceEnabled(this)
        val nwdHidden = BarControl.isNwdBarHidden(this)
        setCheckedQuietly(barEnable, serviceOn) { bindBarSwitches() }
        setCheckedQuietly(barHideNwd, nwdHidden) { bindBarSwitches() }
        val stripHeldByStock = BarControl.isStripHeldByStockBar(this)
        // Hiding is offered only when ours can take the reserved strip; un-hiding is always allowed.
        barHideNwd.isEnabled = nwdHidden || (serviceOn && (BarControl.isReservedBarShowing(this) || stripHeldByStock))
        val state = getString(
            when {
                !serviceOn -> R.string.settings_bar_state_off
                !BarState.attached && BarState.lastMode(this) == BarState.MODE_FAILED -> R.string.settings_bar_state_failed
                !BarState.attached -> R.string.settings_bar_state_starting
                BarState.lastMode(this) == BarState.MODE_RESERVED -> R.string.settings_bar_state_reserved
                stripHeldByStock -> R.string.settings_bar_state_ready_to_swap
                else -> R.string.settings_bar_state_overlay
            },
        )
        // Show the system's reasons when the reserved strip was refused for any other cause, so they can be reported.
        val errors = if (serviceOn && !BarControl.isReservedBarShowing(this) && !stripHeldByStock) BarState.lastErrors(this) else ""
        barStatus.text = if (errors.isEmpty()) state else "$state\n$errors"
    }

    private fun setCheckedQuietly(toggle: Switch, checked: Boolean, rebind: () -> Unit) {
        if (toggle.isChecked == checked) return
        toggle.setOnCheckedChangeListener(null)
        toggle.isChecked = checked
        rebind()
    }

    private fun renderStatus(result: RootShell.Result?) {
        // Any reply at all means adbd accepted our key; command failures are reported separately.
        status.setText(if (result != null) R.string.settings_status_ready else R.string.settings_status_unavailable)
    }

    private fun renderMemory() {
        val info = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        val memoryLine = getString(R.string.settings_memory, info.availMem / (1024 * 1024), info.totalMem / (1024 * 1024))
        val temperature = BootOptimizer.cpuTemperature()
        memory.text = if (temperature == null) memoryLine else "$memoryLine\n${getString(R.string.settings_temperature, temperature)}"
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        appLoader.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val BAR_SETTLE_MS = 1_500L
    }
}
