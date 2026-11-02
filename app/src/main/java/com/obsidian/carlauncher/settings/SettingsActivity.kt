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
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.obsidian.carlauncher.R
import com.obsidian.carlauncher.bar.BarControl
import com.obsidian.carlauncher.bar.BarState
import com.obsidian.carlauncher.bar.VolumePopup
import com.obsidian.carlauncher.system.BootOptimizer
import com.obsidian.carlauncher.system.RootShell
import com.obsidian.carlauncher.system.UnusedApps

/** Speed tweaks and the list of NWD apps this car does not need. */
class SettingsActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var memory: TextView
    private lateinit var barEnable: Switch
    private lateinit var barHideNwd: Switch
    private lateinit var barStatus: TextView
    private val handler = Handler(Looper.getMainLooper())

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
        applyNow.setOnClickListener {
            applyNow.isEnabled = false
            status.setText(R.string.settings_status_connecting)
            RootShell.runAsync(this, BootOptimizer.SPEED_TWEAKS) { result ->
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

        bindUnusedApps(findViewById(R.id.unused_list))
    }

    override fun onResume() {
        super.onResume()
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
        memory.text = getString(R.string.settings_memory, info.availMem / (1024 * 1024), info.totalMem / (1024 * 1024))
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        const val BAR_SETTLE_MS = 1_500L
    }
}
