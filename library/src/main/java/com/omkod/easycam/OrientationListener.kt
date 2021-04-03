package com.omkod.easycam

import android.content.Context
import android.hardware.SensorManager
import android.view.OrientationEventListener

class OrientationListener(context: Context) : OrientationEventListener(context, SensorManager.SENSOR_DELAY_UI) {

    var currentOrientation: Int = 0

    override fun onOrientationChanged(orientation: Int) {
        if (orientation < 0) {
            return
        }

        val curOrientation: Int

        if (orientation <= 45) {
            curOrientation = ORIENTATION_PORTRAIT
        } else if (orientation <= 135) {
            curOrientation = ORIENTATION_LANDSCAPE_REVERSE
        } else if (orientation <= 225) {
            curOrientation = ORIENTATION_PORTRAIT_REVERSE
        } else if (orientation <= 315) {
            curOrientation = ORIENTATION_LANDSCAPE
        } else {
            curOrientation = ORIENTATION_PORTRAIT
        }
        if (curOrientation != currentOrientation) {
            currentOrientation = curOrientation
        }
    }

    companion object {
        val ORIENTATION_LANDSCAPE = 0
        val ORIENTATION_PORTRAIT = 90
        val ORIENTATION_LANDSCAPE_REVERSE = 180
        val ORIENTATION_PORTRAIT_REVERSE = 270
    }
}