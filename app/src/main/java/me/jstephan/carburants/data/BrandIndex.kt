package me.jstephan.carburants.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer

/**
 * Brand name -> Wikidata id for fuel stations, from OpenStreetMap's name-suggestion-index
 * (data/brands/amenity/fuel.json, BSD-3-Clause): the brands OSM editors use, each with
 * its `brand:wikidata` and the names it goes by. Used when the OSM station itself has no
 * `brand:wikidata` but its brand is known. Downloaded at most once a week, cached.
 */
internal class BrandIndex(context: Context) {
    private val file = File(context.filesDir, "nsi_fuel.json")
    @Volatile private var index: Map<String, String>? = null

    /**
     * Wikidata id of this brand name, once [load] has run. "Intermarché Super Rennes" falls
     * back to "Intermarché Super", then "Intermarché".
     */
    fun qid(brand: String?): String? {
        val map = index ?: return null
        var words = brand?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: return null
        while (words.isNotEmpty()) {
            map[key(words.joinToString(" "))]?.let { return it }
            words = words.dropLast(1)
        }
        return null
    }

    suspend fun load() = withContext(Dispatchers.IO) {
        if (index != null) return@withContext
        val stale = !file.exists() || System.currentTimeMillis() - file.lastModified() > REFRESH_MS
        if (stale) {
            try {
                val bytes = Http.get(URL, timeoutMs = 30_000)
                val parsed = parse(bytes.decodeToString())
                if (parsed.isNotEmpty()) {
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.writeBytes(bytes)
                    tmp.renameTo(file)
                    index = parsed
                    return@withContext
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not download the brand index, using the cached copy", e)
            }
        }
        index = if (file.exists()) runCatching { parse(file.readText()) }.getOrDefault(emptyMap()) else emptyMap()
    }

    /** Brands used in France (or worldwide / Europe) first: a French brand wins a name clash. */
    private fun parse(text: String): Map<String, String> {
        val items = JSONObject(text).optJSONArray("items") ?: return emptyMap()
        val ranked = (0 until items.length()).mapNotNull { items.optJSONObject(it) }.sortedBy { rank(it) }
        val map = HashMap<String, String>()
        for (item in ranked) {
            if (rank(item) > 2) break // Not used in France.
            val tags = item.optJSONObject("tags") ?: continue
            val qid = tags.optString("brand:wikidata").takeIf { it.startsWith("Q") } ?: continue
            val names = listOf(item.optString("displayName"), tags.optString("brand"), tags.optString("name")) +
                strings(item.optJSONArray("matchNames"))
            for (name in names) {
                val k = key(name)
                if (k.isNotEmpty()) map.putIfAbsent(k, qid)
            }
        }
        return map
    }

    /** 0: France listed, 1: Europe, 2: world, 3: elsewhere only or France excluded. */
    private fun rank(item: JSONObject): Int {
        val set = item.optJSONObject("locationSet") ?: return 2
        val include = strings(set.optJSONArray("include")).map { it.lowercase() }
        val exclude = strings(set.optJSONArray("exclude")).map { it.lowercase() }
        return when {
            "fr" in exclude -> 3
            include.any { it == "fr" || it.startsWith("fr-") || it.startsWith("fr.") } -> 0
            "150" in include -> 1
            "001" in include || include.isEmpty() -> 2
            else -> 3
        }
    }

    private fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.opt(it) as? String }

    companion object {
        private const val TAG = "BrandIndex"
        private const val URL =
            "https://raw.githubusercontent.com/osmlab/name-suggestion-index/main/data/brands/amenity/fuel.json"
        private const val REFRESH_MS = 7L * 24 * 3600 * 1000

        /** "E. Leclerc", "E.LECLERC", "e-leclerc" -> "eleclerc"; accents dropped. */
        fun key(name: String): String =
            Normalizer.normalize(name, Normalizer.Form.NFD)
                .lowercase()
                .filter { it in 'a'..'z' || it in '0'..'9' }
    }
}
