package com.sahara.user.ui.components

import kotlin.math.abs

/** Vertical single-finger drags scroll chat; accepted map gestures stay owned until lift. */
internal class MapGestureState(private val touchSlop: Float) {
    private var startX = 0f
    private var startY = 0f
    private var active = false

    fun begin(x: Float, y: Float) { startX = x; startY = y; active = false }
    fun capture(x: Float, y: Float, pointerCount: Int): Boolean {
        if (pointerCount > 1 || abs(x - startX) > touchSlop && abs(x - startX) > abs(y - startY)) active = true
        return active
    }
    fun end() { active = false }
}
