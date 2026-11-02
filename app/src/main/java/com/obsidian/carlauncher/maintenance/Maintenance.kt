package com.obsidian.carlauncher.maintenance

import android.content.Context
import androidx.annotation.StringRes
import com.obsidian.carlauncher.R
import com.obsidian.carlauncher.drive.TripComputer
import java.text.NumberFormat
import java.util.Calendar

/**
 * Service reminders by distance and by time. The odometer is the reading the user types
 * in from the car's own gauge, kept current with the distance measured by GPS; each item
 * remembers the reading and date it was last done.
 */
object Maintenance {

    enum class Item(@StringRes val label: Int, val defaultKm: Int, val defaultMonths: Int) {
        OIL(R.string.maintenance_oil, 5_000, 6),
        AIR_FILTER(R.string.maintenance_air_filter, 15_000, 12),
        TIRES(R.string.maintenance_tires, 10_000, 6),
        BRAKES(R.string.maintenance_brakes, 20_000, 12),
        COOLANT(R.string.maintenance_coolant, 40_000, 24),
    }

    class Status(
        val item: Item,
        val intervalKm: Int,
        val intervalMonths: Int,
        /** Null until the user marks it done once. */
        val nextKm: Int?,
        val remainingKm: Int?,
        val dueAt: Long?,
        val remainingDays: Int?,
    ) {
        val isOverdue: Boolean get() = (remainingKm ?: 1) < 0 || (remainingDays ?: 1) < 0
        val isDueSoon: Boolean get() = isOverdue || (remainingKm ?: Int.MAX_VALUE) <= SOON_KM || (remainingDays ?: Int.MAX_VALUE) <= SOON_DAYS
    }

    private const val SOON_KM = 500
    private const val SOON_DAYS = 14
    private const val PREFS = "maintenance"
    private const val KEY_ODOMETER = "odometer_km"
    /** The trip computer's lifetime distance (metres) at the moment the reading was typed. */
    private const val KEY_ODOMETER_GPS_BASE = "odometer_gps_base_m"
    private const val DAY_MS = 86_400_000L

    /** The typed reading plus the distance the trip computer measured since it was typed. */
    fun odometerKm(context: Context): Int? {
        val prefs = prefs(context)
        val typed = prefs.getInt(KEY_ODOMETER, -1).takeIf { it >= 0 } ?: return null
        val base = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_ODOMETER_GPS_BASE, 0L))
        val measuredKm = ((TripComputer.totalMeters(context) - base) / 1000).coerceAtLeast(0.0)
        return typed + measuredKm.toInt()
    }

    fun setOdometerKm(context: Context, km: Int) {
        prefs(context).edit()
            .putInt(KEY_ODOMETER, km)
            .putLong(KEY_ODOMETER_GPS_BASE, java.lang.Double.doubleToRawLongBits(TripComputer.totalMeters(context)))
            .apply()
    }

    fun setInterval(context: Context, item: Item, km: Int, months: Int) {
        prefs(context).edit().putInt("${item.name}_km", km).putInt("${item.name}_months", months).apply()
    }

    /** Records the item as done now, at the current odometer reading. */
    fun markDone(context: Context, item: Item) {
        val km = odometerKm(context) ?: return
        prefs(context).edit()
            .putInt("${item.name}_last_km", km)
            .putLong("${item.name}_last_at", System.currentTimeMillis())
            .apply()
    }

    fun statuses(context: Context): List<Status> = Item.values().map { status(context, it) }

    /** The most pressing reminder, for the home screen banner. */
    fun mostUrgent(context: Context): Status? =
        statuses(context).filter { it.isDueSoon }.minByOrNull { it.remainingKm ?: Int.MAX_VALUE }

    /** One line for a reminder, as on the home screen banner and the welcome screen. */
    fun reminderText(context: Context, status: Status): String {
        val name = context.getString(status.item.label)
        val remaining = status.remainingKm
        return when {
            status.isOverdue -> context.getString(R.string.maintenance_banner_overdue, name)
            remaining != null && remaining <= status.intervalKm -> context.getString(
                R.string.maintenance_banner_soon,
                name,
                context.getString(R.string.maintenance_km_left, NumberFormat.getIntegerInstance().format(remaining)),
            )
            else -> context.getString(R.string.maintenance_banner_soon, name, status.remainingDays?.let { "$it d" } ?: "")
        }
    }

    private fun status(context: Context, item: Item): Status {
        val prefs = prefs(context)
        val intervalKm = prefs.getInt("${item.name}_km", item.defaultKm)
        val intervalMonths = prefs.getInt("${item.name}_months", item.defaultMonths)
        val lastKm = prefs.getInt("${item.name}_last_km", -1).takeIf { it >= 0 }
        val lastAt = prefs.getLong("${item.name}_last_at", -1).takeIf { it >= 0 }
        val odometer = odometerKm(context)

        val nextKm = lastKm?.plus(intervalKm)
        val remainingKm = if (nextKm != null && odometer != null) nextKm - odometer else null
        val dueAt = lastAt?.let {
            Calendar.getInstance().apply { timeInMillis = it; add(Calendar.MONTH, intervalMonths) }.timeInMillis
        }
        val remainingDays = dueAt?.let { ((it - System.currentTimeMillis()) / DAY_MS).toInt() }
        return Status(item, intervalKm, intervalMonths, nextKm, remainingKm, dueAt, remainingDays)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
