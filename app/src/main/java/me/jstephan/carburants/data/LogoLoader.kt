package me.jstephan.carburants.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.LruCache
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.jstephan.carburants.R
import me.jstephan.carburants.ui.Badges
import java.io.File
import java.security.MessageDigest

/** Station logos: shipped with the app, else downloaded from Wikimedia Commons, else an initials badge. */
class LogoLoader internal constructor(private val context: Context) {
    private val shipped = BrandLogos(context)
    private val downloaded = LruCache<String, Bitmap>(48)
    private val tiles = LruCache<String, Bitmap>(64)
    private val dir = File(context.cacheDir, "logos").apply { mkdirs() }

    private fun shippedLogo(station: Station) = shipped.find(station.brand, station.name)

    /** Logo URL to download for this station, null when shipped or unknown. */
    fun remoteUrl(station: Station): String? = if (shippedLogo(station) != null) null else station.logoUrl

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
        val logo = shippedLogo(station)
        val url = station.logoUrl
        val source = when {
            logo != null -> "res:${logo.res}"
            url != null && downloaded.get(url) != null -> url
            else -> "initials:${station.brand ?: station.name}"
        }
        val key = "$size|$source"
        tiles.get(key)?.let { return it }
        val bitmap = when {
            logo != null -> BitmapFactory.decodeResource(context.resources, logo.res)?.let {
                Badges.logoTile(it, size, if (logo.dark) DARK_TILE else Color.WHITE)
            }
            url != null -> downloaded.get(url)?.let { Badges.logoTile(it, size, Color.WHITE) }
            else -> null
        } ?: fallback(station, size)
        tiles.put(key, bitmap)
        return bitmap
    }

    /** The brand's initials, or a pump when the brand is unknown (initials of an address mean nothing). */
    private fun fallback(station: Station, size: Int): Bitmap {
        val brand = station.brand
        if (brand != null) return Badges.initialsBadge(brand, size)
        val pump = ContextCompat.getDrawable(context, R.drawable.ic_fuel)
        return if (pump != null) Badges.genericBadge(pump, size) else Badges.initialsBadge(station.name, size)
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

    private companion object {
        // Tile for white logos (Roady).
        const val DARK_TILE = 0xFF202124.toInt()
    }
}
