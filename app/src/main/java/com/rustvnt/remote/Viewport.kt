package com.rustvnt.remote

import kotlin.math.min

/** Platform independent transform shared by the display and input hit testing. */
data class Viewport(val left: Float, val top: Float, val width: Float, val height: Float) {
    fun point(x: Float, y: Float): Pair<Float, Float>? {
        if (width <= 0 || height <= 0 || x < left || x > left + width || y < top || y > top + height) return null
        return Pair(((x - left) / width).coerceIn(0f, 1f), ((y - top) / height).coerceIn(0f, 1f))
    }
    companion object {
        fun fit(viewW: Int, viewH: Int, remoteW: Int, remoteH: Int, zoom: Float = 1f, panX: Float = 0f, panY: Float = 0f): Viewport {
            if (viewW <= 0 || viewH <= 0 || remoteW <= 0 || remoteH <= 0) return Viewport(0f, 0f, 0f, 0f)
            val factor = min(viewW.toFloat() / remoteW, viewH.toFloat() / remoteH) * zoom
            val w = remoteW * factor; val h = remoteH * factor
            return Viewport((viewW - w) / 2 + panX, (viewH - h) / 2 + panY, w, h)
        }
    }
}
