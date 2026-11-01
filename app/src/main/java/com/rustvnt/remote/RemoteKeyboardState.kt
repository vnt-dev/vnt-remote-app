package com.rustvnt.remote

/** Selection is local only. No key is held remotely while composing a shortcut. */
class RemoteKeyboardState {
    data class Stroke(val scan: Keymap.Scan, val down: Boolean)
    var composing = false
        private set
    private val selection = linkedSetOf<Keymap.Scan>()
    val selected: List<Keymap.Scan> get() = selection.toList()
    fun begin() { reset(); composing = true }
    fun reset() { composing = false; selection.clear() }
    fun tap(scan: Keymap.Scan): List<Stroke> {
        if (composing) {
            if (!selection.add(scan)) selection.remove(scan)
            return emptyList()
        }
        return strokes(listOf(scan))
    }
    fun send(): List<Stroke> {
        if (!composing || selection.isEmpty()) return emptyList()
        val result = strokes(selected)
        reset()
        return result
    }
    private fun strokes(keys: List<Keymap.Scan>) = keys.map { Stroke(it, true) } + keys.asReversed().map { Stroke(it, false) }

    companion object {
        fun metaLabel(system: String) = when (system) { "macos" -> "Command"; "windows" -> "Win"; else -> "Super" }
        fun altLabel(system: String) = if (system == "macos") "Option" else "Alt"
    }
}
