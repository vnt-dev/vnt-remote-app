package com.rustvnt.remote

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteKeyboardInstrumentedTest {
    @Before fun allowLocalFixtureNetwork() {
        if (android.os.Build.VERSION.SDK_INT >= 37) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                "com.rustvnt.remote", "android.permission.ACCESS_LOCAL_NETWORK"
            )
        }
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    @Test fun selectionIsLocalAndRouteChangesAndInputLossCancelIt() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            var allowed = true
            var route = "session:1:1:1"
            val events = mutableListOf<Pair<Keymap.Scan,Boolean>>()
            val keyboard = RemoteKeyboardView(activity)
            keyboard.bind({allowed},{route}) {scan,down -> events += scan to down}
            keyboard.sync("macos")
            val views = descendants(keyboard)
            fun text(label: String) = views.filterIsInstance<TextView>().first { it.text.toString() == label }
            fun key(scan: Int, extended: Boolean = false) = views.first { it.tag == "key:$scan:$extended" }
            assertEquals(2,views.filterIsInstance<TextView>().count { it.text == "Command" })
            text("开启组合键").performClick()
            assertTrue(text("开启组合键").isEnabled)
            assertTrue(text("开启组合键").isSelected)
            text("开启组合键").performClick()
            assertFalse(text("开启组合键").isSelected)
            assertTrue(events.isEmpty())
            text("开启组合键").performClick()
            key(0x1d).performClick()
            assertTrue(text("发送组合键").isSelected)
            key(0x1d).performClick()
            assertTrue(text("开启组合键").isSelected)
            assertTrue(events.isEmpty())
            key(0x5b,true).performClick(); key(0x2e).performClick()
            assertTrue(key(0x5b,true).isSelected); assertTrue(key(0x2e).isSelected)
            assertTrue(events.isEmpty())
            text("发送组合键").performClick()
            assertFalse(text("开启组合键").isSelected)
            assertEquals(listOf(Keymap.Scan(0x5b,true) to true,Keymap.Scan(0x2e) to true,Keymap.Scan(0x2e) to false,Keymap.Scan(0x5b,true) to false),events)
            events.clear()
            assertFalse(key(0x2e).isSelected)
            text("开启组合键").performClick(); key(0x1d).performClick()
            route="session:2:1:2"; keyboard.sync("macos")
            assertFalse(key(0x1d).isSelected); assertTrue(events.isEmpty())
            text("开启组合键").performClick(); key(0x1d).performClick()
            allowed=false; keyboard.sync("macos")
            assertFalse(key(0x1d).isSelected); assertFalse(key(0x2e).isEnabled)
            allowed=true; keyboard.sync("windows")
            assertTrue(events.isEmpty()); assertEquals("Win",text("Win").text)
            key(0x2e).performClick()
            assertEquals(listOf(Keymap.Scan(0x2e) to true,Keymap.Scan(0x2e) to false),events)
        } }
    }
    @Test fun multitouchHoldsAltAcrossRepeatedTabAndReleasesOnCancellation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val keyboard = RemoteKeyboardView(activity)
            var allowed = true
            var route = "one"
            var resets = 0
            val events = mutableListOf<Pair<Keymap.Scan, Boolean>>()
            keyboard.bind({ allowed }, { route }, { resets++ }) { scan, down -> events += scan to down }
            keyboard.sync("windows")
            val host = FrameLayout(activity)
            host.addView(keyboard, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM))
            activity.setContentView(host)
            host.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, 1080, 2400)
            val views = descendants(keyboard)
            fun key(scan: Int) = views.first { it.tag == "key:$scan:false" }
            fun point(scan: Int): Pair<Float, Float> {
                val view = key(scan)
                val rect = android.graphics.Rect()
                view.getDrawingRect(rect)
                keyboard.offsetDescendantRectToMyCoords(view, rect)
                return rect.exactCenterX() to rect.exactCenterY()
            }
            val alt = Keymap.Scan(0x38)
            val tab = Keymap.Scan(0x0f)
            val now = SystemClock.uptimeMillis()
            var tick = 0L
            fun touch(action: Int, vararg fingers: Pair<Int, Pair<Float, Float>>) {
                val properties = fingers.map { (id, _) -> MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
                val coordinates = fingers.map { (_, xy) -> MotionEvent.PointerCoords().apply { x = xy.first; y = xy.second; pressure = 1f; size = 1f } }.toTypedArray()
                val event = MotionEvent.obtain(now, now + ++tick, action, fingers.size, properties, coordinates, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                assertTrue(keyboard.dispatchTouchEvent(event)); event.recycle()
            }
            val a = 7 to point(0x38)
            val t = 19 to point(0x0f)
            touch(MotionEvent.ACTION_DOWN, a)
            assertEquals(listOf(alt to true), events)
            assertTrue(key(0x38).isSelected)
            repeat(2) {
                touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), a, t)
                touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), a, t)
                assertTrue(key(0x38).isSelected)
            }
            touch(MotionEvent.ACTION_UP, a)
            assertEquals(listOf(alt to true, tab to true, tab to false, tab to true, tab to false, alt to false), events)
            assertFalse(key(0x38).isSelected)
            events.clear()
            // Lifting the first pointer must leave the second key held.
            touch(MotionEvent.ACTION_DOWN, a)
            touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), a, t)
            touch(MotionEvent.ACTION_POINTER_UP, a, t)
            assertTrue(key(0x0f).isSelected)
            touch(MotionEvent.ACTION_UP, t)
            assertEquals(listOf(alt to true, tab to true, alt to false, tab to false), events)
            events.clear()
            touch(MotionEvent.ACTION_DOWN, a)
            touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), a, t)
            touch(MotionEvent.ACTION_CANCEL, a, t)
            assertEquals(listOf(alt to true, tab to true, tab to false, alt to false), events)
            events.clear()
            touch(MotionEvent.ACTION_DOWN, a)
            touch(MotionEvent.ACTION_MOVE, 7 to (0f to 0f))
            touch(MotionEvent.ACTION_UP, 7 to (0f to 0f))
            assertEquals(listOf(alt to true, alt to false), events)
            events.clear()
            // Multiple fingers on the same key only release it when the last finger leaves.
            touch(MotionEvent.ACTION_DOWN, a)
            touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), a, 19 to a.second)
            touch(MotionEvent.ACTION_POINTER_UP, a, 19 to a.second)
            assertEquals(listOf(alt to true), events)
            touch(MotionEvent.ACTION_UP, 19 to a.second)
            assertEquals(listOf(alt to true, alt to false), events)
            events.clear()
            touch(MotionEvent.ACTION_DOWN, a)
            keyboard.visibility = View.GONE
            assertEquals(listOf(alt to true, alt to false), events)
            keyboard.visibility = View.VISIBLE
            touch(MotionEvent.ACTION_UP, a)
            events.clear()
            touch(MotionEvent.ACTION_DOWN, a)
            allowed = false; keyboard.sync("windows")
            assertEquals(1, resets)
            allowed = true; keyboard.sync("windows")
            touch(MotionEvent.ACTION_UP, a)
            assertEquals(listOf(alt to true), events)
            events.clear()
            touch(MotionEvent.ACTION_DOWN, a)
            route = "two"; keyboard.sync("windows")
            assertEquals(2, resets)
            touch(MotionEvent.ACTION_UP, a)
            assertEquals(listOf(alt to true), events)
            events.clear()
            // Composition still selects locally on touch-up and sends only on request.
            val combo = views.filterIsInstance<TextView>().first { it.text == "开启组合键" }
            combo.performClick()
            touch(MotionEvent.ACTION_DOWN, a)
            touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), a, t)
            touch(MotionEvent.ACTION_POINTER_UP, a, t)
            touch(MotionEvent.ACTION_UP, t)
            assertTrue(events.isEmpty())
            assertEquals("发送组合键", combo.text.toString())
            combo.performClick()
            assertEquals(listOf(alt to true, tab to true, tab to false, alt to false), events)
        } }
    }

    @Test fun keyboardRowsFitBesideFullscreenToolbar() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[RemoteModel::class.java]
                model.onEvent("client://connecting", """{"epoch":1}""")
                model.onEvent("client://connected", """{"epoch":1,"viewOnly":false,"remoteSystem":"windows","screens":[]}""")
            }
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("按键"))
                .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.keyboardToggle).performClick()
                activity.findViewById<View>(R.id.fullscreenToggle).performClick()
            }
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("开启组合键"))
                .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
            scenario.onActivity { activity ->
                val keyboard = activity.findViewById<RemoteKeyboardView>(R.id.remoteKeyboard)
                val toolbar = activity.findViewById<View>(R.id.fullscreenControls)
                val origin = IntArray(2); keyboard.getLocationOnScreen(origin)
                val toolbarOrigin = IntArray(2); toolbar.getLocationOnScreen(toolbarOrigin)
                assertTrue(keyboard.width > 0)
                assertTrue("Keyboard must stop before the right toolbar", origin[0] + keyboard.width <= toolbarOrigin[0])
                for (key in descendants(keyboard).filter { it.tag?.toString()?.startsWith("key:") == true }) {
                    val rect = android.graphics.Rect()
                    key.getDrawingRect(rect)
                    keyboard.offsetDescendantRectToMyCoords(key, rect)
                    assertTrue("Key ${key.tag} must fit horizontally: $rect / ${keyboard.width}", rect.left >= 0 && rect.right <= keyboard.width)
                }
                val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                java.io.File(activity.cacheDir, "keyboard-width.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
        }
    }

    @Test fun overlayConsumesBlankTouchesAndLeavesCanvasSizeUnchanged() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val host=FrameLayout(activity)
            var canvasTouches=0
            val canvas=View(activity).apply { setOnTouchListener {_,_->canvasTouches++;true} }
            val keyboard=RemoteKeyboardView(activity)
            host.addView(canvas,FrameLayout.LayoutParams(-1,-1))
            host.addView(keyboard,FrameLayout.LayoutParams(-1,-2,android.view.Gravity.BOTTOM))
            fun layout() {
                host.measure(View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY))
                host.layout(0,0,1000,600)
            }
            layout(); assertEquals(600,canvas.height); assertTrue(keyboard.height<=390)
            val now=SystemClock.uptimeMillis()
            fun touch(x: Float,y: Float) {
                for (action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                    val ev=MotionEvent.obtain(now,now+16,action,x,y,0)
                    assertTrue(host.dispatchTouchEvent(ev));ev.recycle()
                }
            }
            touch(500f,keyboard.top+2f); assertEquals(0,canvasTouches)
            touch(500f,20f); assertEquals(2,canvasTouches)
            keyboard.visibility=View.GONE; layout(); assertEquals(600,canvas.height)
        } }
    }
    @Test fun syntheticConnectionShowsKeyboardInBothLayoutsAndSendsShortcuts() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        val host=args.getString("fixtureHost")
        assumeTrue("Provide fixtureHost to run the synthetic network test",host!=null)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: RemoteModel
            scenario.onActivity { activity ->
                model=ViewModelProvider(activity)[RemoteModel::class.java]
                model.connect(JSONObject().put("name","Keyboard fixture").put("host",host)
                    .put("port",args.getString("fixturePort")?.toInt() ?: 39875)
                    .put("password","android-test").put("transport","tcp"))
            }
            fun waitFor(message: String, condition: () -> Boolean) {
                val deadline=SystemClock.uptimeMillis()+20000
                while(SystemClock.uptimeMillis()<deadline) {
                    var ready=false
                    instrumentation.runOnMainSync {ready=condition()}
                    if(ready)return
                    Thread.sleep(100)
                }
                fail(message)
            }
            waitFor("Synthetic video must decode") {model.canInput && model.renderStats.optLong("framesTotal")>0}
            // Initial surface rotation may trigger a decoder fallback and a new session.
            // Wait for a stable epoch before checking an exact shortcut event sequence.
            var stableEpoch=0L
            var stableSince=SystemClock.uptimeMillis()
            waitFor("Decoder session must settle") {
                if (stableEpoch!=model.epoch || !model.canInput) {
                    stableEpoch=model.epoch; stableSince=SystemClock.uptimeMillis()
                }
                model.canInput && SystemClock.uptimeMillis()-stableSince>=1500
            }
            var frames=0L
            scenario.onActivity { activity ->
                assertEquals("android",model.info!!.getString("localSystem"))
                assertTrue(model.info!!.getString("remoteSystem") in listOf("macos","windows","linux"))
                val actions=activity.findViewById<ViewGroup>(R.id.remoteActions)
                val toggle=activity.findViewById<View>(R.id.keyboardToggle)
                val index=actions.indexOfChild(toggle)
                assertEquals("按键",(actions.getChildAt(index+1) as TextView).text)
                toggle.performClick()
                val keyboard=activity.findViewById<RemoteKeyboardView>(R.id.remoteKeyboard)
                assertEquals(View.VISIBLE,keyboard.visibility)
                val views=descendants(keyboard)
                fun button(label:String)=views.filterIsInstance<TextView>().first {it.text.toString()==label}
                fun key(scan:Int)=views.first {it.tag=="key:$scan:false"}
                key(0x1e).performClick()
                button("开启组合键").performClick();key(0x1d).performClick();key(0x2e).performClick()
                assertTrue(key(0x1d).isSelected)
                button("发送组合键").performClick()
                assertFalse(key(0x1d).isSelected)
                frames=model.renderStats.optLong("framesTotal")
            }
            // Let the reliable input queue drain before recreating the video surface.
            waitFor("Video must continue after shortcut input") {model.renderStats.optLong("framesTotal")>frames}
            scenario.onActivity { activity ->
                val keyboard=activity.findViewById<RemoteKeyboardView>(R.id.remoteKeyboard)
                val views=descendants(keyboard)
                fun button(label:String)=views.filterIsInstance<TextView>().first {it.text.toString()==label}
                fun key(scan:Int)=views.first {it.tag=="key:$scan:false"}
                val toggle=activity.findViewById<View>(R.id.keyboardToggle)
                button("开启组合键").performClick();key(0x1d).performClick()
                activity.findViewById<View>(R.id.fullscreenToggle).performClick()
                assertEquals(View.VISIBLE,keyboard.visibility)
                assertFalse(key(0x1d).isSelected)
                assertEquals(View.GONE,toggle.visibility)
                val controls=activity.findViewById<ViewGroup>(R.id.fullscreenControls)
                assertEquals(controls.indexOfChild(activity.findViewById<View>(R.id.fullscreenExit))+1,controls.indexOfChild(activity.findViewById<View>(R.id.fullscreenKeyboard)))
                frames=model.renderStats.optLong("framesTotal")
            }
            waitFor("Video must keep decoding under keyboard") {model.renderStats.optLong("framesTotal")>frames}
            if(args.getString("keyboardScreenshot")=="true") {
                // Optional visual QA artifact, private to the debug application.
                Thread.sleep(500)
                val bitmap=instrumentation.uiAutomation.takeScreenshot()
                java.io.File(instrumentation.targetContext.cacheDir,"remote-keyboard.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                }
                bitmap.recycle()
            }
            scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
                assertEquals(View.GONE,activity.findViewById<View>(R.id.remoteKeyboard).visibility)
                assertTrue(ViewModelProvider(activity)[WorkspaceState::class.java].remoteFullscreen)
                activity.findViewById<View>(R.id.fullscreenKeyboard).performClick()
                assertEquals(View.VISIBLE,activity.findViewById<View>(R.id.remoteKeyboard).visibility)
                model.disconnect()
                assertEquals(View.GONE,activity.findViewById<View>(R.id.remoteKeyboard).visibility)
            }
        }
    }

}
