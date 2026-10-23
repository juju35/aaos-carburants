package me.jstephan.carburants.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Station names from Aohzan/hass-prixcarburant's community file (stations_name.json,
 * {id: {name, brand}}, Apache-2.0), as in ha-carburants-france's names.py.
 * Downloaded at most once a week and kept in the app's files, so an outage only
 * means the cached copy.
 */
internal class StationNames(context: Context) {
    data class Entry(val name: String, val brand: String)

    private val download = CachedDownload(File(context.filesDir, "stations_name.json"), URL, REFRESH_MS)

    suspend fun load(): Map<String, Entry> = withContext(Dispatchers.IO) {
        download.load(::parse) { it.isEmpty() }.orEmpty()
    }

    private fun parse(text: String): Map<String, Entry> {
        val json = JSONObject(text)
        val names = HashMap<String, Entry>(json.length())
        for (id in json.keys()) {
            val info = json.optJSONObject(id) ?: continue
            val name = info.optString("name").trim()
            val brand = info.optString("brand").trim()
            if (name.isNotEmpty() || brand.isNotEmpty()) names[id] = Entry(prettyName(name), brand)
        }
        return names
    }

    companion object {
        private const val URL =
            "https://raw.githubusercontent.com/Aohzan/hass-prixcarburant/refs/heads/master/" +
                "custom_components/prix_carburant/stations_name.json"
        private const val REFRESH_MS = 7L * 24 * 3600 * 1000

        // Left in lower case when an all-caps name is put in title case, except first.
        private val SMALL_WORDS =
            setOf("de", "du", "des", "la", "le", "les", "et", "sur", "sous", "en", "aux", "au", "d", "l")

        // Kept as written: road numbers, "SARL", "ZA"...
        private val UPPER_WORDS =
            Regex("^(?:[A-Z]{1,3}\\d+[A-Z]?|RN|RD|ZA|ZI|ZAC|SA|SAS|SARL|SNC|EURL|BP|E\\.?\\s?LECLERC)$")
        private const val SEPARATORS = "-'’"

        /** "RELAIS LES TROIS MARCHES" -> "Relais les Trois Marches". Names not in capitals are kept. */
        fun prettyName(input: String): String {
            val name = input.trim().split(Regex("\\s+")).joinToString(" ")
            if (name.isEmpty() || name != name.uppercase() || name.none { it.isLetter() }) return name
            return name.split(" ").mapIndexed { index, word ->
                if (UPPER_WORDS.matches(word)) {
                    word
                } else {
                    splitKeepingSeparators(word.lowercase()).mapIndexed { n, part ->
                        when {
                            part.isEmpty() || part in SEPARATORS -> part
                            part in SMALL_WORDS && (index > 0 || n > 0) -> part
                            else -> part.replaceFirstChar(Char::titlecase)
                        }
                    }.joinToString("")
                }
            }.joinToString(" ")
        }

        private fun splitKeepingSeparators(word: String): List<String> {
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            for (ch in word) {
                if (ch in SEPARATORS) {
                    parts += current.toString()
                    current.clear()
                    parts += ch.toString()
                } else {
                    current.append(ch)
                }
            }
            parts += current.toString()
            return parts
        }
    }
}
