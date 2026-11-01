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
        val state=RemoteViewState().apply {source(1,1,2000,1200);floatingMouseX=0f;floatingMouseY=0f}
        val view=RemoteCanvas(activity).apply {attachInput(state,sink)}
        private var time=SystemClock.uptimeMillis()
        private var start=time
        init {layout()}
        fun layout(w:Int=1000,h:Int=600) {
            view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY))
            view.layout(0,0,w,h)
        }
        fun touch(action:Int,vararg points:Pair<Float,Float>) {
            touchIds(action,points.mapIndexed {i,p->i to p},32)
        }
        fun touchIds(action:Int, contacts:List<Pair<Int,Pair<Float,Float>>>,elapsed:Long=32) {
            time+=elapsed;if(action==MotionEvent.ACTION_DOWN)start=time
            val points=contacts.map {it.second}
            val properties=Array(points.size){i->MotionEvent.PointerProperties().apply{id=contacts[i].first;toolType=MotionEvent.TOOL_TYPE_FINGER}}
            val coords=Array(points.size){i->MotionEvent.PointerCoords().apply{x=points[i].first;y=points[i].second;pressure=1f;size=1f}}
            val event=MotionEvent.obtain(start,time,action,points.size,properties,coords,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
            view.dispatchTouchEvent(event);event.recycle()
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
    @Test fun trackpadPictureTapsNeverClickAndSingleFingerStillMoves() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->scenario.onActivity { activity ->
            for(movePicture in listOf(false,true)) {
                val f=Fixture(activity);f.view.movePicture=movePicture;f.sink.events.clear()
                val x=f.state.cursorX;val y=f.state.cursorY
                f.touch(MotionEvent.ACTION_DOWN,600f to 200f)
                f.touch(MotionEvent.ACTION_UP,600f to 200f)
                assertTrue("A picture tap must not send remote input",f.sink.events.isEmpty())
                assertEquals(x,f.state.cursorX,0f);assertEquals(y,f.state.cursorY,0f)
                f.touchIds(MotionEvent.ACTION_DOWN,listOf(0 to (600f to 200f)),400)
                f.touch(MotionEvent.ACTION_MOVE,700f to 200f)
                f.touch(MotionEvent.ACTION_UP,700f to 200f)
                assertTrue(f.sink.events.any {it.kind=="move"})
                assertTrue(f.sink.events.none {it.kind=="button" || it.kind=="wheel"})
                f.view.release()
            }
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
    private fun Fixture.buttonPoint(button:Int):Pair<Float,Float> {
        val b=view.floatingMouseBounds
        return (b.left+b.width()*(if(button==1).25f else .75f)) to (b.top+b.height()*.3f)
    }
    @Test fun floatingButtonsClickHoldAndDragWithoutExtraClicks() {
        ActivityScenario.launch(MainActivity::class.java).use {scenario->scenario.onActivity {activity->
            for(button in 1..2) {
                val f=Fixture(activity);val p=f.buttonPoint(button);val bounds=f.view.floatingMouseBounds
                f.touch(MotionEvent.ACTION_DOWN,p)
                assertEquals(listOf(true),f.sink.events.filter {it.kind=="button"}.map {it.down})
                f.touchIds(MotionEvent.ACTION_MOVE,listOf(0 to p),1200)
                assertEquals(1,f.sink.events.count {it.kind=="button"})
                val x=f.state.cursorX
                f.touch(MotionEvent.ACTION_MOVE,(p.first+80) to p.second)
                assertTrue(f.state.cursorX>x);assertEquals(bounds,f.view.floatingMouseBounds)
                f.touch(MotionEvent.ACTION_UP,(p.first+80) to p.second)
                assertEquals(listOf(true,false),f.sink.events.filter {it.kind=="button"}.map {it.down})
                assertTrue(f.sink.events.filter {it.kind=="button"}.all {it.x==button.toFloat()})
                f.sink.events.clear();f.touch(MotionEvent.ACTION_DOWN,p);f.touch(MotionEvent.ACTION_UP,p)
                assertEquals(listOf(true,false),f.sink.events.filter {it.kind=="button"}.map {it.down});f.view.release()
            }
        }}
    }
    @Test fun pictureFingerHasPriorityAndHandoffDoesNotJump() {
        ActivityScenario.launch(MainActivity::class.java).use {scenario->scenario.onActivity {activity->
            val f=Fixture(activity);val p=f.buttonPoint(1);val q=600f to 450f
            val pd=MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            val pu=MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            f.touch(MotionEvent.ACTION_DOWN,p);f.touch(pd,p,q)
            val before=f.state.cursorX;val scale=f.state.scale
            f.touch(MotionEvent.ACTION_MOVE,(p.first+100) to p.second,650f to 450f)
            assertEquals(before+.05f,f.state.cursorX,.001f)
            assertEquals(scale,f.state.scale,0f)
            assertTrue(f.sink.events.none {it.kind=="release" || it.kind=="wheel"})
            f.touch(pu,(p.first+100) to p.second,650f to 450f)
            val handoff=f.state.cursorX
            f.touch(MotionEvent.ACTION_MOVE,(p.first+110) to p.second)
            assertEquals(handoff+.01f,f.state.cursorX,.001f)
            f.touch(MotionEvent.ACTION_UP,(p.first+110) to p.second)
            assertEquals(listOf(true,false),f.sink.events.filter {it.kind=="button"}.map {it.down});f.view.release()
        }}
    }
    @Test fun buttonCanJoinAnExistingPictureTouchWithNonzeroPointerId() {
        ActivityScenario.launch(MainActivity::class.java).use {scenario->scenario.onActivity {activity->
            val f=Fixture(activity);val p=f.buttonPoint(2);val q=600f to 450f
            val pd=MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            f.touch(MotionEvent.ACTION_DOWN,q);f.touch(pd,q,p)
            f.touch(MotionEvent.ACTION_MOVE,650f to 450f,p)
            // Lift the original picture finger (id 0), retaining button finger id 1.
            f.touch(MotionEvent.ACTION_POINTER_UP,650f to 450f,p)
            val x=f.state.cursorX
            f.touchIds(MotionEvent.ACTION_MOVE,listOf(1 to ((p.first+10) to p.second)))
            assertEquals(x+.01f,f.state.cursorX,.001f)
            f.touchIds(MotionEvent.ACTION_UP,listOf(1 to ((p.first+10) to p.second)))
            assertEquals(listOf(true,false),f.sink.events.filter {it.kind=="button"}.map {it.down});f.view.release()
        }}
    }
    @Test fun localHandleClampsAndNeverSendsInput() {
        ActivityScenario.launch(MainActivity::class.java).use {scenario->scenario.onActivity {activity->
            val f=Fixture(activity);val b=f.view.floatingMouseBounds
            val p=b.centerX() to (b.top+b.height()*.8f)
            val cursor=f.state.cursorX to f.state.cursorY
            f.touch(MotionEvent.ACTION_DOWN,p)
            f.touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),p,600f to 450f)
            f.touch(MotionEvent.ACTION_MOVE,2000f to 2000f,650f to 450f)
            f.touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2000f to 2000f,650f to 450f)
            f.touch(MotionEvent.ACTION_UP,2000f to 2000f)
            assertTrue(f.sink.events.isEmpty());assertEquals(cursor,f.state.cursorX to f.state.cursorY)
            val moved=f.view.floatingMouseBounds
            assertEquals(1000f,moved.right,0f);assertEquals(600f,moved.bottom,0f)
            f.layout(600,1000);val resized=f.view.floatingMouseBounds
            assertEquals(600f,resized.right,0f);assertEquals(1000f,resized.bottom,0f)
            f.state.source(2,1,2000,1200)
            assertNull(f.state.floatingMouseX);assertNull(f.state.floatingMouseY);f.view.release()
        }}
    }
    @Test fun cancellationAndInputModeChangesReleaseAndSuppressRemainingTouches() {
        ActivityScenario.launch(MainActivity::class.java).use {scenario->scenario.onActivity {activity->
            for(reason in 0..4) {
                val f=Fixture(activity);val p=f.buttonPoint(1)
                f.touch(MotionEvent.ACTION_DOWN,p)
                when(reason) {
                    0->f.touch(MotionEvent.ACTION_CANCEL,p)
                    1->f.view.trackpad=false
                    2->{f.sink.enabled=false;f.view.syncInputAvailability()}
                    3->f.view.remoteSize(1920,1080)
                    else->f.view.release()
                }
                val count=f.sink.events.size
                if(reason!=0) {
                    f.touch(MotionEvent.ACTION_MOVE,700f to 450f);f.touch(MotionEvent.ACTION_UP,700f to 450f)
                }
                assertTrue(f.sink.events.drop(count).none {it.kind in listOf("move","button","wheel")})
                assertEquals(listOf(true,false),f.sink.events.filter {it.kind=="button"}.map {it.down});f.view.release()
            }
        }}
    }

    @Test fun liftingButtonFirstSuppressesPictureUntilAllFingersLift() {
        ActivityScenario.launch(MainActivity::class.java).use {scenario->scenario.onActivity {activity->
            val f=Fixture(activity);val p=f.buttonPoint(1);val q=600f to 450f
            f.touch(MotionEvent.ACTION_DOWN,p)
            f.touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),p,q)
            f.touch(MotionEvent.ACTION_POINTER_UP,p,q)
            val before=f.sink.events.size
            f.touchIds(MotionEvent.ACTION_MOVE,listOf(1 to (700f to 450f)))
            f.touchIds(MotionEvent.ACTION_UP,listOf(1 to (700f to 450f)))
            assertTrue(f.sink.events.drop(before).none {it.kind in listOf("button","move","wheel")})
            f.sink.events.clear();f.touch(MotionEvent.ACTION_DOWN,p);f.touch(MotionEvent.ACTION_UP,p)
            assertEquals(listOf(true,false),f.sink.events.filter {it.kind=="button"}.map {it.down});f.view.release()
        }}
    }
    @Test fun bothButtonsReleaseIndependently() {
        ActivityScenario.launch(MainActivity::class.java).use {scenario->scenario.onActivity {activity->
            val f=Fixture(activity);val left=f.buttonPoint(1);val right=f.buttonPoint(2)
            f.touch(MotionEvent.ACTION_DOWN,left)
            f.touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),left,right)
            f.touch(MotionEvent.ACTION_POINTER_UP,left,right)
            f.touchIds(MotionEvent.ACTION_UP,listOf(1 to right))
            assertEquals(listOf(1f to true,2f to true,1f to false,2f to false),f.sink.events.filter {it.kind=="button"}.map {it.x to it.down});f.view.release()
        }}
    }
    @Test fun floatingMouseRenderingVisibilityAndPressedFeedback() {
        ActivityScenario.launch(MainActivity::class.java).use {scenario->scenario.onActivity {activity->
            val f=Fixture(activity);f.state.floatingMouseX=null;f.state.floatingMouseY=null
            fun render(name:String):android.graphics.Bitmap {
                val bitmap=android.graphics.Bitmap.createBitmap(f.view.width,f.view.height,android.graphics.Bitmap.Config.ARGB_8888)
                f.view.getChildAt(1).draw(android.graphics.Canvas(bitmap))
                java.io.File(activity.cacheDir,name).outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                return bitmap
            }
            val b=f.view.floatingMouseBounds;val x=(b.left+b.width()*.2f).toInt();val y=(b.top+b.height()*.2f).toInt()
            assertEquals(16*activity.resources.displayMetrics.density,1000-b.right,.01f)
            val normal=render("floating-mouse-landscape.png");val color=normal.getPixel(x,y)
            assertTrue(android.graphics.Color.alpha(color) in 1..254)
            f.touch(MotionEvent.ACTION_DOWN,f.buttonPoint(1))
            val pressed=render("floating-mouse-pressed.png");assertNotEquals(color,pressed.getPixel(x,y))
            f.touch(MotionEvent.ACTION_UP,f.buttonPoint(1))
            f.sink.enabled=false;f.view.syncInputAvailability()
            val disabled=render("floating-mouse-disabled.png");assertEquals(0,disabled.getPixel(x,y))
            f.sink.enabled=true;f.view.trackpad=false
            val direct=render("floating-mouse-direct.png");assertEquals(0,direct.getPixel(x,y))
            f.view.trackpad=true;f.layout(600,1000);render("floating-mouse-portrait.png")
            normal.recycle();pressed.recycle();disabled.recycle();direct.recycle();f.view.release()
        }}
    }

}
