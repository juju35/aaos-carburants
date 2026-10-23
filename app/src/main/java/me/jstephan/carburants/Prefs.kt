package me.jstephan.carburants

import android.content.Context
import me.jstephan.carburants.data.Fuel

enum class SortOrder { PRICE, DISTANCE }

/** Choices kept between drives. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("carburants", Context.MODE_PRIVATE)

    var fuel: Fuel
        get() = Fuel.fromKey(prefs.getString(KEY_FUEL, null))
        set(value) = prefs.edit().putString(KEY_FUEL, value.key).apply()

    var sort: SortOrder
        get() = SortOrder.entries.firstOrNull { it.name == prefs.getString(KEY_SORT, null) } ?: SortOrder.PRICE
        set(value) = prefs.edit().putString(KEY_SORT, value.name).apply()

    private companion object {
        const val KEY_FUEL = "fuel"
        const val KEY_SORT = "sort"
    }
}
