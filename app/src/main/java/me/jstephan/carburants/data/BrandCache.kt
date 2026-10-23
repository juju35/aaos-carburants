package me.jstephan.carburants.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Brand and logo, after ha-carburants-france's brands.py, cached in the app's files:
 * - per station, from OpenStreetMap (Overpass): `brand`, `brand:wikidata` and `name` of the
 *   station carrying the feed id in `ref:FR:prix-carburants`, else the nearest branded one;
 * - per brand (Wikidata id), the logo: "small logo or icon" (P8972, square, best on a tile),
 *   else "logo image" (P154), served by Wikimedia Commons.
 * Best effort: a failure only means initials for now, retried later.
 */
internal class BrandCache(context: Context) {
    data class Info(val brand: String?, val osmName: String?, val wikidata: String?, val checkedAt: Long)
    private data class Logo(val url: String?, val checkedAt: Long)

    private val stationsFile = File(context.filesDir, "brands_v2.json")
    private val logosFile = File(context.filesDir, "logos.json")
    private val mutex = Mutex()

    @Volatile private var stations: MutableMap<Long, Info>? = null

    @Volatile private var logos: MutableMap<String, Logo>? = null

    fun peek(id: Long): Info? = stations?.get(id)

    /** Logo URL of this brand, null when unknown or none. */
    fun logo(qid: String?): String? = qid?.let { logos?.get(it)?.url }

    suspend fun ensureLoaded() {
        if (stations != null && logos != null) return
        mutex.withLock {
            withContext(Dispatchers.IO) {
                stationMap()
                logoMap()
            }
        }
    }

