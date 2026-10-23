package me.jstephan.carburants.data

import androidx.annotation.StringRes
import me.jstephan.carburants.R
import java.time.Instant

private const val WHITE = 0xFFFFFFFF.toInt()
private const val INK = 0xFF1A1A1A.toInt()

/**
 * Fuels of the government feed. [key] prefixes the API fields ({key}_prix, {key}_maj...),
 * [apiName] is the name used in the raw "rupture" JSON. Colours and EU identifiers
 * (EN 16942) are the pump ones, as in the Lovelace card.
 */
enum class Fuel(
    val key: String,
    val apiName: String,
    val label: String,
    val code: String,
    val color: Int,
    val onColor: Int,
    @StringRes val description: Int,
) {
    GAZOLE("gazole", "Gazole", "Gazole", "B7", 0xFFE8A000.toInt(), INK, R.string.fuel_gazole),
    SP95("sp95", "SP95", "SP95", "E5", 0xFF00A651.toInt(), WHITE, R.string.fuel_sp95),
    SP98("sp98", "SP98", "SP98", "E5", 0xFF00703C.toInt(), WHITE, R.string.fuel_sp98),
    E10("e10", "E10", "SP95-E10", "E10", 0xFF009A3E.toInt(), WHITE, R.string.fuel_e10),
    E85("e85", "E85", "E85", "", 0xFF0057A8.toInt(), WHITE, R.string.fuel_e85),
    GPLC("gplc", "GPLc", "GPLc", "", 0xFFC62828.toInt(), WHITE, R.string.fuel_gplc);

    companion object {
        fun fromKey(key: String?): Fuel = entries.firstOrNull { it.key == key } ?: GAZOLE
    }
}

const val RUPTURE_DEFINITIVE = "definitive"

data class FuelPrice(
    val price: Double?,
    val updated: Instant?,
    val available: Boolean,
    /** "temporaire" or "definitive", null when in stock. */
    val rupture: String?,
    val ruptureSince: Instant?,
)

/** Opening hours of one day, [day] 1 = Monday … 7 = Sunday, ranges as "HH:mm". */
data class DayHours(val day: Int, val closed: Boolean, val ranges: List<Pair<String, String>>)

data class Station(
    val id: Long,
    val address: String,
    val postcode: String,
    val city: String,
    val latitude: Double,
    val longitude: Double,
    val highway: Boolean,
    val automate24: Boolean,
    val hours: List<DayHours>,
    val services: List<String>,
    val fuels: Map<Fuel, FuelPrice>,
    val distanceKm: Double? = null,
    /** Display name, see StationRepository.decorate. */
    val name: String = "",
    val brand: String? = null,
    val logoUrl: String? = null,
) {
    /** Sold here, possibly in a temporary shortage. */
    fun sells(fuel: Fuel): Boolean = fuels[fuel]?.let { it.rupture != RUPTURE_DEFINITIVE } ?: false

    /** Price when in stock. */
    fun price(fuel: Fuel): Double? = fuels[fuel]?.takeIf { it.available }?.price

    val prettyCity: String get() = formatCity(city)
}

/** The feed mixes "Rennes" and "RENNES". */
fun formatCity(city: String): String =
    if (city.isNotEmpty() && city == city.uppercase()) {
        city.lowercase().split(" ").joinToString(" ") { word ->
            word.split("-").joinToString("-") { it.replaceFirstChar(Char::titlecase) }
        }
    } else {
        city
    }

object Geo {
    /** Great-circle distance in km. */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)
        return 6371 * 2 * Math.asin(Math.sqrt(a))
    }
}
