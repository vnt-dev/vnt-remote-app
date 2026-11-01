package com.rustvnt.remote

import android.media.AudioTrack
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class RemoteAudioInstrumentedTest {
    @Test fun syntheticAudioReachesPcmAndAudioTrackAcrossTransports() {
        val host = InstrumentationRegistry.getArguments().getString("fixtureHost")
        assumeTrue("Requires the synthetic Rust audio fixture", host != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (transport in listOf("tcp", "hybrid", "quic")) {
            val connected = CountDownLatch(1)
            val ready = CountDownLatch(1)
            val lastStatus = AtomicReference("")
            val bridge = NativeBridge(context.filesDir.absolutePath, Build.VERSION.SDK_INT) { event, payload ->
                if (event == "client://connected") connected.countDown()
                if (event == "client://audio") {
                    lastStatus.set(payload)
                    if (JSONObject(payload).optBoolean("available")) ready.countDown()
                }
            }
            val player = AudioPlayer()
            try {
                val params = JSONObject().put("host", host).put("port", 39875).put("password", "android-test")
                    .put("transport", transport).put("colorDepth", 24).put("showWallpaper", true)
                    .put("dragFullWindows", false).put("fecEnabled", false).put("resolutionMode", "scale")
                    .put("targetWidth", 1280).put("targetHeight", 720).put("fps", 30).put("bitrateKbps", 2000)
                    .put("codecs", org.json.JSONArray().put("h264")).put("hardwareCodecs", org.json.JSONArray())
                    .put("clipboardEnabled", false).put("audioEnabled", true)
                bridge.command(JSONObject().put("op", "connect").put("request", JSONObject()
                    .put("params", params).put("decoders", CodecProbe.decoders(1280, 720, 30))).toString())
                assertTrue("$transport handshake", connected.await(20, TimeUnit.SECONDS))
                assertTrue("$transport audio state: ${lastStatus.get()}", ready.await(10, TimeUnit.SECONDS))
                val buffer = ByteBuffer.allocateDirect(3840).order(ByteOrder.nativeOrder())
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var audible = false
                while (!audible && System.nanoTime() < deadline) {
                    buffer.clear()
                    val bytes = bridge.readAudio(buffer)
                    for (offset in 0 until bytes step 4) if (kotlin.math.abs(buffer.getFloat(offset)) > .001f) audible = true
                    Thread.sleep(5)
                }
                assertTrue("$transport must decode the fixture tone, not just silence", audible)
                val failure = AtomicReference<String>()
                player.start(bridge) { failure.set(it) }
                val field = AudioPlayer::class.java.getDeclaredField("track").apply { isAccessible = true }
                val outputDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var played = false
                while (!played && failure.get() == null && System.nanoTime() < outputDeadline) {
                    val track = field.get(player) as? AudioTrack
                    played = (track?.playbackHeadPosition ?: 0) >= 4800
                    Thread.sleep(20)
                }
                assertNull("$transport local playback error", failure.get())
                assertTrue("$transport AudioTrack must consume at least 100ms", played)
            } finally {
                player.close()
                bridge.close()
            }
        }
    }
}
