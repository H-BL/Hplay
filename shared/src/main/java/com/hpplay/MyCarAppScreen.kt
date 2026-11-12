package com.hpplay

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import com.hpplay.shared.R

class MyCarAppScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        return MessageTemplate.Builder(
            carContext.getString(R.string.car_app_transport_missing),
        )
            .setHeaderAction(Action.APP_ICON)
            .setTitle(carContext.getString(R.string.car_app_title))
            .build()
    }
}
