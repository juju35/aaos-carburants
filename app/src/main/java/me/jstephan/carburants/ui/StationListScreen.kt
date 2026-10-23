package me.jstephan.carburants.ui

import android.location.Location
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarLocation
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Metadata
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.jstephan.carburants.Prefs
import me.jstephan.carburants.R
import me.jstephan.carburants.SortOrder
import me.jstephan.carburants.data.Fuel
import me.jstephan.carburants.data.Station
import me.jstephan.carburants.data.StationRepository
import me.jstephan.carburants.location.LocationTracker
import me.jstephan.carburants.location.hasLocationPermission
import me.jstephan.carburants.ui.Format.appendColored
import me.jstephan.carburants.ui.Format.appendDistance

/** Home screen: the nearest stations on the map, cheapest first, with the prices of the chosen fuels. */
class StationListScreen(carContext: CarContext) : Screen(carContext) {
    private val repo = StationRepository.get(carContext)
    private val prefs = Prefs(carContext)
    private var fuels = prefs.fuels
    private var sort = prefs.sort

    private var stations: List<Station>? = null
    private var radiusKm = StationRepository.RADIUS_KM
    private var error: Int? = null

    private var location: Location? = null
    private var fetchedAt: Location? = null
    private var fetchedTime = 0L
    private var fetchJob: Job? = null
    private var waitJob: Job? = null
    private val tracker = LocationTracker(carContext, ::onLocation)

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = startTracking()
            override fun onStop(owner: LifecycleOwner) {
                tracker.stop()
                waitJob?.cancel()
            }
        })
    }

    // ── Location and loading ─────────────────────────────────────────────────

    private fun startTracking() {
        if (!tracker.start()) {
            showError(R.string.error_permission)
            return
        }
        if (error == R.string.error_permission) {
            error = null
            invalidate()
        }
        if (location == null) {
            waitJob?.cancel()
            waitJob = lifecycleScope.launch {
                delay(FIX_TIMEOUT_MS)
                if (location == null) showError(R.string.error_location)
            }
        } else if (isStale(location!!)) {
            refresh()
        }
    }

    private fun onLocation(loc: Location) {
        location = loc
        waitJob?.cancel()
        if (error == R.string.error_location) error = null
        if (isStale(loc) && fetchJob?.isActive != true) refresh()
    }

    private fun isStale(loc: Location): Boolean {
        val last = fetchedAt ?: return true
        return last.distanceTo(loc) > REFETCH_METERS || SystemClock.elapsedRealtime() - fetchedTime > REFETCH_MS
    }

    private fun refresh() {
        val loc = location ?: return
        fetchJob?.cancel()
        fetchJob = lifecycleScope.launch {
            fetchedAt = loc
            fetchedTime = SystemClock.elapsedRealtime()
            try {
                val result = repo.nearby(loc.latitude, loc.longitude, fuels)
                stations = result.stations
                radiusKm = result.radiusKm
                error = null
                invalidate()
                enrichVisible()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Station search failed", e)
                fetchedAt = null // Try again on the next fix.
                if (stations == null) {
                    showError(R.string.error_network)
                } else {
                    CarToast.makeText(carContext, R.string.error_network, CarToast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Brands, names and logos of the stations on screen, then one redraw. */
    private suspend fun enrichVisible() {
        val shown = visible(stations ?: return)
        repo.enrich(shown)?.let { updated ->
            stations = stations?.map { updated[it.id] ?: it }
            invalidate()
        }
        if (repo.loadLogos(visible(stations ?: return))) invalidate()
    }

    private fun showError(message: Int) {
        error = message
        invalidate()
    }

    // ── Template ─────────────────────────────────────────────────────────────

    override fun onGetTemplate(): Template {
        val list = stations
        val err = error
        if (list == null && err != null) return errorTemplate(err)

        @Suppress("DEPRECATION") // MapWithContentTemplate needs car API 7; Android 12 hosts don't have it.
        val builder = PlaceListMapTemplate.Builder()
            .setTitle(carContext.getString(R.string.list_title))
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(actionStrip())
            .setCurrentLocationEnabled(true)
        if (list == null) builder.setLoading(true) else builder.setItemList(itemList(list))
        return builder.build()
    }

    private fun visible(list: List<Station>): List<Station> {
        val byDistance = compareBy<Station> { it.distanceKm ?: Double.MAX_VALUE }
        val sorted = when (sort) {
            // Cheapest of the chosen fuels first; shortages at the end, nearest first.
            SortOrder.PRICE -> list.sortedWith(compareBy<Station> { it.best(fuels)?.second ?: Double.MAX_VALUE }.then(byDistance))
            SortOrder.DISTANCE -> list.sortedWith(byDistance)
        }
        return sorted.take(contentLimit())
    }

    private fun contentLimit(): Int =
        if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_2) {
            runCatching {
                carContext.getCarService(ConstraintManager::class.java)
                    .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PLACE_LIST)
            }.getOrDefault(DEFAULT_LIMIT)
        } else {
            DEFAULT_LIMIT
        }

    private fun itemList(list: List<Station>): ItemList {
        val shown = visible(list)
        val cheapest = shown.mapNotNull { it.best(fuels)?.second }.minOrNull()
        val items = ItemList.Builder()
            .setNoItemsMessage(carContext.getString(R.string.no_station, fuelsLabel(), radiusKm))
        shown.forEach { items.addItem(row(it, cheapest)) }
        return items.build()
    }

    private fun row(station: Station, cheapest: Double?): Row {
        val image = CarIcon.Builder(IconCompat.createWithBitmap(repo.logos.stationBitmap(station, LOGO_PX))).build()
        val best = station.best(fuels)
        val isCheapest = best != null && best.second == cheapest

        // Line 1: distance · price of each chosen fuel sold here (the cheapest of the list in green).
        val line1 = SpannableStringBuilder()
        station.distanceKm?.let { line1.appendDistance(it).append(" · ") }
        val sold = fuels.filter { station.sells(it) }
        if (sold.isEmpty()) line1.append(carContext.getString(R.string.no_price))
        sold.forEachIndexed { index, f ->
            if (index > 0) line1.append(" · ")
            if (fuels.size > 1) line1.append(f.short).append(' ')
            val price = station.price(f)
            when {
                price != null && isCheapest && f == best?.first -> line1.appendColored(Format.price(price), CarColor.GREEN)
                price != null -> line1.append(Format.price(price))
                station.fuels[f]?.rupture != null ->
                    line1.appendColored(carContext.getString(R.string.rupture).lowercase(), CarColor.RED)
                else -> line1.append("–")
            }
        }

        // Line 2: town · opening status.
        val line2 = SpannableStringBuilder(station.prettyCity)
        Format.opening(carContext, station)?.let { opening ->
            if (line2.isNotEmpty()) line2.append(" · ")
            line2.appendColored(opening.text, opening.color)
        }

        val marker = PlaceMarker.Builder()
            .setIcon(image, PlaceMarker.TYPE_IMAGE)
            .apply { if (isCheapest) setColor(CarColor.GREEN) }
            .build()
        val place = Place.Builder(CarLocation.create(station.latitude, station.longitude)).setMarker(marker).build()

        return Row.Builder()
            .setTitle(station.name)
            .addText(line1)
            .addText(line2)
            .setImage(image, Row.IMAGE_TYPE_LARGE)
            .setMetadata(Metadata.Builder().setPlace(place).build())
            .setOnClickListener { screenManager.push(StationDetailScreen(carContext, station, fuels)) }
            .build()
    }

    private fun actionStrip(): ActionStrip {
        val fuelAction = Action.Builder()
            .setTitle(fuelsLabel())
            .setIcon(Format.icon(carContext, R.drawable.ic_fuel))
            .setOnClickListener(::pickFuel)
            .build()
        val sortAction = Action.Builder()
            .setTitle(carContext.getString(if (sort == SortOrder.PRICE) R.string.sort_price else R.string.sort_distance))
            .setIcon(Format.icon(carContext, R.drawable.ic_sort))
            .setOnClickListener(::toggleSort)
            .build()
        return ActionStrip.Builder().addAction(fuelAction).addAction(sortAction).build()
    }

    /** "Gazole", "E10, SP98", "3 carburants". */
    private fun fuelsLabel(): String = when (fuels.size) {
        1 -> fuels[0].label
        2 -> fuels.joinToString(", ") { it.short }
        else -> carContext.resources.getQuantityString(R.plurals.n_fuels, fuels.size, fuels.size)
    }

    private fun pickFuel() {
        screenManager.pushForResult(FuelPickerScreen(carContext, fuels)) { result ->
            @Suppress("UNCHECKED_CAST")
            val picked = result as? List<Fuel> ?: return@pushForResult
            if (picked == fuels) return@pushForResult
            fuels = picked
            prefs.fuels = picked
            stations = null
            fetchedAt = null
            refresh()
            invalidate()
        }
    }

    private fun toggleSort() {
        sort = if (sort == SortOrder.PRICE) SortOrder.DISTANCE else SortOrder.PRICE
        prefs.sort = sort
        invalidate()
        lifecycleScope.launch { enrichVisible() }
    }

    private fun errorTemplate(message: Int): Template {
        val retry = Action.Builder()
            .setTitle(carContext.getString(R.string.retry))
            .setBackgroundColor(CarColor.PRIMARY)
            .setOnClickListener {
                if (!hasLocationPermission(carContext)) {
                    // Comes back here once granted: onStart starts tracking again.
                    screenManager.push(PermissionScreen(carContext, root = false))
                    return@setOnClickListener
                }
                error = null
                tracker.stop()
                startTracking()
                if (location != null) refresh()
                invalidate()
            }
            .build()
        return MessageTemplate.Builder(carContext.getString(message))
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .setIcon(Format.icon(carContext, if (message == R.string.error_network) R.drawable.ic_fuel else R.drawable.ic_location_off))
            .addAction(retry)
            .build()
    }

    private companion object {
        const val TAG = "StationList"
        const val DEFAULT_LIMIT = 6
        const val LOGO_PX = 128
        const val FIX_TIMEOUT_MS = 20_000L
        // New search after 2 km or 10 minutes (the feed is refreshed every 10 minutes).
        const val REFETCH_METERS = 2_000f
        const val REFETCH_MS = 10 * 60 * 1000L
    }
}
