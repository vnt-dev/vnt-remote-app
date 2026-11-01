package com.rustvnt.remote

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ControllerInstrumentedTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun encryptedConfigurationRoundTripsUnicodeAndKeepsSecretsOffDisk() {
        val store=SecureStore(context);val previous=store.load()
        try {
            val secret="测试-password-"+System.nanoTime();store.save(JSONObject().put("password",secret))
            assertEquals(secret,store.load().getString("password"))
            val raw=context.getSharedPreferences("vnt_secure",Context.MODE_PRIVATE).getString("config","")!!
            assertFalse(raw.contains(secret))
        } finally {store.save(previous)}
    }
    @Test fun nativeRejectsMalformedInputAndDestroyedHandles() {
        val bridge=NativeBridge(context.filesDir.absolutePath,Build.VERSION.SDK_INT){_,_->}
        val field=NativeBridge::class.java.getDeclaredField("handle").apply{isAccessible=true};val handle=field.getLong(bridge)
        val bytes=ByteBuffer.allocateDirect(24).order(ByteOrder.LITTLE_ENDIAN).apply{putInt(0).putInt(0).putInt(0).putInt(0).putFloat(Float.NaN).putFloat(.5f)}
        try {bridge.input(1,1,1,0,bytes,1);fail("NaN must be rejected") }catch(_:IllegalStateException){}
        bridge.close();bridge.close()
        val native=NativeBridge::class.java.getDeclaredMethod("nativeCommand",Long::class.javaPrimitiveType,String::class.java).apply{isAccessible=true}
        try {native.invoke(null,handle,"{\"op\":\"disconnect\"}");fail("stale handle must be rejected")}
        catch(e:InvocationTargetException){assertTrue(e.cause is IllegalStateException)}
    }
    @Test fun rotationRetainsControllerAndActivityCanResume() {
        var before:RemoteModel?=null
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            scenario.onActivity{before=ViewModelProvider(it)[RemoteModel::class.java]}
            scenario.recreate()
            scenario.onActivity{assertSame(before,ViewModelProvider(it)[RemoteModel::class.java]);assertTrue(ViewModelProvider(it)[RemoteModel::class.java].foreground)}
        }
    }
    @Test fun unreachableConnectionCanBeCancelledWithoutWaitingForHandshakeTimeout() {
        val cancelled=CountDownLatch(1);val bridge=NativeBridge(context.filesDir.absolutePath,Build.VERSION.SDK_INT){event,_->if(event=="client://closed")cancelled.countDown()}
        try {
            val json="""{"op":"connect","request":{"params":{"host":"192.0.2.1","port":29875,"password":"","transport":"tcp","colorDepth":24,"showWallpaper":true,"dragFullWindows":false,"fecEnabled":false,"resolutionMode":"scale","targetWidth":1280,"targetHeight":720,"codecs":["h264"],"hardwareCodecs":[],"fps":30,"bitrateKbps":5000,"clipboardEnabled":true,"audioEnabled":false,"audioDeviceId":null},"decoders":[{"codec":"h264","name":"c2.android.avc.decoder","hardware":false,"maxWidth":1920,"maxHeight":1080,"maxFps":60}]}}"""
            bridge.command(json);bridge.command("{\"op\":\"disconnect\"}")
            assertTrue("cancel must complete promptly",cancelled.await(3,TimeUnit.SECONDS))
        } finally {bridge.close()}
    }
    @Test fun syntheticHostStreamsAcrossTransportsAndSurfaceRecreation() {
        val host=InstrumentationRegistry.getArguments().getString("fixtureHost")
        assumeTrue("Run the synthetic Rust fixture and pass -e fixtureHost 10.0.2.2",host!=null)
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model:RemoteModel
            scenario.onActivity { model=ViewModelProvider(it)[RemoteModel::class.java] }
            fun waitFor(message:String, timeoutMs:Long=20000, predicate:()->Boolean) {
                val end=System.currentTimeMillis()+timeoutMs
                while(System.currentTimeMillis()<end) {
                    var ready=false;instrumentation.runOnMainSync {ready=predicate()}
                    if(ready)return
                    Thread.sleep(100)
                }
                var detail="";instrumentation.runOnMainSync{detail=model.status+" / "+model.error+" / "+model.renderStats.toString()}
                fail("$message: $detail")
            }
            for(transport in listOf("tcp","hybrid","quic")) {
                scenario.onActivity { assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,it.requestedOrientation) }
                scenario.onActivity { model.connect(JSONObject().put("name","Synthetic $transport").put("host",host).put("port",39875).put("password","android-test").put("transport",transport)) }
                waitFor("$transport must deliver decoded frames") {model.info!=null&&model.renderStats.optInt("renderFps")>0}
                var epoch=0L;var frames=0L;scenario.onActivity {
                    assertTrue(model.controlOnline);epoch=model.epoch;frames=model.renderStats.optLong("framesTotal")
                    val canvas=it.findViewById<android.view.ViewGroup>(R.id.remoteCanvas)
                    val surface=canvas.getChildAt(0)
                    val screen=model.screen!!
                    assertEquals("display and input must use the same aspect ratio",screen.optInt("width").toFloat()/screen.optInt("height"),surface.width.toFloat()/surface.height,.01f)
                    assertTrue("fitted surface must remain inside its parent",surface.top>=0&&surface.bottom<=canvas.height)
                }
                var pasteEvents=0;var pasteError="";var decoderStarts=0
                scenario.onActivity {
                    val previous=model.listener
                    model.listener={event->
                        if(event=="client://paste"){pasteEvents++;pasteError=model.error}
                        if(event=="client://decoder")decoderStarts++
                        previous?.invoke(event)
                    }
                    model.clipboard("安卓协议粘贴🙂",true)
                }
                waitFor("$transport must confirm remote paste") {pasteEvents==1}
                assertEquals("",pasteError)
                scenario.onActivity {model.clipboard("reject-paste",true)}
                waitFor("$transport must report remote paste failure") {pasteEvents==2}
                assertEquals("synthetic paste failure",pasteError)
                var zoomScale=0f;var zoomCenterX=0f;var zoomCenterY=0f;var zoomFrames=0L
                scenario.onActivity { activity ->
                    val canvas=activity.findViewById<RemoteCanvas>(R.id.remoteCanvas)
                    model.viewState.original();model.viewState.zoomBy(1.25f);canvas.refreshView()
                    assertEquals(1.25f,model.viewState.scale,0f)
                    model.viewState.panBy(60f,-40f);canvas.refreshView()
                    zoomScale=model.viewState.scale;zoomCenterX=model.viewState.centerX;zoomCenterY=model.viewState.centerY
                    zoomFrames=model.renderStats.optLong("framesTotal")
                }
                waitFor("zoom and pan must keep decoding without rebuilding the media stream") {model.renderStats.optLong("framesTotal")>zoomFrames}
                scenario.onActivity {assertEquals("local zoom must not restart MediaCodec",0,decoderStarts)}
                scenario.onActivity { activity ->
                    assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,activity.requestedOrientation)
                    assertEquals(android.content.res.Configuration.ORIENTATION_LANDSCAPE,activity.resources.configuration.orientation)
                    activity.findViewById<android.view.View>(R.id.fullscreenToggle).performClick()
                    assertEquals(android.view.View.GONE,activity.findViewById<android.view.View>(R.id.remoteHeader).visibility)
                    assertEquals(android.view.View.GONE,activity.findViewById<android.view.View>(R.id.remoteActionScroll).visibility)
                    assertEquals(android.view.View.VISIBLE,activity.findViewById<android.view.View>(R.id.fullscreenControls).visibility)
                    assertEquals(android.view.View.GONE,activity.findViewById<android.view.View>(R.id.fullscreenToggle).visibility)
                    val controls=activity.findViewById<android.view.ViewGroup>(R.id.remoteActions)
                    val labels=(0 until controls.childCount).mapNotNull {(controls.getChildAt(it) as? android.widget.TextView)?.text?.toString()}
                    assertFalse(labels.any {it=="屏幕" || it=="远端滚动" || it=="退出全屏" || '%' in it})
                    activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.movePicture).performClick()
                    assertTrue(model.viewState.movePicture)
                    activity.findViewById<android.view.View>(R.id.fullscreenMenu).performClick()
                    assertEquals(android.view.View.VISIBLE,activity.findViewById<android.view.View>(R.id.remoteActionScroll).visibility)
                    activity.findViewById<android.view.View>(R.id.fullscreenMenu).performClick()
                }
                waitFor("fullscreen must rotate to landscape and hide system bars",5000) {
                    val activity=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
                    val canvas=activity?.findViewById<android.view.View>(R.id.remoteCanvas)
                    val insets=canvas?.let {androidx.core.view.ViewCompat.getRootWindowInsets(it)}
                    activity?.resources?.configuration?.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
                        activity.requestedOrientation==android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE &&
                        canvas!=null && canvas.width>canvas.height &&
                        insets!=null && canvas.height==canvas.rootView.height-insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.displayCutout()).top-insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.displayCutout()).bottom &&
                        (canvas.right<=activity.findViewById<android.view.View>(R.id.fullscreenControls).left || activity.findViewById<android.view.View>(R.id.fullscreenControls).right<=canvas.left) &&
                        !insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                }
                scenario.recreate()
                waitFor("rotation must rebuild the Surface on the same session") {model.epoch==epoch&&model.info!=null&&model.renderStats.optLong("framesTotal")>frames}
                scenario.onActivity { activity ->
                    assertEquals(android.view.View.GONE,activity.findViewById<android.view.View>(R.id.remoteHeader).visibility)
                    assertTrue(ViewModelProvider(activity)[WorkspaceState::class.java].remoteFullscreen)
                    assertEquals(zoomScale,model.viewState.scale,0f)
                    assertEquals(zoomCenterX,model.viewState.centerX,.0001f);assertEquals(zoomCenterY,model.viewState.centerY,.0001f)
                    assertTrue(activity.findViewById<RemoteCanvas>(R.id.remoteCanvas).movePicture)
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                waitFor("background must suspend media",5000) {!model.foreground&&model.renderStats.optInt("renderFps")==0}
                scenario.moveToState(Lifecycle.State.RESUMED)
                waitFor("foreground must resume decoding") {model.epoch==epoch&&model.renderStats.optInt("renderFps")>0}
                scenario.onActivity { activity ->
                    activity.onBackPressedDispatcher.onBackPressed()
                    assertFalse(ViewModelProvider(activity)[WorkspaceState::class.java].remoteFullscreen)
                    assertEquals(android.view.View.VISIBLE,activity.findViewById<android.view.View>(R.id.remoteHeader).visibility)
                    assertEquals(epoch,model.epoch)
                    assertNotNull(model.info)
                    assertEquals(zoomScale,model.viewState.scale,0f)
                }
                waitFor("leaving fullscreen must keep landscape",5000) {
                    androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().any {
                            it.requestedOrientation==android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE && it.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE
                        }
                }
                if(transport=="quic") {
                    scenario.onActivity {it.findViewById<android.view.View>(R.id.fullscreenToggle).performClick()}
                    waitFor("enter landscape before disconnect",5000) {
                        androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().any {
                                it.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE
                            }
                    }
                }
                scenario.onActivity {model.disconnect()}
                waitFor("disconnect must end the session",5000) {model.info==null&&!model.connecting}
                scenario.onActivity {assertTrue(model.viewState.fitting);assertFalse(model.viewState.movePicture)}
                waitFor("disconnect must return to portrait",5000) {
                    androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().any {
                            it.requestedOrientation==android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT && it.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT
                        }
                }
            }
            val repeats=InstrumentationRegistry.getArguments().getString("reconnects")?.toIntOrNull() ?: 0
            val baseline=android.os.Debug.getNativeHeapAllocatedSize()
            val initialThreads=java.io.File("/proc/self/task").list()?.size ?: 0
            repeat(repeats) {
                scenario.onActivity { model.connect(JSONObject().put("name","Reconnect $it").put("host",host).put("port",39875).put("password","android-test").put("transport","tcp")) }
                waitFor("reconnection $it must decode fresh frames") {model.info!=null&&model.renderStats.optLong("epoch")==model.epoch&&model.renderStats.optLong("framesTotal")>0}
                scenario.onActivity {model.disconnect()}
                Thread.sleep(350)
            }
            if(repeats>0) {
                Thread.sleep(2200)
                val finalBytes=android.os.Debug.getNativeHeapAllocatedSize()
                val finalThreads=java.io.File("/proc/self/task").list()?.size ?: 0
                android.util.Log.i("VntAcceptance","reconnects=$repeats nativeBefore=$baseline nativeAfter=$finalBytes threadsBefore=$initialThreads threadsAfter=$finalThreads")
                assertTrue("native memory must return within 64 MiB of the warmed baseline", finalBytes <= baseline+64L*1024*1024)
                assertTrue("session workers must be released", finalThreads <= initialThreads+8)
            }
        }
    }

}
