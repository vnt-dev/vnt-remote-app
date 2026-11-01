package com.rustvnt.remote

import org.junit.Assert.*
import org.junit.Test

class RemoteViewStateTest {
    private fun state()=RemoteViewState().apply {source(1,1,2000,1000);resize(1000,500)}
    @Test fun fitAndOriginalUseDecodedPixelsAndAlwaysRemainSelectable() {
        val s=state();assertEquals(.5f,s.scale,0f)
        s.original();assertEquals(1f,s.scale,0f);assertEquals(2000f,s.viewport().width,0f)
        s.fit();assertEquals(.5f,s.scale,0f)
        s.resize(4000,2000);assertEquals(2f,s.scale,0f)
        s.original();assertEquals(1f,s.scale,0f);assertEquals(1000f,s.viewport().left,0f)
    }
    @Test fun pinchPreservesTheRemotePointUnderTheMovingFocus() {
        val s=state();s.zoomBy(3f)
        val anchor=s.viewport().point(330f,210f)!!
        s.gesture(330f,210f,400f,250f,1.2f)
        val after=s.viewport().point(400f,250f)!!
        assertEquals(anchor.first,after.first,.00001f);assertEquals(anchor.second,after.second,.00001f)
    }
    @Test fun panClampsAtEdgesWithoutExposingBlankSpace() {
        val s=state();s.zoomBy(4f)
        s.panBy(10000f,10000f);assertEquals(0f,s.viewport().left,0f);assertEquals(0f,s.viewport().top,0f)
        s.panBy(-10000f,-10000f);val v=s.viewport()
        assertEquals(1000f,v.left+v.width,0f);assertEquals(500f,v.top+v.height,0f)
    }
    @Test fun sourceIdentityAndSessionResetAreSeparateFromDecoderRestarts() {
        val s=state();s.zoomBy(2f);s.movePicture=true
        assertFalse(s.source(1,1,2000,1000));assertFalse(s.fitting)
        assertTrue(s.source(1,2,2000,1000));assertTrue(s.fitting);assertTrue(s.movePicture)
        s.zoomBy(2f);s.source(1,2,1920,1080);assertTrue(s.fitting)
        s.source(2,2,1920,1080);assertFalse(s.movePicture)
        s.movePicture=true;s.reset();assertFalse(s.movePicture);assertTrue(s.fitting)
    }
    @Test fun manualScaleAndChosenCenterSurviveWindowResizing() {
        val s=state();s.zoomBy(3f);s.panBy(120f,-40f)
        val scale=s.scale;val x=s.centerX;val y=s.centerY
        s.resize(500,1000);assertEquals(scale,s.scale,0f);assertEquals(x,s.centerX,0f);assertEquals(y,s.centerY,0f)
        s.resize(1000,500);assertEquals(x,s.centerX,0f);assertEquals(y,s.centerY,0f)
        s.fit();s.resize(500,1000);assertEquals(.25f,s.scale,0f)
    }
    @Test fun zoomRangeIncludesOriginalAndFourTimesFit() {
        val s=state();s.zoomBy(100f);assertEquals(2f,s.scale,0f)
        s.zoomBy(.0001f);assertEquals(.5f,s.scale,0f)
        s.resize(100,50);s.fit();s.original();assertEquals(1f,s.scale,0f)
        s.zoomBy(100f);assertEquals(1f,s.scale,0f)
    }
    @Test fun cursorFollowingKeepsThePointerWithinTheVisibleMargin() {
        val s=state();s.zoomBy(4f);s.moveCursor(.3f*s.viewport().width,.2f*s.viewport().height,24f)
        val v=s.viewport();val x=v.left+s.cursorX*v.width;val y=v.top+s.cursorY*v.height
        assertTrue(x in 24f..976f);assertTrue(y in 24f..476f)
    }
    @Test fun zoomAndPanShareTheSameRemoteHitCoordinates() {
        val s=state();s.original();s.panBy(-100f,40f)
        val rect=s.viewport();val point=rect.point(rect.left+.25f*rect.width,rect.top+.75f*rect.height)!!
        assertEquals(.25f,point.first,.00001f);assertEquals(.75f,point.second,.00001f)
        assertNull(rect.point(rect.left-1,rect.top))
    }
    @Test fun floatingMousePositionBelongsToTheSessionRatherThanTheStreamOrWindow() {
        val s=state();s.floatingMouseX=.2f;s.floatingMouseY=.7f
        s.resize(500,1000);s.source(1,2,1920,1080)
        assertEquals(.2f,s.floatingMouseX!!,0f);assertEquals(.7f,s.floatingMouseY!!,0f)
        s.source(2,1,1920,1080);assertNull(s.floatingMouseX);assertNull(s.floatingMouseY)
        s.floatingMouseX=.3f;s.floatingMouseY=.4f;s.reset()
        assertNull(s.floatingMouseX);assertNull(s.floatingMouseY)
    }

}
