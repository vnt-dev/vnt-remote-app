package com.rustvnt.remote

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class RemoteKeyboardStateTest {
    private val ctrl = Keymap.Scan(0x1d)
    private val c = Keymap.Scan(0x2e)
    @Test fun normalClickAlwaysReleasesAndComboSelectionNeverSends() {
        val state = RemoteKeyboardState()
        assertEquals(listOf(RemoteKeyboardState.Stroke(c,true), RemoteKeyboardState.Stroke(c,false)), state.tap(c))
        state.begin()
        assertTrue(state.send().isEmpty())
        assertTrue(state.composing)
        assertTrue(state.tap(ctrl).isEmpty())
        assertTrue(state.tap(c).isEmpty())
        assertEquals(listOf(ctrl,c), state.selected)
        assertTrue(state.tap(ctrl).isEmpty())
        assertEquals(listOf(c),state.selected)
        state.tap(ctrl)
        assertEquals(listOf(c,ctrl),state.selected)
        assertEquals(listOf(
            RemoteKeyboardState.Stroke(c,true),RemoteKeyboardState.Stroke(ctrl,true),
            RemoteKeyboardState.Stroke(ctrl,false),RemoteKeyboardState.Stroke(c,false)
        ),state.send())
        assertFalse(state.composing)
        assertTrue(state.selected.isEmpty())
    }
    @Test fun cancellationCannotReplayASelection() {
        val state=RemoteKeyboardState()
        state.begin(); state.tap(ctrl); state.tap(c); state.reset()
        assertTrue(state.send().isEmpty()); assertFalse(state.composing)
        assertEquals(2,state.tap(c).size)
    }
    @Test fun systemNamesAndPhysicalMappingsMatchHostInjection() {
        assertEquals("Command",RemoteKeyboardState.metaLabel("macos"))
        assertEquals("Option",RemoteKeyboardState.altLabel("macos"))
        assertEquals("Win",RemoteKeyboardState.metaLabel("windows"))
        assertEquals("Super",RemoteKeyboardState.metaLabel("linux"))
        assertEquals("Alt",RemoteKeyboardState.altLabel("windows"))
        assertEquals(c,Keymap.scan(KeyEvent.KEYCODE_C))
        assertEquals(Keymap.Scan(0x5b,true),Keymap.scan(KeyEvent.KEYCODE_META_LEFT))
        assertEquals(Keymap.Scan(0x5c,true),Keymap.scan(KeyEvent.KEYCODE_META_RIGHT))
        assertEquals(Keymap.Scan(0x48,true),Keymap.scan(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(Keymap.Scan(0x58),Keymap.scan(KeyEvent.KEYCODE_F12))
    }
}
