package me.jstephan.carburants.data

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Brand logos from Wikidata, served by Wikimedia Commons. Blocking: call from Dispatchers.IO. */
internal object WikidataLogos {
    private const val WIKIDATA_URL = "https://www.wikidata.org/w/api.php"
    private const val COMMONS_FILE_URL = "https://commons.wikimedia.org/wiki/Special:FilePath/"

    // Small logo or icon (square), then logo image.
    private val LOGO_PROPERTIES = listOf("P8972", "P154")

    // Parent organisation, then part of: where a sub-brand's logo is found.
    private val PARENT_PROPERTIES = listOf("P749", "P361")

    /**
     * Logo URL per brand. Sub-brands (Super U, Carrefour Market...) often have no logo of
     * their own: they take their parent organisation's (P749), else what they are part of (P361).
     */
    fun urls(qids: Set<String>): Map<String, String?> {
        val claims = entityClaims(qids)
        val files = qids.associateWith { q -> claims[q]?.let(::logoFile) }.toMutableMap()
        val parents = files.filterValues { it == null }.keys
            .mapNotNull { q -> claims[q]?.let(::parentOf)?.let { q to it } }
            .toMap()
        if (parents.isNotEmpty()) {
            val parentClaims = entityClaims(parents.values.toSet())
            for ((q, parent) in parents) files[q] = parentClaims[parent]?.let(::logoFile)
        }
        return files.mapValues { (_, file) -> file?.let { "$COMMONS_FILE_URL${Uri.encode(it.replace(' ', '_'))}?width=128" } }
    }

    /** Claims of each entity, by Wikidata id. */
    private fun entityClaims(qids: Set<String>): Map<String, JSONObject> {
        val result = HashMap<String, JSONObject>()
        for (chunk in qids.filter { it.startsWith("Q") }.sorted().chunked(50)) {
            val url = WIKIDATA_URL + "?" + Http.query(
                mapOf("action" to "wbgetentities", "ids" to chunk.joinToString("|"), "props" to "claims", "format" to "json"),
            )
            val entities = JSONObject(Http.getText(url)).optJSONObject("entities") ?: continue
            for (qid in chunk) entities.optJSONObject(qid)?.optJSONObject("claims")?.let { result[qid] = it }
        }
        return result
    }

    private fun logoFile(claims: JSONObject): String? =
        LOGO_PROPERTIES.firstNotNullOfOrNull { p -> claims.optJSONArray(p)?.let { current(it) { v -> v is String } } as? String }

    private fun parentOf(claims: JSONObject): String? =
        PARENT_PROPERTIES.firstNotNullOfOrNull { p ->
            (claims.optJSONArray(p)?.let { current(it) { v -> v is JSONObject } } as? JSONObject)
                ?.optString("id")
                ?.takeIf { it.startsWith("Q") }
        }

    /**
     * Value of the current statement: brands keep their old logos with an end time, so the
     * preferred rank first, else one with no end time (P582), else the last one.
     */
    private fun current(claims: JSONArray, accepts: (Any?) -> Boolean): Any? {
        val usable = (0 until claims.length()).mapNotNull { claims.optJSONObject(it) }.filter {
            it.optString("rank") != "deprecated" && accepts(it.value())
        }
        if (usable.isEmpty()) return null
        val claim = usable.firstOrNull { it.optString("rank") == "preferred" }
            ?: usable.lastOrNull { it.optJSONObject("qualifiers")?.has("P582") != true }
            ?: usable.last()
        return claim.value()
    }

    private fun JSONObject.value(): Any? = optJSONObject("mainsnak")?.optJSONObject("datavalue")?.opt("value")
}
