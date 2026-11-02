package com.obsidian.carlauncher.settings

import android.app.Activity
import android.app.ActivityManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.obsidian.carlauncher.R
import com.obsidian.carlauncher.system.BootOptimizer
import com.obsidian.carlauncher.system.RootShell
import com.obsidian.carlauncher.system.UnusedApps

/** Speed tweaks and the list of NWD apps this car does not need. */
class SettingsActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var memory: TextView

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

        bindUnusedApps(findViewById(R.id.unused_list))
    }

    override fun onResume() {
        super.onResume()
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

    private fun renderStatus(result: RootShell.Result?) {
        // Any reply at all means adbd accepted our key; command failures are reported separately.
        status.setText(if (result != null) R.string.settings_status_ready else R.string.settings_status_unavailable)
    }

    private fun renderMemory() {
        val info = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        memory.text = getString(R.string.settings_memory, info.availMem / (1024 * 1024), info.totalMem / (1024 * 1024))
    }
}
