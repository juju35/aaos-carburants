package me.jstephan.carburants.data

import org.json.JSONArray
import org.json.JSONObject

/** A French commune, centre included, as the search suggests it. */
data class Town(
    val name: String,
    val postcode: String,
    val department: String,
    val latitude: Double,
    val longitude: Double,
)

/**
 * Towns from geo.api.gouv.fr (API Découpage administratif, no key): by name, most populated
 * first, or by postcode. Blocking: call from Dispatchers.IO.
 */
internal object TownApi {
    private const val URL = "https://geo.api.gouv.fr/communes"
    private const val LIMIT = 8
    private val POSTCODE = Regex("\\d{5}")

    fun search(query: String): List<Town> {
        val text = query.trim()
        val byPostcode = POSTCODE.matches(text)
        val params = mapOf(
            (if (byPostcode) "codePostal" else "nom") to text,
            "fields" to "nom,codesPostaux,departement,centre",
            "boost" to "population",
            "limit" to LIMIT.toString(),
        )
        val towns = JSONArray(Http.getText("$URL?${Http.query(params)}"))
        return (0 until towns.length()).mapNotNull { i ->
            towns.optJSONObject(i)?.let { parse(it, text.takeIf { byPostcode }) }
        }
    }

    private fun parse(town: JSONObject, postcode: String?): Town? {
        val point = town.optJSONObject("centre")?.optJSONArray("coordinates") ?: return null
        val postcodes = town.optJSONArray("codesPostaux")
            ?.let { list -> (0 until list.length()).map { list.optString(it) }.filter { it.isNotEmpty() }.sorted() }
            .orEmpty()
        return Town(
            name = town.optString("nom").ifEmpty { return null },
            postcode = postcode ?: postcodes.firstOrNull().orEmpty(),
            department = town.optJSONObject("departement")?.optString("nom").orEmpty(),
            latitude = point.optDouble(1).takeIf { !it.isNaN() } ?: return null,
            longitude = point.optDouble(0).takeIf { !it.isNaN() } ?: return null,
        )
    }
}
