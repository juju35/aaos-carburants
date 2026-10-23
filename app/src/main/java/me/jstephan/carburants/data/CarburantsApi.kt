package me.jstephan.carburants.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * "Prix des carburants en France – Flux instantané v2" (data.economie.gouv.fr), no key.
 * Same queries and parsing as ha-carburants-france's api.py.
 */
internal object CarburantsApi {
    private const val RECORDS =
        "https://data.economie.gouv.fr/api/explore/v2.1/catalog/datasets/" +
            "prix-des-carburants-en-france-flux-instantane-v2/records"
    private const val MAX_RESULTS = 50

    /** Up to 50 stations around the point, nearest first, that sell [fuel] (or had a shortage of it). */
    fun nearby(latitude: Double, longitude: Double, radiusKm: Int, fuel: Fuel): List<Station> {
        val point = "geom'POINT($longitude $latitude)'"
        val area = "within_distance(geom, $point, ${radiusKm}km)"
        val sold = "(${fuel.key}_prix is not null OR ${fuel.key}_rupture_type is not null)"
        val order = "distance(geom, $point)"
        val records = try {
            records("$area AND $sold", order)
        } catch (e: HttpException) {
            // Fall back to the plain area query if the filter is ever refused.
            if (e.code == 400) records(area, order) else throw e
        }
        return records.mapNotNull(::parseStation).map {
            it.copy(distanceKm = Geo.distanceKm(latitude, longitude, it.latitude, it.longitude))
        }
    }

    private fun records(where: String, orderBy: String): List<JSONObject> {
        val url = RECORDS + "?" + Http.query(
            mapOf("where" to where, "order_by" to orderBy, "limit" to MAX_RESULTS.toString()),
        )
        val results = JSONObject(Http.getText(url)).optJSONArray("results")
            ?: throw IOException("Unexpected response from the fuel prices API")
        return objects(results)
    }

    /**
     * One record. Prices come from the flat {fuel}_prix fields: a fuel with a price is
     * available, one with a shortage is not, one with neither but an ended shortage is
     * kept without a price. Anything else is not sold here.
     */
    fun parseStation(record: JSONObject): Station? {
        val id = when (val raw = record.opt("id")) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
            else -> null
        } ?: return null

        val geom = record.optJSONObject("geom")
        val latitude: Double
        val longitude: Double
        if (geom != null && geom.has("lat")) {
            latitude = geom.getDouble("lat")
            longitude = geom.getDouble("lon")
        } else {
            // The raw feed stores degrees * 100000 as strings.
            latitude = record.optString("latitude").toDoubleOrNull()?.div(100_000) ?: return null
            longitude = record.optString("longitude").toDoubleOrNull()?.div(100_000) ?: return null
        }

        val ended = endedRuptures(record.opt("rupture"))
        val fuels = buildMap {
            for (fuel in Fuel.entries) {
                val price = record.doubleOrNull("${fuel.key}_prix")
                val rupture = record.stringOrNull("${fuel.key}_rupture_type")
                if (price == null && rupture == null && ended[fuel] == null) continue
                put(
                    fuel,
                    FuelPrice(
                        price = price?.let { Math.round(it * 1000) / 1000.0 },
                        updated = parseTime(record.stringOrNull("${fuel.key}_maj")),
                        available = price != null && rupture == null,
                        rupture = rupture,
                        ruptureSince = if (rupture != null) parseTime(record.stringOrNull("${fuel.key}_rupture_debut")) else null,
                    ),
                )
            }
        }

        return Station(
            id = id,
            address = record.optString("adresse").trim(),
            postcode = record.optString("cp"),
            city = record.optString("ville").trim(),
            latitude = latitude,
            longitude = longitude,
            highway = record.optString("pop") == "A",
            automate24 = record.optString("horaires_automate_24_24") == "Oui",
            hours = parseHours(record.opt("horaires")),
            services = (record.opt("services_service") as? JSONArray)
                ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it).ifEmpty { null } } }
                .orEmpty(),
            fuels = fuels,
        )
    }

    /** "horaires" is a JSON string; "horaire" is one range or a list, "@ferme" "1" means closed. */
    private fun parseHours(raw: Any?): List<DayHours> {
        val obj = jsonValue(raw) as? JSONObject ?: return emptyList()
        return objects(obj.opt("jour")).mapNotNull { day ->
            val number = day.optString("@id").toIntOrNull() ?: return@mapNotNull null
            val ranges = objects(day.opt("horaire")).mapNotNull { range ->
                val open = range.optString("@ouverture").replace('.', ':')
                val close = range.optString("@fermeture").replace('.', ':')
                if (open.isEmpty() || close.isEmpty()) null else open to close
            }
            DayHours(number, day.optString("@ferme") == "1", ranges)
        }.sortedBy { it.day }
    }

    /** Fuels listed in the raw "rupture" JSON with an end date (shortage just lifted). */
    private fun endedRuptures(raw: Any?): Map<Fuel, Instant> =
        objects(jsonValue(raw)).mapNotNull { item ->
            val fuel = Fuel.entries.firstOrNull { it.apiName.equals(item.optString("@nom"), ignoreCase = true) }
            val end = parseTime(item.optString("@fin"))
            if (fuel != null && end != null) fuel to end else null
        }.toMap()

    /** Feed times: ISO with offset, or "2026-10-20 18:05:00" in UTC. */
    fun parseTime(value: String?): Instant? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(text.replace(' ', 'T')).toInstant(ZoneOffset.UTC) }.getOrNull()
    }

    private fun jsonValue(raw: Any?): Any? =
        if (raw is String) runCatching { JSONTokener(raw).nextValue() }.getOrNull() else raw

    private fun objects(value: Any?): List<JSONObject> = when (value) {
        is JSONObject -> listOf(value)
        is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }
        else -> emptyList()
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).trim().ifEmpty { null }

    private fun JSONObject.doubleOrNull(key: String): Double? =
        if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }
}
