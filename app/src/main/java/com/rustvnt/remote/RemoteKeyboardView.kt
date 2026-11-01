package com.rustvnt.remote

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*

/** A sibling overlay: opening the keyboard never resizes the decoded desktop. */
class RemoteKeyboardView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private val state = RemoteKeyboardState()
    private val keys = mutableListOf<Pair<TextView, Keymap.Scan>>()
    private val metaKeys = mutableListOf<TextView>()
    private val altKeys = mutableListOf<TextView>()
    private data class Touch(val view: TextView, val scan: Keymap.Scan, val composing: Boolean)
    private val touches = linkedMapOf<Int, Touch>()
    private var keyGesture = false
    private var ignoreKeyGesture = false
    private var ready = false
    private var release: () -> Unit = {}
    private val combo: TextView
    private var allowed: () -> Boolean = { false }
    private var route: () -> String = { "" }
    private var send: (Keymap.Scan, Boolean) -> Unit = { _, _ -> }
    private var lastRoute = ""
    private var available = false
    private var system = ""

    init {
        orientation = VERTICAL
        isClickable = true
        setBackgroundColor(Color.parseColor("#BF161A22"))
        setPadding(dp(4), dp(2), dp(4), dp(4))
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        combo = control("开启组合键") {
            sync(system)
            if (available) {
                when {
                    !state.composing -> { releaseTouches(); state.begin() }
                    state.selected.isEmpty() -> state.reset()
                    else -> emit(state.send())
                }
                refresh()
            }
        }
        header.addView(combo, LayoutParams(dp(128), dp(42)))
        header.addView(TextView(context).apply {
            text = "支持多指按键 · 组合键模式可选键发送"
            textSize = 11f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER
        }, LayoutParams(0, dp(42), 1f))
        header.addView(control("关闭") { cancelComposition(); close?.invoke() }, LayoutParams(dp(56), dp(42)))
        addView(header)
        val rows = LinearLayout(context).apply { orientation = VERTICAL }
        fun row(vararg definitions: Triple<String, Keymap.Scan, Float>) {
            val line = LinearLayout(context)
            definitions.forEach { (label, scan, weight) ->
                val key = control(label) {
                    sync(system)
                    if (available) { emit(state.tap(scan)); refresh() }
                }
                key.tag = "key:${scan.code}:${scan.extended}"
                keys += key to scan
                if (label == "Meta") metaKeys += key
                if (label == "Alt") altKeys += key
                line.addView(key, LayoutParams(0, dp(38), weight).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            }
            rows.addView(line, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        fun k(label: String, code: Int, extended: Boolean = false, weight: Float = 1f) = Triple(label, Keymap.Scan(code, extended), weight)
        row(k("Esc", 1), *Array(12) { i -> k("F${i + 1}", if (i < 10) 0x3b + i else 0x57 + i - 10) }, k("PrtSc", 0x37, true), k("Scroll", 0x46))
        row(k("` ~", 0x29), *Array(9) { i -> k("${i + 1} ${"!@#$%^&*("[i]}", i + 2) }, k("0 )", 0x0b), k("- _", 0x0c), k("= +", 0x0d), k("Backspace", 0x0e, weight = 2f))
        row(k("Tab", 0x0f, weight = 1.5f), *"QWERTYUIOP".mapIndexed { i, c -> k(c.toString(), 0x10 + i) }.toTypedArray(), k("[ {", 0x1a), k("] }", 0x1b), k("\\ |", 0x2b, weight = 1.5f))
        row(k("Caps Lock", 0x3a, weight = 1.8f), *"ASDFGHJKL".mapIndexed { i, c -> k(c.toString(), 0x1e + i) }.toTypedArray(), k("; :", 0x27), k("' \"", 0x28), k("Enter", 0x1c, weight = 2.2f))
        row(k("Shift", 0x2a, weight = 2f), *"ZXCVBNM".mapIndexed { i, c -> k(c.toString(), 0x2c + i) }.toTypedArray(), k(", <", 0x33), k(". >", 0x34), k("/ ?", 0x35), k("Shift", 0x36, weight = 2f), k("↑", 0x48, true))
        row(k("Ctrl", 0x1d), k("Meta", 0x5b, true, 1.5f), k("Alt", 0x38), k("Space", 0x39, weight = 3.5f), k("Alt", 0x38, true), k("Meta", 0x5c, true, 1.5f), k("Ctrl", 0x1d, true), k("←", 0x4b, true), k("↓", 0x50, true), k("→", 0x4d, true))
        row(k("Insert", 0x52, true), k("Delete", 0x53, true), k("Home", 0x47, true), k("End", 0x4f, true), k("Page Up", 0x49, true), k("Page Down", 0x51, true), k("Menu", 0x5d, true))
        // Fit every row to the canvas host, whose width already excludes the fullscreen
        // toolbar. A fixed minimum width here pushed the rightmost keys off screen.
        val vertical = ScrollView(context).apply {
            addView(rows, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            isFillViewport = false
        }
        addView(vertical, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        ready = true
        refresh()
    }
    var close: (() -> Unit)? = null
    fun bind(allowed: () -> Boolean, route: () -> String, release: () -> Unit = {}, send: (Keymap.Scan, Boolean) -> Unit) {
        this.allowed = allowed; this.route = route; this.release = release; this.send = send
    }
    fun sync(remoteSystem: String) {
        val nextRoute = route()
        val nextAvailable = allowed()
        if (lastRoute != nextRoute || !nextAvailable || system != remoteSystem) cancelComposition()
        lastRoute = nextRoute; available = nextAvailable; system = remoteSystem
        metaKeys.forEach { it.text = RemoteKeyboardState.metaLabel(system) }
        altKeys.forEach { it.text = RemoteKeyboardState.altLabel(system) }
        refresh()
    }
    fun cancelComposition() {
        if (keyGesture) ignoreKeyGesture = true
        releaseTouches(); state.reset(); refresh()
    }

    private fun releaseTouches() {
        val held = touches.values.filter { !it.composing }.map { it.scan }.distinct().asReversed()
        touches.clear()
        if (held.isNotEmpty()) {
            if (allowed() && lastRoute == route()) held.forEach { send(it, false) }
            else release()
        }
    }

    private fun contains(view: View, x: Float, y: Float): Boolean {
        val rect = Rect()
        view.getDrawingRect(rect)
        offsetDescendantRectToMyCoords(view, rect)
        // Also clip keys to the visible scroll viewport.
        val visible = Rect()
        if (!view.getGlobalVisibleRect(visible)) return false
        val origin = IntArray(2)
        getLocationOnScreen(origin)
        return rect.contains(x.toInt(), y.toInt()) && visible.contains(origin[0] + x.toInt(), origin[1] + y.toInt())
    }

    private fun finishTouch(id: Int, select: Boolean = false) {
        val touch = touches.remove(id) ?: return
        if (touch.composing) {
            if (select && state.composing) emit(state.tap(touch.scan))
        } else if (touches.values.none { !it.composing && it.scan == touch.scan }) {
            emit(listOf(RemoteKeyboardState.Stroke(touch.scan, false)))
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            releaseTouches()
            sync(system)
            ignoreKeyGesture = false
            keyGesture = keys.any { (view, _) -> contains(view, event.x, event.y) }
            if (keyGesture) parent?.requestDisallowInterceptTouchEvent(true)
        }
        if (!keyGesture) return super.dispatchTouchEvent(event)
        sync(system)
        if (ignoreKeyGesture) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                keyGesture = false; ignoreKeyGesture = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                if (available) keys.firstOrNull { (view, _) -> contains(view, event.getX(i), event.getY(i)) }?.let { (view, scan) ->
                    val held = touches.values.any { !it.composing && it.scan == scan }
                    touches[event.getPointerId(i)] = Touch(view, scan, state.composing)
                    if (!state.composing && !held) emit(listOf(RemoteKeyboardState.Stroke(scan, true)))
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val touch = touches[id] ?: continue
                    // Sliding away releases the original key; it never types adjacent keys.
                    if (!contains(touch.view, event.getX(i), event.getY(i))) finishTouch(id)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                val id = event.getPointerId(i)
                val touch = touches[id]
                finishTouch(id, touch != null && contains(touch.view, event.getX(i), event.getY(i)))
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    releaseTouches(); keyGesture = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                releaseTouches(); keyGesture = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        refresh()
        return true
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (ready && visibility != VISIBLE) cancelComposition()
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (ready && !hasWindowFocus) cancelComposition()
    }
    override fun onDetachedFromWindow() {
        cancelComposition()
        super.onDetachedFromWindow()
    }
    private fun emit(strokes: List<RemoteKeyboardState.Stroke>) {
        if (!allowed() || lastRoute != route()) { cancelComposition(); return }
        strokes.forEach { send(it.scan, it.down) }
    }
    private fun refresh() {
        combo.text = if (state.selected.isNotEmpty()) "发送组合键" else "开启组合键"
        combo.isSelected = state.composing
        combo.isEnabled = available
        combo.alpha = if (combo.isEnabled) 1f else .45f
        keys.forEach { (view, scan) ->
            view.isEnabled = available
            view.isSelected = scan in state.selected || touches.values.any { !it.composing && it.scan == scan }
            view.alpha = if (available) 1f else .45f
        }
    }
    private fun control(label: String, action: () -> Unit) = TextView(context).apply {
        text = label; textSize = 12f; gravity = Gravity.CENTER
        setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        isFocusable = false; isClickable = true
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), background("#DA2778D4"))
            addState(intArrayOf(android.R.attr.state_pressed), background("#AA2778D4"))
            addState(intArrayOf(), background("#503F495C"))
        }
        setOnClickListener { action() }
    }
    private fun background(color: String) = GradientDrawable().apply { setColor(Color.parseColor(color)); cornerRadius = dp(5).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maximum = (View.MeasureSpec.getSize(heightMeasureSpec) * .65f).toInt()
        val height = minOf(dp(348), maximum)
        // Fit every row on a typical landscape display; very short windows remain scrollable.
        val keyHeight = ((height - dp(48)) / 7 - dp(4)).coerceIn(dp(24), dp(38))
        keys.forEach { (key, _) ->
            if (key.layoutParams.height != keyHeight) key.layoutParams = key.layoutParams.apply { this.height = keyHeight }
        }
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
    }
}
