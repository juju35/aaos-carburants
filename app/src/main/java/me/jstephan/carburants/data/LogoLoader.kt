package me.jstephan.carburants.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.jstephan.carburants.R
import me.jstephan.carburants.ui.Badges
import java.io.File
import java.security.MessageDigest

/** Station logos: bundled (Total), downloaded from Wikimedia Commons, or an initials badge. */
class LogoLoader internal constructor(private val context: Context) {
    private val downloaded = LruCache<String, Bitmap>(48)
    private val tiles = LruCache<String, Bitmap>(64)
    private val dir = File(context.cacheDir, "logos").apply { mkdirs() }

    /** Logo URL to download for this station, null when bundled or unknown. */
    fun remoteUrl(station: Station): String? = if (bundled(station.brand) != null) null else station.logoUrl

    /** Download the logos not in memory yet. True when at least one is new. */
    suspend fun preload(urls: Collection<String>): Boolean = withContext(Dispatchers.IO) {
        var any = false
        for (url in urls.toSet()) {
            if (downloaded.get(url) != null) continue
            load(url)?.let {
                downloaded.put(url, it)
                any = true
            }
        }
        any
    }

    /** The station's image, [size] px square. Never null: falls back to initials. */
    fun stationBitmap(station: Station, size: Int): Bitmap {
        val res = bundled(station.brand)
        val url = station.logoUrl
        val source = when {
            res != null -> "res:$res"
            url != null && downloaded.get(url) != null -> url
            else -> "initials:${station.brand ?: station.name}"
        }
        val key = "$size|$source"
        tiles.get(key)?.let { return it }
        val bitmap = when {
            res != null -> BitmapFactory.decodeResource(context.resources, res)?.let { Badges.logoTile(it, size) }
            url != null -> downloaded.get(url)?.let { Badges.logoTile(it, size) }
            else -> null
        } ?: Badges.initialsBadge(station.brand ?: station.name, size)
        tiles.put(key, bitmap)
        return bitmap
    }

    private fun load(url: String): Bitmap? {
        val file = File(dir, sha1(url) + ".img")
        val bytes = if (file.exists()) {
            file.readBytes()
        } else {
            runCatching { Http.get(url, timeoutMs = 15_000) }.getOrNull()?.also { file.writeBytes(it) }
        } ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        // Sub-brands share their network's logo in Wikidata: shipped with the app, as in the card.
        private val TOTAL_ACCESS = Regex("\\btotal\\s*(energies)?\\s*access\\b", RegexOption.IGNORE_CASE)
        private val TOTAL = Regex("^\\s*total(\\s*energies)?\\b", RegexOption.IGNORE_CASE)

        fun bundled(brand: String?): Int? = when {
            brand == null -> null
            TOTAL_ACCESS.containsMatchIn(brand) -> R.drawable.logo_total_access
            TOTAL.containsMatchIn(brand) -> R.drawable.logo_totalenergies
            else -> null
        }
    }
}
