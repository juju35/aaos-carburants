package me.jstephan.carburants.ui

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.ForegroundCarColorSpan
import androidx.core.graphics.drawable.IconCompat
import me.jstephan.carburants.R
import me.jstephan.carburants.data.DayHours
import me.jstephan.carburants.data.Station
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

internal object Format {
    private val FR = Locale.FRANCE

    fun price(value: Double): String = String.format(FR, "%.3f €", value)

    /**
     * "12 min", "3 h", "2 j" since [instant]; null under a minute. In French whatever the car's language,
     * like the rest of the app. A station clock ahead of time counts as now.
     */
    private fun elapsed(instant: Instant): String? {
        val minutes = maxOf(0L, (System.currentTimeMillis() - instant.toEpochMilli()) / 60_000)
        return when {
            minutes < 1 -> null
            minutes < 60 -> "$minutes min"
            minutes < 48 * 60 -> "${minutes / 60} h"
            else -> "${minutes / (24 * 60)} j"
        }
    }

    /** "il y a 12 min", "à l'instant". */
    fun ago(context: Context, instant: Instant): String =
        elapsed(instant)?.let { context.getString(R.string.ago, it) } ?: context.getString(R.string.just_now)

    /** "12 min", "2 j", at least "1 min". */
    fun since(instant: Instant): String = elapsed(instant) ?: "1 min"

    /** Distance for a DistanceSpan: metres under 1 km, km with one decimal under 10 km. */
    fun distance(km: Double): Distance = when {
        km < 1 -> Distance.create(Math.round(km * 1000 / 10) * 10.0, Distance.UNIT_METERS)
        km < 10 -> Distance.create(Math.round(km * 10) / 10.0, Distance.UNIT_KILOMETERS_P1)
        else -> Distance.create(Math.round(km).toDouble(), Distance.UNIT_KILOMETERS)
    }

    fun SpannableStringBuilder.appendDistance(km: Double): SpannableStringBuilder =
        // Exclusive: the host replaces the whole span with the distance, text appended after it must stay out.
        append(" ", DistanceSpan.create(distance(km)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

    fun SpannableStringBuilder.appendColored(text: CharSequence, color: CarColor): SpannableStringBuilder =
        append(text, ForegroundCarColorSpan.create(color), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

    fun icon(context: Context, res: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(context, res)).setTint(CarColor.DEFAULT).build()

    // ── Opening status, as carbOpening() in the card ─────────────────────────

    enum class OpenState { OPEN, CLOSED, ALWAYS, AUTOMATE }

    data class Opening(val state: OpenState, val text: String) {
        val color: CarColor
            get() = when (state) {
                OpenState.OPEN, OpenState.ALWAYS -> CarColor.GREEN
                OpenState.AUTOMATE -> CarColor.YELLOW
                OpenState.CLOSED -> CarColor.RED
            }
    }

    private fun minutes(hhmm: String): Int {
        val parts = hhmm.split(":")
        return (parts.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    /** Stations are in France: their hours are read in French time, whatever the car's time zone. */
    private val STATION_ZONE: ZoneId = ZoneId.of("Europe/Paris")

    fun opening(context: Context, station: Station, now: LocalDateTime = LocalDateTime.now(STATION_ZONE)): Opening? {
        val hours = station.hours
        val day = now.dayOfWeek.value
        val current = now.hour * 60 + now.minute
        if (alwaysOpen(hours)) return Opening(OpenState.ALWAYS, context.getString(R.string.always_open))

        val today = hours.firstOrNull { it.day == day }?.takeIf { !it.closed }
        today?.ranges?.firstOrNull { current >= minutes(it.first) && current < minutes(it.second) }?.let {
            return Opening(OpenState.OPEN, context.getString(R.string.open_until, it.second))
        }
        if (station.automate24) return Opening(OpenState.AUTOMATE, context.getString(R.string.automate))
        if (hours.isEmpty()) return null
        return Opening(OpenState.CLOSED, nextOpening(context, hours, today, day, current))
    }

    /** Open every day from 00:00 to 23:59 or later. */
    private fun alwaysOpen(hours: List<DayHours>): Boolean =
        hours.size == 7 &&
            hours.all { d -> !d.closed && d.ranges.any { it.first == "00:00" && minutes(it.second) >= 23 * 60 + 59 } }

    /** "Fermé · ouvre à 14:00" later today, else on the next open day. */
    private fun nextOpening(context: Context, hours: List<DayHours>, today: DayHours?, day: Int, current: Int): String {
        today?.ranges?.firstOrNull { minutes(it.first) > current }?.let {
            return context.getString(R.string.closed_opens_at, it.first)
        }
        for (k in 1..7) {
            val next = hours.firstOrNull { it.day == ((day - 1 + k) % 7) + 1 } ?: continue
            if (next.closed || next.ranges.isEmpty()) continue
            val time = next.ranges.first().first
            if (k == 1) return context.getString(R.string.closed_opens_tomorrow, time)
            val dayName = DayOfWeek.of(next.day).getDisplayName(TextStyle.SHORT, FR)
            return context.getString(R.string.closed_opens_day, dayName, time)
        }
        return context.getString(R.string.closed)
    }
}
