package com.obsidian.carlauncher.apps

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.obsidian.carlauncher.AppLauncher
import com.obsidian.carlauncher.R
import java.text.Collator
import java.util.concurrent.Executors

/**
 * Full-screen grid of every launchable app, shown on top of the home screen.
 * [homeContent] is hidden while the drawer covers it, so each scroll frame paints one screen, not two.
 */
class AppDrawer(private val activity: Activity, private val root: View, private val homeContent: View) {

    private val launcherApps = activity.getSystemService(LauncherApps::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private val adapter = AppAdapter(::launch, ::showAppInfo)
    private val list: RecyclerView = root.findViewById(R.id.apps)

    private var loaded = false

    /** Every launchable app, sorted by label; empty until the first background load finishes. */
    var apps: List<AppEntry> = emptyList()
        private set

    /** Called on the main thread each time [apps] is (re)loaded. */
    var onAppsLoaded: (() -> Unit)? = null

    /** Reloads the list whenever an app is installed, removed or updated. */
    private val packageCallback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) = reload()
        override fun onPackageRemoved(packageName: String, user: UserHandle) = reload()
        override fun onPackageChanged(packageName: String, user: UserHandle) = reload()
        override fun onPackagesAvailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) = reload()
        override fun onPackagesUnavailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) = reload()
    }

    val isOpen: Boolean get() = root.visibility == View.VISIBLE

    private val iconSizePx = activity.resources.getDimensionPixelSize(R.dimen.app_icon_size)

    init {
        list.layoutManager = GridLayoutManager(activity, COLUMNS)
        list.adapter = adapter
        list.setHasFixedSize(true)
        list.setItemViewCacheSize(COLUMNS * 2)
        // Rows never animate; item animations only cost frames when the list is refreshed.
        list.itemAnimator = null
        root.findViewById<View>(R.id.drawer_close).setOnClickListener { close(animate = true) }
        launcherApps.registerCallback(packageCallback, mainHandler)
        // Load in the background right away so the first open is instant.
        reload()
    }

    fun open() {
        if (isOpen) return
        if (!loaded) reload()
        list.scrollToPosition(0)
        root.alpha = 0f
        root.translationY = OPEN_OFFSET_PX
        root.visibility = View.VISIBLE
        root.animate().alpha(1f).translationY(0f).setDuration(ANIMATION_MS).withLayer()
            .withEndAction { homeContent.visibility = View.INVISIBLE }
            .start()
    }

    fun close(animate: Boolean) {
        if (!isOpen) return
        root.animate().cancel()
        homeContent.visibility = View.VISIBLE
        if (!animate) {
            root.visibility = View.GONE
            return
        }
        root.animate().alpha(0f).translationY(OPEN_OFFSET_PX).setDuration(ANIMATION_MS).withLayer()
            .withEndAction { root.visibility = View.GONE }
            .start()
    }

    fun release() {
        launcherApps.unregisterCallback(packageCallback)
        loader.shutdownNow()
    }

    private fun reload() {
        loader.execute {
            val user = Process.myUserHandle()
            val collator = Collator.getInstance()
            val apps = launcherApps.getActivityList(null, user)
                .filter { it.componentName.packageName != activity.packageName }
                .map { AppEntry(it.label.toString(), it.componentName, user, prerender(it.getIcon(0))) }
                .sortedWith { a, b -> collator.compare(a.label, b.label) }
            mainHandler.post {
                loaded = true
                this.apps = apps
                adapter.submit(apps)
                onAppsLoaded?.invoke()
            }
        }
    }

    /**
     * Draws an icon once into a bitmap of its on-screen size. App icons are often large
     * adaptive drawables with several layers; scrolling then only blits small bitmaps.
     */
    private fun prerender(icon: Drawable): Drawable {
        val bitmap = Bitmap.createBitmap(iconSizePx, iconSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        icon.setBounds(0, 0, iconSizePx, iconSizePx)
        icon.draw(canvas)
        return BitmapDrawable(activity.resources, bitmap)
    }

    private fun launch(app: AppEntry) {
        try {
            launcherApps.startMainActivity(app.component, app.user, null, null)
        } catch (e: RuntimeException) {
            AppLauncher.startComponent(activity, app.component)
        }
        // Close after the app's window covers us, so the drawer never visibly flickers.
        mainHandler.postDelayed({ close(animate = false) }, CLOSE_AFTER_LAUNCH_MS)
    }

    private fun showAppInfo(app: AppEntry) {
        AppLauncher.start(
            activity,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.component.packageName, null)),
        )
    }

    private companion object {
        const val COLUMNS = 5
        const val ANIMATION_MS = 180L
        const val CLOSE_AFTER_LAUNCH_MS = 400L
        const val OPEN_OFFSET_PX = 48f
    }
}

class AppEntry(
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val icon: Drawable,
)

private class AppAdapter(
    private val onClick: (AppEntry) -> Unit,
    private val onLongClick: (AppEntry) -> Unit,
) : RecyclerView.Adapter<AppAdapter.Holder>() {

    private var apps: List<AppEntry> = emptyList()

    init {
        setHasStableIds(true)
    }

    fun submit(newApps: List<AppEntry>) {
        apps = newApps
        notifyDataSetChanged()
    }

    override fun getItemCount() = apps.size

    override fun getItemId(position: Int) = apps[position].component.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val app = apps[position]
        holder.icon.setImageDrawable(app.icon)
        holder.label.text = app.label
        holder.itemView.contentDescription = app.label
        holder.itemView.setOnClickListener { onClick(app) }
        holder.itemView.setOnLongClickListener {
            onLongClick(app)
            true
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.icon)
        val label: TextView = view.findViewById(R.id.label)
    }
}
