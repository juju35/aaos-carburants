package me.jstephan.carburants.ui

import android.Manifest
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import me.jstephan.carburants.R

/**
 * Asks for the location permission. [root]: first screen of the session, replaced by the
 * station list once granted; otherwise it just goes back to the list.
 */
class PermissionScreen(carContext: CarContext, private val root: Boolean = true) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val allow = Action.Builder()
            .setTitle(carContext.getString(R.string.allow))
            .setBackgroundColor(CarColor.PRIMARY)
            .setOnClickListener(::request)
            .build()
        return MessageTemplate.Builder(carContext.getString(R.string.permission_message))
            .setTitle(carContext.getString(R.string.permission_title))
            .setHeaderAction(if (root) Action.APP_ICON else Action.BACK)
            .setIcon(Format.icon(carContext, R.drawable.ic_place))
            .addAction(allow)
            .build()
    }

    private fun request() {
        val permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        carContext.requestPermissions(permissions) { granted, _ ->
            if (granted.isEmpty()) {
                CarToast.makeText(carContext, R.string.permission_denied, CarToast.LENGTH_LONG).show()
            } else if (root) {
                screenManager.push(StationListScreen(carContext))
                finish()
            } else {
                screenManager.pop()
            }
        }
    }
}
