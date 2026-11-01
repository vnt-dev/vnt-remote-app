package com.rustvnt.remote

/** Semantic input sink; local view navigation never enters this lane. */
interface RemoteInput {
    val enabled: Boolean
    fun move(x: Float, y: Float)
    fun button(button: Int, down: Boolean)
    fun wheel(dx: Float, dy: Float)
    fun click(button: Int = 1)
    fun flush()
    fun release()
}
