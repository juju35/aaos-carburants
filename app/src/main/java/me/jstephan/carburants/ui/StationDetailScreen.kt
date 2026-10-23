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

/** One station: price of your best fuel, your other fuels, the rest, address, hours, and "Y aller". */
class StationDetailScreen(
    carContext: CarContext,
    private val station: Station,
    /** The fuels chosen in the picker, never empty. */
    private val fuels: List<Fuel>,
) : Screen(carContext) {
    private val repo = StationRepository.get(carContext)

    /** Headline fuel: the cheapest in stock among yours, else the first one sold here. */
    private val main: Fuel = station.best(fuels)?.first ?: fuels.firstOrNull { station.sells(it) } ?: fuels.first()

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

    /** "E10 · 1,749 €/L", then your other fuels sold here, then the price's age. */
    private fun priceRow(): Row {
        val fp = station.fuels[main]
        val row = Row.Builder().setImage(bitmapIcon(Badges.fuelBadge(main, BADGE_PX)), Row.IMAGE_TYPE_SMALL)
        val price = station.price(main)
        val yours = fuels.filter { it != main }.mapNotNull(::pricePart)
        if (yours.isNotEmpty()) row.addText(join(yours))
        if (price != null) {
            row.setTitle(carContext.getString(R.string.price_per_litre, main.label, Format.price(price)))
            fp?.updated?.let { row.addText(carContext.getString(R.string.updated, Format.relative(it))) }
        } else if (fp?.rupture != null) {
            row.setTitle("${main.label} · ${carContext.getString(R.string.rupture)}")
            val since = fp.ruptureSince
            val text = if (since != null) {
                carContext.getString(R.string.rupture_since, Format.relative(since))
            } else {
                carContext.getString(R.string.rupture)
            }
            row.addText(SpannableStringBuilder().appendColored(text, CarColor.RED))
        } else {
            row.setTitle("${main.label} · ${carContext.getString(R.string.no_price)}")
        }
        return row.build()
    }

    /** The fuels you didn't choose: "SP98 1,899 € · E85 0,829 €", three per line, at most two lines. */
    private fun otherFuelsRow(): Row? {
        val others = Fuel.entries.filter { it !in fuels }.mapNotNull(::pricePart)
        if (others.isEmpty()) return null
        val row = Row.Builder().setTitle(carContext.getString(R.string.other_fuels))
        others.chunked(3).take(2).forEach { row.addText(join(it)) }
        return row.build()
    }

    /** "SP98 1,899 €", "E85 rupture" (red), null when not sold here. */
    private fun pricePart(fuel: Fuel): CharSequence? {
        val fp = station.fuels[fuel] ?: return null
        if (fp.rupture == RUPTURE_DEFINITIVE) return null
        val price = station.price(fuel)
        val text = SpannableStringBuilder(fuel.label).append(' ')
        when {
            price != null -> text.append(Format.price(price))
            fp.rupture != null -> text.appendColored(carContext.getString(R.string.rupture).lowercase(), CarColor.RED)
            else -> text.append("–")
        }
        return text
    }

    private fun join(parts: List<CharSequence>): CharSequence {
        val line = SpannableStringBuilder()
        parts.forEachIndexed { i, part ->
            if (i > 0) line.append("  ·  ")
            line.append(part)
        }
        return line
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
