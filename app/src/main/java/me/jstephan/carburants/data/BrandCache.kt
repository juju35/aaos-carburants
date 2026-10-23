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
 * Brand and logo, as in ha-carburants-france's brands.py: OpenStreetMap (Overpass) gives
 * `brand`, `brand:wikidata` and `name` for the station carrying the feed id in
 * `ref:FR:prix-carburants` (else the nearest branded one), Wikidata P154 the logo.
 * Best effort, cached per station in the app's files.
 */
internal class BrandCache(context: Context) {
    data class Info(
        val brand: String?,
        val osmName: String?,
        val wikidata: String?,
        val logo: String?,
        val checkedAt: Long,
        val logoChecked: Boolean,
    )

    private val file = File(context.filesDir, "brands.json")
    private val mutex = Mutex()
    @Volatile private var cache: MutableMap<Long, Info>? = null

    fun peek(id: Long): Info? = cache?.get(id)

    suspend fun ensureLoaded() {
        if (cache == null) mutex.withLock { withContext(Dispatchers.IO) { map() } }
    }

    /** Look up the stations not checked yet. True when something new was stored. */
    suspend fun lookup(stations: List<Station>): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val map = map()
            val now = System.currentTimeMillis()
            var changed = false

            val todo = stations.filter { s ->
                val info = map[s.id]
                info == null || (info.brand == null && now - info.checkedAt > RETRY_MS)
            }
            if (todo.isNotEmpty()) {
                val found = runCatching { overpass(todo) }
                    .onFailure { Log.d(TAG, "Brand lookup failed", it) }
                    .getOrNull()
                if (found != null) {
                    for (s in todo) {
                        val tags = found[s.id]
                        map[s.id] = Info(tags?.brand, tags?.name, tags?.wikidata, null, now, logoChecked = false)
                    }
                    changed = true
                }
            }

            val qids = stations.mapNotNull { map[it.id] }
                .filter { it.wikidata != null && !it.logoChecked }
                .mapNotNull { it.wikidata }
                .toSet()
            if (qids.isNotEmpty()) {
                val logos = runCatching { wikidataLogos(qids) }
                    .onFailure { Log.d(TAG, "Logo lookup failed", it) }
                    .getOrNull()
                if (logos != null) {
                    map.replaceAll { _, info ->
                        if (info.wikidata in qids) info.copy(logo = logos[info.wikidata], logoChecked = true) else info
                    }
                    changed = true
                }
            }

            if (changed) save(map)
            changed
        }
    }

    // ── OpenStreetMap ────────────────────────────────────────────────────────

    private data class Tags(val brand: String?, val name: String?, val wikidata: String?)

    private fun overpass(stations: List<Station>): Map<Long, Tags> {
        val parts = stations.joinToString("") {
            "nwr[\"amenity\"=\"fuel\"](around:$NEARBY_METERS,${it.latitude},${it.longitude});"
        }
        val body = Http.postForm(OVERPASS_URL, mapOf("data" to "[out:json][timeout:30];($parts);out tags center;"))
        val elements = JSONObject(body).optJSONArray("elements") ?: JSONArray()
        val list = (0 until elements.length()).mapNotNull { elements.optJSONObject(it) }
        return stations.mapNotNull { s -> match(s, list)?.let { s.id to it } }.toMap()
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
            if (tags.optString("brand").isEmpty() && tags.optString("name").isEmpty()) continue
            val center = element.optJSONObject("center")
            val lat = if (element.has("lat")) element.optDouble("lat") else center?.optDouble("lat") ?: continue
            val lon = if (element.has("lon")) element.optDouble("lon") else center?.optDouble("lon") ?: continue
            val dist = Geo.distanceKm(station.latitude, station.longitude, lat, lon)
            if (dist < best) {
                best = dist
                nearest = tags
            }
        }
        return nearest?.toTags()
    }

    private fun JSONObject.toTags(): Tags {
        val name = optString("name").ifEmpty { null }
        return Tags(optString("brand").ifEmpty { name }, name, optString("brand:wikidata").ifEmpty { null })
    }

    // ── Wikidata ─────────────────────────────────────────────────────────────

    private fun wikidataLogos(qids: Set<String>): Map<String, String?> {
        val logos = HashMap<String, String?>()
        for (chunk in qids.filter { it.startsWith("Q") }.sorted().chunked(50)) {
            val url = WIKIDATA_URL + "?" + Http.query(
                mapOf("action" to "wbgetentities", "ids" to chunk.joinToString("|"), "props" to "claims", "format" to "json"),
            )
            val entities = JSONObject(Http.getText(url)).optJSONObject("entities") ?: continue
            for (qid in chunk) {
                val claims = entities.optJSONObject(qid)?.optJSONObject("claims")?.optJSONArray("P154")
                val file = claims?.let(::currentLogo)
                logos[qid] = file?.let { "$COMMONS_FILE_URL${Uri.encode(it.replace(' ', '_'))}?width=128" }
            }
        }
        return logos
    }

    /** Brands keep their old logos with an end time: preferred rank, else no P582, else the last one. */
    private fun currentLogo(claims: JSONArray): String? {
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

    private fun map(): MutableMap<Long, Info> {
        cache?.let { return it }
        val loaded = HashMap<Long, Info>()
        if (file.exists()) {
            runCatching {
                val json = JSONObject(file.readText())
                for (key in json.keys()) {
                    val o = json.optJSONObject(key) ?: continue
                    loaded[key.toLong()] = Info(
                        brand = o.optString("brand").ifEmpty { null },
                        osmName = o.optString("osm_name").ifEmpty { null },
                        wikidata = o.optString("wikidata").ifEmpty { null },
                        logo = o.optString("logo").ifEmpty { null },
                        checkedAt = o.optLong("checked_at"),
                        logoChecked = o.optBoolean("logo_checked"),
                    )
                }
            }.onFailure { Log.w(TAG, "Brand cache unreadable, starting over", it) }
        }
        cache = loaded
        return loaded
    }

    private fun save(map: Map<Long, Info>) {
        val json = JSONObject()
        for ((id, info) in map) {
            json.put(
                id.toString(),
                JSONObject()
                    .put("brand", info.brand ?: "")
                    .put("osm_name", info.osmName ?: "")
                    .put("wikidata", info.wikidata ?: "")
                    .put("logo", info.logo ?: "")
                    .put("checked_at", info.checkedAt)
                    .put("logo_checked", info.logoChecked),
            )
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file)
    }

    companion object {
        private const val TAG = "BrandCache"
        private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
        private const val WIKIDATA_URL = "https://www.wikidata.org/w/api.php"
        private const val COMMONS_FILE_URL = "https://commons.wikimedia.org/wiki/Special:FilePath/"
        // The feed's coordinates are rounded to ~100 m and sometimes sit on the street.
        private const val NEARBY_METERS = 300
        // A station with no brand in OSM is looked up again after a week.
        private const val RETRY_MS = 7L * 24 * 3600 * 1000
    }
}
