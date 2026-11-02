package com.obsidian.carlauncher.apps

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import com.obsidian.carlauncher.R
import com.obsidian.carlauncher.music.MusicActivity
import java.text.Collator

/** Loads every launchable app with its icon pre-rendered at on-screen size. Call off the main thread. */
object AppCatalog {

    fun load(context: Context): List<AppEntry> {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val iconSizePx = context.resources.getDimensionPixelSize(R.dimen.app_icon_size)
        val user = Process.myUserHandle()
        val collator = Collator.getInstance()
        return launcherApps.getActivityList(null, user)
            // The launcher itself is left out, but its Music screen is listed like an app.
            .filter { it.componentName.packageName != context.packageName || it.componentName.className == MusicActivity::class.java.name }
            .map { AppEntry(it.label.toString(), it.componentName, user, prerender(context, it.getIcon(0), iconSizePx)) }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    /**
     * Draws an icon once into a bitmap of its on-screen size. App icons are often large
     * adaptive drawables with several layers; scrolling then only blits small bitmaps.
     */
    private fun prerender(context: Context, icon: Drawable, sizePx: Int): Drawable {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, sizePx, sizePx)
        icon.draw(Canvas(bitmap))
        return BitmapDrawable(context.resources, bitmap)
    }
}
