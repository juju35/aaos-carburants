package me.jstephan.carburants.data

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import me.jstephan.carburants.R
import org.json.JSONObject

/** Brand logos shipped with the app (res/raw/brand_logos.json), found from the brand or station name. */
internal class BrandLogos(private val context: Context) {
    data class Logo(val res: Int, val dark: Boolean)

    private data class Entry(val key: String, val logo: Logo)

    // Longest keys first: "totalaccess" before "total", "geantcasino" before "casino".
    private val entries: List<Entry> by lazy { load().sortedByDescending { it.key.length } }

    /** The logo for these names (brand first, then station name), null when none is shipped. */
    fun find(vararg names: String?): Logo? = names.firstNotNullOfOrNull { it?.let(::match) }

    private fun match(name: String): Logo? {
        val key = BrandIndex.key(name)
        if (key.isEmpty()) return null
        return entries.firstOrNull { e -> key == e.key || (e.key.length >= MIN_PREFIX && key.startsWith(e.key)) }?.logo
    }

    @SuppressLint("DiscouragedApi") // Names come from the JSON file shared with the screenshot script.
    private fun load(): List<Entry> = runCatching {
        val text = context.resources.openRawResource(R.raw.brand_logos).use { it.readBytes().decodeToString() }
        val brands = JSONObject(text).getJSONArray("brands")
        (0 until brands.length()).flatMap { i ->
            val brand = brands.getJSONObject(i)
            val res = context.resources.getIdentifier(brand.getString("logo"), "drawable", context.packageName)
            val keys = brand.getJSONArray("keys")
            if (res == 0) {
                emptyList()
            } else {
                (0 until keys.length()).map { Entry(keys.getString(it), Logo(res, brand.optBoolean("dark"))) }
            }
        }
    }.onFailure { Log.w(TAG, "Brand logos unreadable", it) }.getOrDefault(emptyList())

    private companion object {
        const val TAG = "BrandLogos"
        const val MIN_PREFIX = 4
    }
}