    /** OpenStreetMap lookup of the stations not checked yet. True when something new was stored. */
    suspend fun lookupStations(list: List<Station>): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val map = stationMap()
            val now = System.currentTimeMillis()
            val todo = list.filter { s ->
                val info = map[s.id]
                info == null || ((info.brand == null || info.wikidata == null) && now - info.checkedAt > RETRY_MS)
            }
            if (todo.isEmpty()) return@withContext false
            val found = runCatching { overpass(todo) }
                .onFailure { Log.d(TAG, "Brand lookup failed", it) }
                .getOrNull() ?: return@withContext false
            for (s in todo) {
                val tags = found[s.id]
                map[s.id] = Info(tags?.brand, tags?.name, tags?.wikidata, now)
            }
            save(stationsFile, map) { it.toJson() }
            true
        }
    }

    /** Wikidata lookup of the brands without a known logo. True when a logo was found. */
    suspend fun lookupLogos(qids: Set<String>): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val map = logoMap()
            val now = System.currentTimeMillis()
            val todo = qids.filter { q -> map[q].let { it == null || (it.url == null && now - it.checkedAt > RETRY_MS) } }.toSet()
            if (todo.isEmpty()) return@withContext false
            val found = runCatching { wikidataLogos(todo) }
                .onFailure { Log.d(TAG, "Logo lookup failed", it) }
                .getOrNull() ?: return@withContext false
            for (q in todo) map[q] = Logo(found[q], now)
            save(logosFile, map) { JSONObject().put("url", it.url ?: "").put("checked_at", it.checkedAt) }
            found.values.any { it != null }
        }
    }

    // ── OpenStreetMap ────────────────────────────────────────────────────────

    private data class Tags(val brand: String?, val name: String?, val wikidata: String?)

    private fun overpass(list: List<Station>): Map<Long, Tags> {
        val parts = list.joinToString("") {
            "nwr[\"amenity\"=\"fuel\"](around:$NEARBY_METERS,${it.latitude},${it.longitude});"
        }
        val body = Http.postForm(OVERPASS_URL, mapOf("data" to "[out:json][timeout:30];($parts);out tags center;"))
        val elements = JSONObject(body).optJSONArray("elements") ?: JSONArray()
        val all = (0 until elements.length()).mapNotNull { elements.optJSONObject(it) }
        return list.mapNotNull { s -> match(s, all)?.let { s.id to it } }.toMap()
    }

    /** The OSM station carrying this feed id, else the nearest branded one. */
    private fun match(station: Station, elements: List<JSONObject>): Tags? {
        val sid = station.id.toString()
        var nearest: JSONObject? = null
        var best = Double.MAX_VALUE
        for (element in elements) {
            val tags = element.optJSONObject("tags") ?: continue
            val refs = tags.optString("ref:FR:prix-carburants").replace(" ", "").split(";")
            if (sid in refs) return tags.toTags()
            val dist = distanceIfBranded(station, element, tags)
            if (dist != null && dist < best) {
                best = dist
                nearest = tags
            }
        }
        return nearest?.toTags()
    }

    /** Distance to an OSM station that has a brand or a name (point, or centre of a way), else null. */
    private fun distanceIfBranded(station: Station, element: JSONObject, tags: JSONObject): Double? {
        if (tags.optString("brand").isEmpty() && tags.optString("name").isEmpty()) return null
        val point = if (element.has("lat")) element else element.optJSONObject("center") ?: return null
        if (!point.has("lat") || !point.has("lon")) return null
        return Geo.distanceKm(station.latitude, station.longitude, point.optDouble("lat"), point.optDouble("lon"))
    }

    private fun JSONObject.toTags(): Tags {
        val name = optString("name").ifEmpty { null }
        return Tags(optString("brand").ifEmpty { name }, name, optString("brand:wikidata").ifEmpty { null })
    }

    // ── Wikidata ─────────────────────────────────────────────────────────────

    private fun wikidataLogos(qids: Set<String>): Map<String, String?> {
        val result = HashMap<String, String?>()
        for (chunk in qids.filter { it.startsWith("Q") }.sorted().chunked(50)) {
            val url = WIKIDATA_URL + "?" + Http.query(
                mapOf("action" to "wbgetentities", "ids" to chunk.joinToString("|"), "props" to "claims", "format" to "json"),
            )
            val entities = JSONObject(Http.getText(url)).optJSONObject("entities") ?: continue
            for (qid in chunk) {
                val claims = entities.optJSONObject(qid)?.optJSONObject("claims")
                val file = LOGO_PROPERTIES.firstNotNullOfOrNull { p -> claims?.optJSONArray(p)?.let(::currentFile) }
                result[qid] = file?.let { "$COMMONS_FILE_URL${Uri.encode(it.replace(' ', '_'))}?width=128" }
            }
        }
        return result
    }

    /** Brands keep their old logos with an end time: preferred rank, else no end time (P582), else the last one. */
    private fun currentFile(claims: JSONArray): String? {
        val usable = (0 until claims.length()).mapNotNull { claims.optJSONObject(it) }.filter {
            it.optString("rank") != "deprecated" &&
                it.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.opt("value") is String
        }
        if (usable.isEmpty()) return null
        val claim = usable.firstOrNull { it.optString("rank") == "preferred" }
            ?: usable.lastOrNull { it.optJSONObject("qualifiers")?.has("P582") != true }
            ?: usable.last()
        return claim.getJSONObject("mainsnak").getJSONObject("datavalue").getString("value")
    }

    // ── Storage ──────────────────────────────────────────────────────────────

    private fun Info.toJson(): JSONObject = JSONObject()
        .put("brand", brand ?: "")
        .put("osm_name", osmName ?: "")
        .put("wikidata", wikidata ?: "")
        .put("checked_at", checkedAt)

    private fun stationMap(): MutableMap<Long, Info> {
        stations?.let { return it }
        val loaded = HashMap<Long, Info>()
        read(stationsFile) { key, o ->
            loaded[key.toLong()] = Info(
                brand = o.optString("brand").ifEmpty { null },
                osmName = o.optString("osm_name").ifEmpty { null },
                wikidata = o.optString("wikidata").ifEmpty { null },
                checkedAt = o.optLong("checked_at"),
            )
        }
        stations = loaded
        return loaded
    }

    private fun logoMap(): MutableMap<String, Logo> {
        logos?.let { return it }
        val loaded = HashMap<String, Logo>()
        read(logosFile) { key, o -> loaded[key] = Logo(o.optString("url").ifEmpty { null }, o.optLong("checked_at")) }
        logos = loaded
        return loaded
    }

    private fun read(file: File, each: (String, JSONObject) -> Unit) {
        if (!file.exists()) return
        runCatching {
            val json = JSONObject(file.readText())
            for (key in json.keys()) json.optJSONObject(key)?.let { each(key, it) }
        }.onFailure { Log.w(TAG, "${file.name} unreadable, starting over", it) }
    }

    private fun <K, V> save(file: File, map: Map<K, V>, toJson: (V) -> JSONObject) {
        val json = JSONObject()
        for ((key, value) in map) json.put(key.toString(), toJson(value))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file)
    }

    companion object {
        private const val TAG = "BrandCache"
        private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
        private const val WIKIDATA_URL = "https://www.wikidata.org/w/api.php"
        private const val COMMONS_FILE_URL = "https://commons.wikimedia.org/wiki/Special:FilePath/"

        // Small logo or icon (square), then logo image.
        private val LOGO_PROPERTIES = listOf("P8972", "P154")

        // The feed's coordinates are rounded to ~100 m and sometimes sit on the street.
        private const val NEARBY_METERS = 300

        // Missing brand or logo: looked up again after a week.
        private const val RETRY_MS = 7L * 24 * 3600 * 1000
    }
}
