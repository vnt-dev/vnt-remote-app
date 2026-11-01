package com.rustvnt.remote
import org.junit.Assert.*
import org.junit.Test
class ViewportTest {
    @Test fun letterboxingNeverSendsInputOutsideTheImage() {
        val v=Viewport.fit(1000,1000,1920,1080)
        assertNull(v.point(500f,100f));assertNull(v.point(500f,900f))
        val center=v.point(500f,500f)!!;assertEquals(.5f,center.first,.0001f);assertEquals(.5f,center.second,.0001f)
    }
    @Test fun zoomAndPanUseTheSameCoordinatesAsTheSurface() {
        val v=Viewport.fit(1000,1000,1000,1000,2f,100f,-100f)
        val center=v.point(600f,400f)!!;assertEquals(.5f,center.first,.0001f);assertEquals(.5f,center.second,.0001f)
        assertEquals(0f,v.point(v.left,v.top)!!.first,0f)
    }
    @Test fun invalidSizesHaveNoHitTarget() {assertNull(Viewport.fit(0,100,1920,1080).point(0f,0f))}
    @Test fun keyboardUsesWindowsScancodesIncludingExtendedKeys() {
        assertEquals(0x1e,Keymap.scan(29)!!.code) // Android KEYCODE_A
        assertEquals(0x10,Keymap.scan(45)!!.code) // Q
        assertEquals(0x4b,Keymap.scan(21)!!.code) // left arrow
        assertTrue(Keymap.scan(21)!!.extended)
        assertNull(Keymap.scan(4)) // local Android Back
    }
}
