package com.obsidian.carlauncher.apps

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.StringRes
import com.obsidian.carlauncher.R

/** A dark dialog listing apps; the first row is a "none / default" choice that returns null. */
object AppPicker {

    fun show(activity: Activity, @StringRes title: Int, resetLabel: String, apps: List<AppEntry>, onPick: (AppEntry?) -> Unit) {
        if (apps.isEmpty()) return
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setAdapter(PickerAdapter(activity, resetLabel, apps)) { _, position ->
                onPick(if (position == 0) null else apps[position - 1])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private class PickerAdapter(
        private val context: Context,
        private val resetLabel: String,
        private val apps: List<AppEntry>,
    ) : BaseAdapter() {

        override fun getCount() = apps.size + 1
        override fun getItem(position: Int): Any? = if (position == 0) null else apps[position - 1]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_picker, parent, false)
            val icon = view.findViewById<ImageView>(R.id.picker_icon)
            val label = view.findViewById<TextView>(R.id.picker_label)
            if (position == 0) {
                icon.setImageResource(R.drawable.ic_close)
                icon.imageTintList = ColorStateList.valueOf(context.getColor(R.color.text_secondary))
                label.text = resetLabel
            } else {
                val app = apps[position - 1]
                icon.imageTintList = null
                icon.setImageDrawable(app.icon)
                label.text = app.label
            }
            return view
        }
    }
}
