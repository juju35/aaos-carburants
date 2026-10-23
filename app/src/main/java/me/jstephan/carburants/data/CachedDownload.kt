package me.jstephan.carburants.data

import android.util.Log
import java.io.File

/** A file downloaded at most once per [maxAgeMs] and kept in [file]: an outage only means the cached copy. */
internal class CachedDownload(private val file: File, private val url: String, private val maxAgeMs: Long) {

    /** Blocking. [parse] may throw; a download parsed as empty ([isEmpty]) is not kept. */
    fun <T> load(parse: (String) -> T, isEmpty: (T) -> Boolean): T? {
        val stale = !file.exists() || System.currentTimeMillis() - file.lastModified() > maxAgeMs
        if (stale) {
            try {
                val bytes = Http.get(url, timeoutMs = 30_000)
                val parsed = parse(bytes.decodeToString())
                if (!isEmpty(parsed)) {
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.writeBytes(bytes)
                    tmp.renameTo(file)
                    return parsed
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not download ${file.name}, using the cached copy", e)
            }
        }
        return if (file.exists()) runCatching { parse(file.readText()) }.getOrNull() else null
    }

    private companion object {
        const val TAG = "CachedDownload"
    }
}
