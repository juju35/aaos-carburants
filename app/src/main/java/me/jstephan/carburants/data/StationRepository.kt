package me.jstephan.carburants.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Stations around a point with their name, brand and logo. One per process. */
class StationRepository private constructor(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stationNames = StationNames(context)
    private val brands = BrandCache(context)
    val logos = LogoLoader(context)

    // Started right away: the first download (~1 MB) must not hold up the first list.
    private var names: Deferred<Map<String, StationNames.Entry>> = scope.async { stationNames.load() }

    /** Nearest stations selling [fuel], within [RADIUS_KM], widened to [WIDE_RADIUS_KM] when there are few. */
    suspend fun nearby(latitude: Double, longitude: Double, fuel: Fuel): Result {
        var radius = RADIUS_KM
        var list = withContext(Dispatchers.IO) { CarburantsApi.nearby(latitude, longitude, radius, fuel) }
        if (list.count { it.sells(fuel) } < MIN_RESULTS) {
            radius = WIDE_RADIUS_KM
            list = withContext(Dispatchers.IO) { CarburantsApi.nearby(latitude, longitude, radius, fuel) }
        }
        val names = currentNames()
        brands.ensureLoaded()
        return Result(list.filter { it.sells(fuel) }.map { decorate(it, names) }, radius)
    }

    data class Result(val stations: List<Station>, val radiusKm: Int)

    /**
     * Brand and logo lookups for these stations (those shown). Returns the stations again,
     * by id, when something changed, else null.
     */
    suspend fun enrich(stations: List<Station>): Map<Long, Station>? {
        if (!brands.lookup(stations)) return null
        val names = currentNames()
        return stations.associate { it.id to decorate(it, names) }
    }

    /** Download the logos of these stations. True when one became available. */
    suspend fun loadLogos(stations: List<Station>): Boolean =
        logos.preload(stations.mapNotNull { logos.remoteUrl(it) })

    private suspend fun currentNames(): Map<String, StationNames.Entry> {
        val current = names
        if (current.isCompleted && current.await().isEmpty()) {
            // Offline at start: try again for the next search.
            names = scope.async { stationNames.load() }
            return emptyMap()
        }
        return withTimeoutOrNull(NAMES_WAIT_MS) { current.await() } ?: emptyMap()
    }

    /** Name shown: community file, OpenStreetMap, then brand + town, then address (station_title). */
    private fun decorate(station: Station, names: Map<String, StationNames.Entry>): Station {
        val community = names[station.id.toString()]
        val info = brands.peek(station.id)
        val brand = community?.brand?.ifEmpty { null } ?: info?.brand
        val city = station.prettyCity
        val title = community?.name?.ifEmpty { null }
            ?: usefulOsmName(info?.osmName, info?.brand)
            ?: brand?.let { "$it $city".trim() }
            ?: "${station.address.ifEmpty { "Station" }} $city".trim()
        return station.copy(name = title, brand = brand, logoUrl = info?.logo)
    }

    companion object {
        const val RADIUS_KM = 10
        const val WIDE_RADIUS_KM = 30
        private const val MIN_RESULTS = 3
        private const val NAMES_WAIT_MS = 6_000L

        private val GENERIC_NAMES = setOf("station", "station service", "stationservice", "carburant", "carburants", "essence", "fuel")

        @Volatile private var instance: StationRepository? = null

        fun get(context: Context): StationRepository =
            instance ?: synchronized(this) {
                instance ?: StationRepository(context.applicationContext).also { instance = it }
            }

        private fun key(text: String): String =
            text.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.replace("  ", " ").trim()

        /** The OSM name, unless it says "Station-service" or is just the brand (short or long form). */
        fun usefulOsmName(name: String?, brand: String?): String? {
            if (name.isNullOrBlank()) return null
            val k = key(name.replace("-", " "))
            if (k in GENERIC_NAMES) return null
            if (!brand.isNullOrBlank()) {
                val compact = k.replace(" ", "")
                val brandCompact = key(brand.replace("-", " ")).replace(" ", "")
                if (compact.isNotEmpty() && brandCompact.isNotEmpty() &&
                    (compact in brandCompact || (brandCompact in compact && compact.length - brandCompact.length <= 8))
                ) {
                    return null
                }
            }
            return name.trim()
        }
    }
}
