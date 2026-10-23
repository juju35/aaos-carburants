package me.jstephan.carburants.ui

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import androidx.core.graphics.drawable.IconCompat
import me.jstephan.carburants.R
import me.jstephan.carburants.data.Fuel

/**
 * The fuels your car takes, as switches. Changes apply at once and go back to the list
 * with setResult (a List<Fuel> in the enum order). At least one stays on.
 */
class FuelPickerScreen(carContext: CarContext, initial: List<Fuel>) : Screen(carContext) {
    private val chosen = initial.toMutableSet()

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        Fuel.entries.forEach { fuel ->
            val icon = CarIcon.Builder(IconCompat.createWithBitmap(Badges.fuelBadge(fuel, BADGE_PX))).build()
            val subtitle = carContext.getString(fuel.description) + if (fuel.code.isNotEmpty()) " · ${fuel.code}" else ""
            val toggle = Toggle.Builder { checked -> onToggle(fuel, checked) }
                .setChecked(fuel in chosen)
                .build()
            list.addItem(
                Row.Builder()
                    .setTitle(fuel.label)
                    .addText(subtitle)
                    .setImage(icon, Row.IMAGE_TYPE_SMALL)
                    .setToggle(toggle)
                    .build(),
            )
        }
        return ListTemplate.Builder()
            .setSingleList(list.build())
            .setTitle(carContext.getString(R.string.choose_fuel))
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun onToggle(fuel: Fuel, checked: Boolean) {
        if (checked) {
            chosen += fuel
        } else if (chosen.size > 1) {
            chosen -= fuel
        } else {
            CarToast.makeText(carContext, R.string.keep_one_fuel, CarToast.LENGTH_SHORT).show()
        }
        setResult(Fuel.entries.filter { it in chosen })
        // Redraw: puts the switch back on when the last fuel couldn't be turned off.
        invalidate()
    }

    private companion object {
        const val BADGE_PX = 128
    }
}
