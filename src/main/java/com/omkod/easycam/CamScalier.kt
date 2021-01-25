package com.omkod.easycam

import android.graphics.Rect
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class CamScalier(val zoomMax: Float, private val maxX: Int, private val maxY: Int) {

    val currentView: Rect

    private var centerX: Int
    private var centerY: Int
    private var width: Int
    private var height: Int
    private var currentZoom: Float

    fun getCurrentZoom() = currentZoom

    fun setZoom(newZoom: Float) {
        currentZoom = min(zoomMax, max(MIN_ZOOM, newZoom))
        val newWidthHalf = floor(maxX / currentZoom / 2.0).toInt()
        val newHeightHalf = floor(maxY / currentZoom / 2.0).toInt()
        var xTempCenter = centerX
        var yTempCenter = centerY

        if (centerX + newWidthHalf > maxX) {
            xTempCenter = maxX - newWidthHalf
        } else if (centerX - newWidthHalf < 0) {
            xTempCenter = newWidthHalf
        }
        if (centerY + newHeightHalf > maxY) {
            yTempCenter = maxY - newHeightHalf
        } else if (centerY - newHeightHalf < 0) {
            yTempCenter = newHeightHalf
        }
        currentView[xTempCenter - newWidthHalf, yTempCenter - newHeightHalf, xTempCenter + newWidthHalf] = yTempCenter + newHeightHalf
        width = currentView.width()
        height = currentView.height()
        centerX = currentView.centerX()
        centerY = currentView.centerY()
    }

    init {
        currentView = Rect(MIN_X, MIN_Y, maxX, maxY)
        currentZoom = MIN_ZOOM
        width = currentView.width()
        height = currentView.height()
        centerX = currentView.centerX()
        centerY = currentView.centerY()
    }

    companion object {
        const val MIN_ZOOM = 1.0f
        const val MIN_X = 0
        const val MIN_Y = 0
    }
}