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
            assertFalse(text("发送组合键").isEnabled)
            key(0x5b,true).performClick(); key(0x2e).performClick()
            assertTrue(key(0x5b,true).isSelected); assertTrue(key(0x2e).isSelected)
            assertTrue(events.isEmpty())
            text("发送组合键").performClick()
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
