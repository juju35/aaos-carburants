package me.jstephan.carburants.ui

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.jstephan.carburants.R
import me.jstephan.carburants.data.Town
import me.jstephan.carburants.data.TownApi

/**
 * Town search with suggestions as you type (name or postcode). Returns the [Town] picked with
 * setResult, or [CurrentPosition] to go back to the stations around the car: with the field's
 * clear button (or "Ma position") while a town is shown.
 */
class TownSearchScreen(carContext: CarContext, private val current: Town?) : Screen(carContext) {
    /** Result: leave the town, back to the car's position. */
    object CurrentPosition

    private var query = current?.name.orEmpty()
    private var towns: List<Town> = emptyList()
    private var failed = false
    private var job: Job? = null

    /** Text whose suggestions are shown, so the same text isn't searched again. */
    private var searched: String? = null

    init {
        // The town shown is in the field: its suggestions too.
        if (query.length >= MIN_CHARS) onText(query, 0)
    }

    override fun onGetTemplate(): Template {
        val callback = object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) = onText(searchText, SUGGEST_DELAY_MS)

            override fun onSearchSubmitted(searchText: String) {
                towns.firstOrNull()?.let(::pick) ?: onText(searchText, 0)
            }
        }
        val builder = SearchTemplate.Builder(callback)
            .setHeaderAction(Action.BACK)
            .setSearchHint(carContext.getString(R.string.search_hint))
            .setInitialSearchText(query)
            .setShowKeyboardByDefault(true)
        // Loading on the first draw with a town in the field; then the list, kept while the next
        // suggestions load (no redraw per key).
        if (job?.isActive == true && towns.isEmpty()) builder.setLoading(true) else builder.setItemList(itemList())
        if (current != null) {
            val myPosition = Action.Builder()
                .setTitle(carContext.getString(R.string.my_position))
                .setIcon(Format.icon(carContext, R.drawable.ic_my_location))
                .setOnClickListener {
                    setResult(CurrentPosition)
                    screenManager.pop()
                }
                .build()
            builder.setActionStrip(ActionStrip.Builder().addAction(myPosition).build())
        }
        return builder.build()
    }

    private fun itemList(): ItemList {
        val list = ItemList.Builder()
        val message = when {
            failed -> R.string.search_failed
            query.trim().length < MIN_CHARS -> R.string.search_prompt
            else -> R.string.no_town
        }
        list.setNoItemsMessage(carContext.getString(message))
        towns.forEach { town ->
            val details = listOf(town.postcode, town.department).filter { it.isNotEmpty() }.joinToString(" · ")
            val row = Row.Builder()
                .setTitle(town.name)
                .setImage(Format.icon(carContext, R.drawable.ic_place), Row.IMAGE_TYPE_ICON)
                .setOnClickListener { pick(town) }
            if (details.isNotEmpty()) row.addText(details)
            list.addItem(row.build())
        }
        return list.build()
    }

    /** Suggestions for [text], after [delayMs] without typing (one request per pause, not per key). */
    private fun onText(text: String, delayMs: Long) {
        // The field's clear button empties it at once (backspace goes one letter at a time):
        // back to the stations around the car when a town is shown.
        if (text.isEmpty() && query.length > 1 && current != null) {
            setResult(CurrentPosition)
            screenManager.pop()
            return
        }
        if (text == searched && job?.isActive != true) {
            query = text
            return
        }
        query = text
        job?.cancel()
        if (text.trim().length < MIN_CHARS) {
            searched = null
            // Redraw only when something goes away: a redraw per key can break the typing.
            if (towns.isNotEmpty() || failed) {
                towns = emptyList()
                failed = false
                invalidate()
            }
            return
        }
        job = lifecycleScope.launch {
            delay(delayMs)
            try {
                towns = withContext(Dispatchers.IO) { TownApi.search(text) }
                searched = text
                failed = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Town search failed", e)
                towns = emptyList()
                failed = true
            }
            invalidate()
        }
    }

    private fun pick(town: Town) {
        setResult(town)
        screenManager.pop()
    }

    private companion object {
        const val TAG = "TownSearch"
        const val MIN_CHARS = 2
        const val SUGGEST_DELAY_MS = 300L
    }
}
