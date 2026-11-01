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
            try { model.foreground(true); test(model) } finally { model.bridge.close() }
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
}
