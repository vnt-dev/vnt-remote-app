package com.rustvnt.remote

import android.view.Choreographer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Coalesce adjacent pointer motion only; clicks and key releases are never discarded. */
class InputBatch(private val model: RemoteModel) : RemoteInput {
    override val enabled: Boolean get() = model.canInput
    private val buffer = ByteBuffer.allocateDirect(64 * 24).order(ByteOrder.LITTLE_ENDIAN)
    private var count = 0
    private var scheduled = false
    private var lastKind = -1
    private var route = ""
    private val callback = Choreographer.FrameCallback { scheduled = false; flush() }
    private fun add(kind: Int, a: Int = 0, b: Int = 0, c: Int = 0, x: Float = 0f, y: Float = 0f) {
        if (!model.canInput) { clear(); return }
        val screen = model.screen ?: return
        val current = "${model.epoch}:${model.activeStream}:${screen.optInt("generation")}:${model.revision}"
        if (route != current) clear()
        route = current
        if (kind == 0 && lastKind == 0 && count > 0) { count--; buffer.position(count * 24) }
        buffer.putInt(kind).putInt(a).putInt(b).putInt(c).putFloat(x).putFloat(y)
        count++; lastKind = kind
        if (count == 64 || kind != 0) flush()
        else if (!scheduled) { scheduled = true; Choreographer.getInstance().postFrameCallback(callback) }
    }
    override fun move(x: Float, y: Float) = add(0, x = x.coerceIn(0f, 1f), y = y.coerceIn(0f, 1f))
    override fun button(button: Int, down: Boolean) = add(1, a = button, b = if (down) 1 else 0)
    override fun wheel(dx: Float, dy: Float) = add(2, x = dx.coerceIn(-100f, 100f), y = dy.coerceIn(-100f, 100f))
    fun key(scan: Int, extended: Boolean, down: Boolean) = add(3, a = scan, b = if (extended) 1 else 0, c = if (down) 1 else 0)
    override fun click(button: Int) { button(button, true); button(button, false) }
    override fun flush() {
        if (count == 0) return
        val screen = model.screen
        val current = "${model.epoch}:${model.activeStream}:${screen?.optInt("generation")}:${model.revision}"
        if (model.canInput && screen != null && current == route) {
            try { model.bridge.input(model.epoch, model.activeStream, screen.optInt("generation"), model.revision, buffer, count) }
            catch (e: RuntimeException) { model.disconnect() }
        }
        count = 0; lastKind = -1; buffer.clear()
    }
    fun clear() { count = 0; lastKind = -1; buffer.clear(); if (scheduled) Choreographer.getInstance().removeFrameCallback(callback); scheduled = false }
    override fun release() { flush(); clear(); if (model.info != null && model.controlOnline) model.command("resetInput") }
}
