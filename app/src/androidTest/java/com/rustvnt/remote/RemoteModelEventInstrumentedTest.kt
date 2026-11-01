package com.rustvnt.remote

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteModelEventInstrumentedTest {
    private fun withModel(test: (RemoteModel) -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val model = RemoteModel(ApplicationProvider.getApplicationContext<Application>())
            try { model.foreground(true); test(model) } finally { model.disconnect(false); model.bridge.close() }
        }
    }
    private fun screen(id: Int, generation: Int) = JSONObject()
        .put("streamId", id).put("generation", generation).put("available", true)
        .put("width", 1920).put("height", 1080)
    private fun connecting(model: RemoteModel, epoch: Long) = model.onEvent(
        "client://connecting", JSONObject().put("epoch", epoch).toString())
    private fun connected(model: RemoteModel, epoch: Long) = model.onEvent(
        "client://connected", JSONObject().put("epoch", epoch).put("viewOnly", false)
            .put("screens", JSONArray().put(screen(1, 1))).toString())
    private fun screens(model: RemoteModel, epoch: Long, revision: Long, id: Int, generation: Int) = model.onEvent(
        "client://media", JSONObject().put("epoch", epoch).put("type", "screensState")
            .put("revision", revision).put("activeStreamId", id)
            .put("screens", JSONArray().put(screen(id, generation))).toString())

    @Test fun connectedDoesNotOverwriteScreenRouteReceivedDuringHandshake() = withModel { model ->
        connecting(model, 1)
        screens(model, 1, 7, 2, 3)
        connected(model, 1)
        assertEquals(2, model.activeStream)
        assertEquals(7L, model.revision)
        assertEquals("Handshake snapshot must not make every input batch stale", 3, model.screen!!.getInt("generation"))
        assertTrue(model.canInput)
    }

    @Test fun connectedDoesNotOverwriteControlOutageReceivedDuringHandshake() = withModel { model ->
        connecting(model, 1)
        model.onEvent("client://media", """{"epoch":1,"type":"transportState","kind":0,"available":false}""")
        connected(model, 1)
        assertFalse(model.controlOnline)
        assertFalse(model.canInput)
        model.onEvent("client://media", """{"epoch":1,"type":"transportState","kind":0,"available":true}""")
        assertTrue(model.canInput)
    }

    @Test fun sameScreenGenerationStaysCurrentRegardlessOfCallbackOrder() = withModel { model ->
        connecting(model, 1)
        screens(model, 1, 4, 1, 3)
        connected(model, 1)
        assertEquals(3, model.screen!!.getInt("generation"))
        assertEquals(4L, model.revision)
        connecting(model, 2)
        connected(model, 2)
        screens(model, 2, 4, 1, 3)
        assertEquals(3, model.screen!!.getInt("generation"))
        assertEquals(4L, model.revision)
        assertTrue(model.canInput)
    }

    @Test fun newEpochClearsPreviousScreenRouteAndIgnoresOldEvents() = withModel { model ->
        connecting(model, 1)
        screens(model, 1, 7, 2, 3)
        connected(model, 1)
        connecting(model, 2)
        screens(model, 1, 8, 2, 4)
        connected(model, 2)
        assertEquals(1, model.activeStream)
        assertEquals(0L, model.revision)
        assertEquals(1, model.screen!!.getInt("generation"))
        screens(model, 2, 1, 1, 2)
        assertEquals(2, model.screen!!.getInt("generation"))
    }
    @Test fun audioRecoveryClearsInitializationAndPreservesControlStatus() = withModel { model ->
        connecting(model, 1)
        connected(model, 1)
        model.onEvent("client://audio", """{"epoch":1,"enabled":true,"available":false,"reason":"正在初始化系统音频"}""")
        assertEquals("正在初始化系统音频", model.status)
        model.onEvent("client://media", """{"epoch":1,"type":"transportState","kind":0,"available":false}""")
        assertEquals("连接恢复中…", model.status)
        model.onEvent("client://audio", """{"epoch":1,"enabled":true,"available":true}""")
        assertEquals("连接恢复中…", model.status)
        model.onEvent("client://media", """{"epoch":1,"type":"transportState","kind":0,"available":true}""")
        assertEquals("已连接", model.status)
    }

    @Test fun audioStateSurvivesHandshakeAndResetsOnNewEpoch() = withModel { model ->
        connecting(model, 1)
        model.onEvent("client://audio", """{"epoch":1,"enabled":true,"available":false,"reason":"正在初始化系统音频"}""")
        connected(model, 1)
        assertEquals("正在初始化系统音频", model.status)
        model.onEvent("client://audio", """{"epoch":1,"enabled":true,"available":true}""")
        assertEquals("已连接", model.status)
        model.onEvent("client://audio", """{"epoch":1,"enabled":true,"available":false,"reason":"旧会话音频错误"}""")
        connecting(model, 2)
        connected(model, 2)
        assertEquals("已连接", model.status)
        model.onEvent("client://audio", """{"epoch":1,"enabled":true,"available":false,"reason":"过期错误"}""")
        assertEquals("已连接", model.status)
    }

    private fun desktop(model: RemoteModel, ready: Boolean, streamId: Int? = null, epoch: Long = 1) {
        val value = JSONObject().put("epoch", epoch).put("type", "desktopState")
            .put("ready", ready).put("reason", if (ready) "" else "等待普通桌面画面")
        if (streamId != null) value.put("streamId", streamId)
        model.onEvent("client://media", value.toString())
    }

    @Test fun desktopRecoveryClearsWaitingAndPreservesControlOutage() = withModel { model ->
        connecting(model, 1)
        connected(model, 1)
        desktop(model, false)
        assertEquals("等待普通桌面画面", model.status)
        desktop(model, true)
        assertEquals("已连接", model.status)
        desktop(model, false)
        model.onEvent("client://media", """{"epoch":1,"type":"transportState","kind":0,"available":false}""")
        desktop(model, true)
        assertEquals("连接恢复中…", model.status)
        model.onEvent("client://media", """{"epoch":1,"type":"transportState","kind":0,"available":true}""")
        assertEquals("已连接", model.status)
    }

    @Test fun desktopRecoveryKeepsAudioWarningAndViewOnlyStatus() = withModel { model ->
        connecting(model, 1)
        model.onEvent("client://connected", """{"epoch":1,"viewOnly":true,"screens":[]}""")
        desktop(model, false)
        model.onEvent("client://audio", """{"epoch":1,"enabled":true,"available":false,"reason":"系统声音不可用"}""")
        desktop(model, true)
        assertEquals("系统声音不可用", model.status)
        model.onEvent("client://audio", """{"epoch":1,"enabled":true,"available":true}""")
        assertEquals("仅观看", model.status)
    }

    @Test fun desktopStateIsScopedToScreenAndSurvivesHandshake() = withModel { model ->
        connecting(model, 1)
        desktop(model, false, 1)
        connected(model, 1)
        assertEquals("等待普通桌面画面", model.status)
        desktop(model, true, 2)
        assertEquals("等待普通桌面画面", model.status)
        desktop(model, true, 1)
        assertEquals("已连接", model.status)
        desktop(model, false, 2)
        assertEquals("已连接", model.status)
        screens(model, 1, 2, 2, 1)
        assertEquals("等待普通桌面画面", model.status)
        desktop(model, true, 2)
        assertEquals("已连接", model.status)
    }

    @Test fun newEpochClearsDesktopWaitingAndRejectsStaleDesktopEvents() = withModel { model ->
        connecting(model, 1)
        connected(model, 1)
        desktop(model, false, 1)
        connecting(model, 2)
        connected(model, 2)
        assertEquals("已连接", model.status)
        desktop(model, false, 1, epoch = 1)
        assertEquals("已连接", model.status)
    }

}
