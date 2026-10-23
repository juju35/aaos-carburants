package me.jstephan.carburants

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import me.jstephan.carburants.location.hasLocationPermission
import me.jstephan.carburants.ui.PermissionScreen
import me.jstephan.carburants.ui.StationListScreen

class CarburantsService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = CarburantsSession()
}

class CarburantsSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen =
        if (hasLocationPermission(carContext)) StationListScreen(carContext) else PermissionScreen(carContext)
}
