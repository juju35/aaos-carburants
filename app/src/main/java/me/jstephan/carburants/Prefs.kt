package me.jstephan.carburants

import android.content.Context
import me.jstephan.carburants.data.Fuel

enum class SortOrder { PRICE, DISTANCE }

/** Choices kept between drives. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("carburants", Context.MODE_PRIVATE)

    /** Fuels shown (those the car takes), in the enum order, never empty. */
    var fuels: List<Fuel>
        get() {
            val stored = prefs.getString(KEY_FUELS, null)?.split(",")
                ?: listOfNotNull(prefs.getString(KEY_FUEL_V1, null)) // 1.0.0 kept a single fuel.
            return Fuel.entries.filter { it.key in stored }.ifEmpty { listOf(Fuel.GAZOLE) }
        }
        set(value) = prefs.edit().putString(KEY_FUELS, value.joinToString(",") { it.key }).remove(KEY_FUEL_V1).apply()

    var sort: SortOrder
        get() = SortOrder.entries.firstOrNull { it.name == prefs.getString(KEY_SORT, null) } ?: SortOrder.PRICE
        set(value) = prefs.edit().putString(KEY_SORT, value.name).apply()

    private companion object {
        const val KEY_FUELS = "fuels"
        const val KEY_FUEL_V1 = "fuel"
        const val KEY_SORT = "sort"
    }
}
