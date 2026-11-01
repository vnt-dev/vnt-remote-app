package com.rustvnt.remote

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CanvasGestureInstrumentedTest {
    private data class Event(val kind:String,val x:Float=0f,val y:Float=0f,val down:Boolean=false)
    private class Sink:RemoteInput {
        override var enabled=true
        val events=mutableListOf<Event>()
        override fun move(x:Float,y:Float){events.add(Event("move",x,y))}
        override fun button(button:Int,down:Boolean){events.add(Event("button",button.toFloat(),down=down))}
        override fun wheel(dx:Float,dy:Float){events.add(Event("wheel",dx,dy))}
        override fun click(button:Int){button(button,true);button(button,false)}
        override fun flush()=Unit
        override fun release(){events.add(Event("release"))}
    }
    private class Fixture(activity:MainActivity) {
        val sink=Sink()
        val state=RemoteViewState().apply {source(1,1,2000,1200)}
        val view=RemoteCanvas(activity).apply {attachInput(state,sink)}
        private var time=SystemClock.uptimeMillis()
        private var start=time
        init {layout()}
        fun layout() {
            view.measure(View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY))
            view.layout(0,0,1000,600)
        }
        fun touch(action:Int,vararg points:Pair<Float,Float>) {
            time+=32;if(action==MotionEvent.ACTION_DOWN)start=time
            val properties=Array(points.size){i->MotionEvent.PointerProperties().apply{id=i;toolType=MotionEvent.TOOL_TYPE_FINGER}}
            val coords=Array(points.size){i->MotionEvent.PointerCoords().apply{x=points[i].first;y=points[i].second;pressure=1f;size=1f}}
            val event=MotionEvent.obtain(start,time,action,points.size,properties,coords,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
            view.onTouchEvent(event);event.recycle()
        }
        fun pinch() {
            touch(MotionEvent.ACTION_DOWN,350f to 250f)
            touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),350f to 250f,650f to 350f)
            touch(MotionEvent.ACTION_MOVE,280f to 220f,780f to 380f)
            touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),280f to 220f,780f to 380f)
            touch(MotionEvent.ACTION_MOVE,700f to 400f)
            touch(MotionEvent.ACTION_UP,700f to 400f)
        }
    }
    @Test fun twoFingersNavigateLocallyWithoutClicksMovesOrWheel() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->scenario.onActivity { activity ->
            val f=Fixture(activity);f.view.trackpad=false;f.sink.events.clear();f.pinch()
            assertTrue(f.state.scale>f.state.fitScale)
            assertTrue(f.sink.events.none{it.kind in listOf("move","button","wheel")})
            assertTrue(f.sink.events.any{it.kind=="release"})
            f.view.release()
        }}
    }
    @Test fun directInputUsesTheZoomedAndPannedSurfaceCoordinates() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->scenario.onActivity { activity ->
            val f=Fixture(activity);f.view.trackpad=false;f.state.original();f.state.panBy(-100f,30f);f.view.refreshView();f.layout();f.sink.events.clear()
            val expected=f.state.viewport().point(250f,200f)!!
            f.touch(MotionEvent.ACTION_DOWN,250f to 200f)
            assertTrue(f.sink.events.isEmpty())
            f.touch(MotionEvent.ACTION_UP,250f to 200f)
            val moved=f.sink.events.first{it.kind=="move"}
            assertEquals(expected.first,moved.x,.001f);assertEquals(expected.second,moved.y,.001f)
            assertEquals(listOf(true,false),f.sink.events.filter{it.kind=="button"}.map{it.down})
            val rect=f.state.viewport();val surface=f.view.getChildAt(0)
            assertEquals(rect.left.toInt(),surface.left);assertEquals(rect.width.toInt(),surface.width)
            f.view.release()
        }}
    }
    @Test fun twoFingerScrollDoesNotMoveTheImageAndPinchDoesNotSendWheel() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->scenario.onActivity { activity ->
            val f=Fixture(activity);f.state.original();f.view.refreshView();f.sink.events.clear()
            val centerX=f.state.centerX;val centerY=f.state.centerY;val scale=f.state.scale
            f.touch(MotionEvent.ACTION_DOWN,350f to 250f)
            f.touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),350f to 250f,650f to 350f)
            f.touch(MotionEvent.ACTION_MOVE,350f to 150f,650f to 250f)
            f.touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),350f to 150f,650f to 250f)
            f.touch(MotionEvent.ACTION_UP,350f to 150f)
            assertTrue(f.sink.events.any{it.kind=="wheel"});assertTrue(f.sink.events.none{it.kind=="move" || it.kind=="button"})
            assertEquals(centerX,f.state.centerX,0f);assertEquals(centerY,f.state.centerY,0f);assertEquals(scale,f.state.scale,0f)
            f.sink.events.clear();f.pinch()
            assertTrue(f.state.scale>scale)
            assertTrue(f.sink.events.none{it.kind in listOf("wheel","button","move")});f.view.release()
        }}
    }
    @Test fun movePictureModePansLocallyAndSingleFingerStillControlsThePointer() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->scenario.onActivity { activity ->
            val f=Fixture(activity);f.state.original();f.view.movePicture=true;f.sink.events.clear()
            val centerY=f.state.centerY
            f.touch(MotionEvent.ACTION_DOWN,350f to 250f)
            f.touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),350f to 250f,650f to 350f)
            f.touch(MotionEvent.ACTION_MOVE,350f to 150f,650f to 250f)
            f.touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),350f to 150f,650f to 250f)
            f.touch(MotionEvent.ACTION_UP,350f to 150f)
            assertNotEquals(centerY,f.state.centerY)
            assertTrue(f.sink.events.none{it.kind in listOf("wheel","button","move")})
            f.sink.events.clear()
            f.touch(MotionEvent.ACTION_DOWN,500f to 300f);f.touch(MotionEvent.ACTION_MOVE,600f to 300f);f.touch(MotionEvent.ACTION_UP,600f to 300f)
            assertTrue(f.sink.events.any{it.kind=="move"});assertTrue(f.sink.events.none{it.kind=="wheel"});f.view.release()
        }}
    }
    @Test fun secondFingerReleasesAnExistingDragAndSuppressesTheRemainingFinger() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->scenario.onActivity { activity ->
            val f=Fixture(activity);f.view.trackpad=false;f.sink.events.clear()
            f.touch(MotionEvent.ACTION_DOWN,300f to 300f);f.touch(MotionEvent.ACTION_MOVE,400f to 300f)
            assertTrue(f.sink.events.any{it.kind=="button" && it.down})
            f.touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),400f to 300f,650f to 400f)
            val before=f.sink.events.size
            f.touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),400f to 300f,650f to 400f)
            f.touch(MotionEvent.ACTION_MOVE,800f to 500f);f.touch(MotionEvent.ACTION_UP,800f to 500f)
            assertTrue(f.sink.events.drop(before).none{it.kind=="move" || it.kind=="wheel" || it.kind=="button"})
            assertEquals(listOf(true,false),f.sink.events.filter{it.kind=="button"}.map{it.down});f.view.release()
        }}
    }
    @Test fun viewOnlyStillAllowsLocalPinch() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->scenario.onActivity { activity ->
            val f=Fixture(activity);f.sink.enabled=false;f.pinch()
            assertTrue(f.state.scale>f.state.fitScale)
            assertTrue(f.sink.events.none{it.kind=="move" || it.kind=="button" || it.kind=="wheel"});f.view.release()
        }}
    }
}
