package me.jstephan.carburants.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Stations around a point with their name, brand and logo. One per process. */
class StationRepository private constructor(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stationNames = StationNames(context)
    private val brands = BrandCache(context)
    private val brandIndex = BrandIndex(context)
    val logos = LogoLoader(context)

    // Started right away: the first download (~1 MB) must not hold up the first list.
    private var names: Deferred<Map<String, StationNames.Entry>> = scope.async { stationNames.load() }

    // Same for the brand index: until it is there, logos come from OpenStreetMap's brand:wikidata only.
    init {
        scope.launch { runCatching { brandIndex.load() } }
    }

    /** Nearest stations selling one of [fuels], within [RADIUS_KM], widened to [WIDE_RADIUS_KM] when there are few. */
    suspend fun nearby(latitude: Double, longitude: Double, fuels: List<Fuel>): Result {
        var radius = RADIUS_KM
        var list = withContext(Dispatchers.IO) { CarburantsApi.nearby(latitude, longitude, radius, fuels) }
        if (list.count { it.sellsAny(fuels) } < MIN_RESULTS) {
            radius = WIDE_RADIUS_KM
            list = withContext(Dispatchers.IO) { CarburantsApi.nearby(latitude, longitude, radius, fuels) }
        }
        val names = currentNames()
        brands.ensureLoaded()
        return Result(list.filter { it.sellsAny(fuels) }.map { decorate(it, names) }, radius)
    }

    data class Result(val stations: List<Station>, val radiusKm: Int)

    /**
     * Names, brands and logos for these stations (those shown): the names file if it came
     * after the list, OpenStreetMap per station, then the logo of each brand. Returns the
     * stations again, by id, when one of them changed, else null.
     */
    suspend fun enrich(stations: List<Station>): Map<Long, Station>? {
        brands.lookupStations(stations)
        val names = currentNames()
        brands.lookupLogos(stations.map { decorate(it, names) }.mapNotNull { qid(it) }.toSet())
        val decorated = stations.map { decorate(it, names) }
        return if (decorated == stations) null else decorated.associateBy { it.id }
    }

    /** The brand's Wikidata id: from the OSM station, else from the brand name (name-suggestion-index). */
    private fun qid(station: Station): String? =
        brands.peek(station.id)?.wikidata ?: brandIndex.qid(station.brand) ?: brandIndex.qid(brands.peek(station.id)?.brand)

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
        val decorated = station.copy(name = title, brand = brand)
        return decorated.copy(logoUrl = brands.logo(qid(decorated)))
    }

    companion object {
        const val RADIUS_KM = 10
        const val WIDE_RADIUS_KM = 30
        private const val MIN_RESULTS = 3

        // The list must show within 10 s (car app quality DR-3): names that come later are
        // added by enrich().
        private const val NAMES_WAIT_MS = 2_500L

        private val GENERIC_NAMES =
            setOf("station", "station service", "stationservice", "carburant", "carburants", "essence", "fuel")

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
                // Shorter than the brand, or the brand plus a few letters ("Energies", an initial).
                val shortForm = compact in brandCompact
                val longForm = brandCompact in compact && compact.length - brandCompact.length <= 8
                val comparable = compact.isNotEmpty() && brandCompact.isNotEmpty()
                if (comparable && (shortForm || longForm)) return null
            }
            return name.trim()
        }
    }
}
