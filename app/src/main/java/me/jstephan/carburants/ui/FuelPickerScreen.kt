package me.jstephan.carburants.ui

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import me.jstephan.carburants.R
import me.jstephan.carburants.data.Fuel

/** Radio list of the six fuels; the choice is returned with setResult. */
class FuelPickerScreen(carContext: CarContext, private val current: Fuel) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        Fuel.entries.forEach { fuel ->
            val icon = CarIcon.Builder(IconCompat.createWithBitmap(Badges.fuelBadge(fuel, BADGE_PX))).build()
            val subtitle = carContext.getString(fuel.description) + if (fuel.code.isNotEmpty()) " · ${fuel.code}" else ""
            list.addItem(
                Row.Builder()
                    .setTitle(fuel.label)
                    .addText(subtitle)
                    .setImage(icon, Row.IMAGE_TYPE_SMALL)
                    .build(),
            )
        }
        list.setSelectedIndex(Fuel.entries.indexOf(current))
        list.setOnSelectedListener { index ->
            setResult(Fuel.entries[index])
            screenManager.pop()
        }
        return ListTemplate.Builder()
            .setSingleList(list.build())
            .setTitle(carContext.getString(R.string.choose_fuel))
            .setHeaderAction(Action.BACK)
            .build()
    }

    private companion object {
        const val BADGE_PX = 128
    }
}
