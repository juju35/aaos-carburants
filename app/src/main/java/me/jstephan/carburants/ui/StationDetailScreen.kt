package me.jstephan.carburants.ui

import android.content.Intent
import android.net.Uri
import android.text.SpannableStringBuilder
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.graphics.drawable.IconCompat
import me.jstephan.carburants.R
import me.jstephan.carburants.data.Fuel
import me.jstephan.carburants.data.RUPTURE_DEFINITIVE
import me.jstephan.carburants.data.Station
import me.jstephan.carburants.data.StationRepository
import me.jstephan.carburants.data.formatCity
import me.jstephan.carburants.ui.Format.appendColored
import me.jstephan.carburants.ui.Format.appendDistance

/** One station: price of the chosen fuel, other fuels, address, hours, and "Y aller". */
class StationDetailScreen(
    carContext: CarContext,
    private val station: Station,
    private val fuel: Fuel,
) : Screen(carContext) {
    private val repo = StationRepository.get(carContext)

    override fun onGetTemplate(): Template {
        val pane = Pane.Builder()
            .addRow(priceRow())
        otherFuelsRow()?.let(pane::addRow)
        pane.addRow(addressRow())
        pane.addRow(hoursRow())
        pane.addAction(navigateAction())
        if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_4) {
            pane.setImage(bitmapIcon(repo.logos.stationBitmap(station, LOGO_PX)))
        }
        return PaneTemplate.Builder(pane.build())
            .setTitle(station.name)
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun priceRow(): Row {
        val fp = station.fuels[fuel]
        val row = Row.Builder().setImage(bitmapIcon(Badges.fuelBadge(fuel, BADGE_PX)), Row.IMAGE_TYPE_SMALL)
        val price = station.price(fuel)
        if (price != null) {
            row.setTitle(carContext.getString(R.string.price_per_litre, fuel.label, Format.price(price)))
            fp?.updated?.let { row.addText(carContext.getString(R.string.updated, Format.relative(it))) }
        } else if (fp?.rupture != null) {
            row.setTitle("${fuel.label} · ${carContext.getString(R.string.rupture)}")
            val since = fp.ruptureSince
            val text = if (since != null) {
                carContext.getString(R.string.rupture_since, Format.relative(since))
            } else {
                carContext.getString(R.string.rupture)
            }
            row.addText(SpannableStringBuilder().appendColored(text, CarColor.RED))
        } else {
            row.setTitle("${fuel.label} · ${carContext.getString(R.string.no_price)}")
        }
        return row.build()
    }

    /** "SP98 1,899 € · E10 1,799 €", three per line, at most two lines. */
    private fun otherFuelsRow(): Row? {
        val others = Fuel.entries.filter { it != fuel }.mapNotNull { f ->
            val fp = station.fuels[f] ?: return@mapNotNull null
            if (fp.rupture == RUPTURE_DEFINITIVE) return@mapNotNull null
            val price = station.price(f)
            val text = SpannableStringBuilder(f.label).append(' ')
            when {
                price != null -> text.append(Format.price(price))
                fp.rupture != null -> text.appendColored(carContext.getString(R.string.rupture).lowercase(), CarColor.RED)
                else -> text.append("–")
            }
            text
        }
        if (others.isEmpty()) return null
        val row = Row.Builder().setTitle(carContext.getString(R.string.other_fuels))
        others.chunked(3).take(2).forEach { chunk ->
            val line = SpannableStringBuilder()
            chunk.forEachIndexed { i, part ->
                if (i > 0) line.append("  ·  ")
                line.append(part)
            }
            row.addText(line)
        }
        return row.build()
    }

    private fun addressRow(): Row {
        val address = formatCity(station.address).ifEmpty { station.prettyCity }
        val line = SpannableStringBuilder("${station.postcode} ${station.prettyCity}".trim())
        station.distanceKm?.let { line.append(" · ").appendDistance(it) }
        return Row.Builder()
            .setTitle(address)
            .addText(line)
            .setImage(Format.icon(carContext, R.drawable.ic_place), Row.IMAGE_TYPE_ICON)
            .build()
    }

    private fun hoursRow(): Row {
        val opening = Format.opening(carContext, station)
        // Plain title: hosts only take colour spans in a row's text lines.
        val title = opening?.text ?: carContext.getString(R.string.hours_unknown)
        val row = Row.Builder()
            .setTitle(title)
            .setImage(Format.icon(carContext, R.drawable.ic_schedule), Row.IMAGE_TYPE_ICON)
        val extras = buildList {
            if (station.highway) add(carContext.getString(R.string.highway))
            if (station.automate24 && opening?.state != Format.OpenState.AUTOMATE) add(carContext.getString(R.string.automate))
            addAll(station.services.take(3))
        }
        if (extras.isNotEmpty()) row.addText(extras.joinToString(" · "))
        return row.build()
    }

    private fun navigateAction(): Action {
        val action = Action.Builder()
            .setTitle(carContext.getString(R.string.navigate))
            .setIcon(Format.icon(carContext, R.drawable.ic_navigate))
            .setBackgroundColor(CarColor.PRIMARY)
            .setOnClickListener(::navigate)
        if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_4) action.setFlags(Action.FLAG_PRIMARY)
        return action.build()
    }

    /** Hands the destination to the car's navigation app (Google Maps on Google built-in). */
    private fun navigate() {
        val lat = station.latitude
        val lon = station.longitude
        val uri = Uri.parse("geo:$lat,$lon?q=" + Uri.encode("$lat,$lon(${station.name})"))
        try {
            carContext.startCarApp(Intent(CarContext.ACTION_NAVIGATE, uri))
        } catch (e: Exception) {
            Log.w(TAG, "No navigation app", e)
            CarToast.makeText(carContext, R.string.navigation_unavailable, CarToast.LENGTH_LONG).show()
        }
    }

    private fun bitmapIcon(bitmap: android.graphics.Bitmap): CarIcon =
        CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()

    private companion object {
        const val TAG = "StationDetail"
        const val LOGO_PX = 256
        const val BADGE_PX = 128
    }
}
