package com.rustvnt.remote

import kotlin.math.max
import kotlin.math.min

/** Session-owned geometry. Scale is physical display pixels per decoded video pixel. */
class RemoteViewState {
    var remoteWidth = 1920; private set
    var remoteHeight = 1080; private set
    var viewWidth = 0; private set
    var viewHeight = 0; private set
    var fitting = true; private set
    private var manualScale = 1f
    var centerX = .5f; private set
    var centerY = .5f; private set
    var cursorX = .5f; private set
    var cursorY = .5f; private set
    // Fractions of the available overlay travel; null means the default bottom-right inset.
    var floatingMouseX: Float? = null
    var floatingMouseY: Float? = null
    var trackpad = true
    var movePicture = false
    private var epoch = 0L
    private var stream = 0
    val fitScale: Float get() = if(viewWidth>0 && viewHeight>0) min(viewWidth.toFloat()/remoteWidth,viewHeight.toFloat()/remoteHeight) else 1f
    val scale: Float get() = if(fitting) fitScale else manualScale

    fun reset() {
        floatingMouseX=null;floatingMouseY=null
        epoch=0;stream=0;trackpad=true;movePicture=false;cursorX=.5f;cursorY=.5f;fit()
    }
    fun source(session:Long, streamId:Int, width:Int, height:Int):Boolean {
        if(width<=0 || height<=0)return false
        if(epoch==session && stream==streamId && remoteWidth==width && remoteHeight==height)return false
        val newSession=epoch!=session
        epoch=session;stream=streamId;remoteWidth=width;remoteHeight=height
        if(newSession){floatingMouseX=null;floatingMouseY=null;movePicture=false;cursorX=.5f;cursorY=.5f}
        fit();return true
    }
    fun resize(width:Int,height:Int) {viewWidth=width.coerceAtLeast(0);viewHeight=height.coerceAtLeast(0)}
    fun fit() {fitting=true;centerX=.5f;centerY=.5f}
    fun original() {fitting=false;manualScale=1f;clampCenter()}
    fun viewport():Viewport {
        if(viewWidth==0 || viewHeight==0)return Viewport(0f,0f,0f,0f)
        val w=remoteWidth*scale;val h=remoteHeight*scale
        fun edge(size:Float,view:Int,center:Float)=if(size<=view)(view-size)/2 else (view/2f-center*size).coerceIn(view-size,0f)
        return Viewport(edge(w,viewWidth,centerX),edge(h,viewHeight,centerY),w,h)
    }
    private fun clampCenter() {
        val rect=viewport()
        if(rect.width>0){centerX=((viewWidth/2f-rect.left)/rect.width).coerceIn(0f,1f);centerY=((viewHeight/2f-rect.top)/rect.height).coerceIn(0f,1f)}
    }
    /** One transform combines pinch around the old focus with translation to the new focus. */
    fun gesture(oldX:Float,oldY:Float,newX:Float,newY:Float,factor:Float) {
        if(viewWidth==0 || viewHeight==0 || !factor.isFinite() || factor<=0)return
        if(fitting && kotlin.math.abs(factor-1f)<.0001f)return // A fitted image has no off-screen area to pan.
        val before=viewport();val oldScale=scale
        val u=((oldX-before.left)/before.width).coerceIn(0f,1f)
        val v=((oldY-before.top)/before.height).coerceIn(0f,1f)
        val minimum=min(min(fitScale,1f),oldScale)
        val maximum=max(max(fitScale*4f,1f),oldScale)
        manualScale=(oldScale*factor).coerceIn(minimum,maximum);fitting=false
        centerX=(viewWidth/2f-newX+u*remoteWidth*manualScale)/(remoteWidth*manualScale)
        centerY=(viewHeight/2f-newY+v*remoteHeight*manualScale)/(remoteHeight*manualScale)
        clampCenter()
    }
    fun zoomBy(factor:Float) = gesture(viewWidth/2f,viewHeight/2f,viewWidth/2f,viewHeight/2f,factor)
    fun panBy(dx:Float,dy:Float) {
        if(!dx.isFinite() || !dy.isFinite())return
        val rect=viewport();if(rect.width<=0)return
        val currentScale=scale;fitting=false;manualScale=currentScale
        centerX=(viewWidth/2f-rect.left-dx)/rect.width;centerY=(viewHeight/2f-rect.top-dy)/rect.height
        clampCenter()
    }
    fun cursor(x:Float,y:Float) {cursorX=x.coerceIn(0f,1f);cursorY=y.coerceIn(0f,1f)}
    fun moveCursor(dx:Float,dy:Float,margin:Float) {
        val rect=viewport();if(rect.width<=0)return
        cursor(cursorX+dx/rect.width,cursorY+dy/rect.height);followCursor(margin)
    }
    fun followCursor(margin:Float) {
        val rect=viewport();if(rect.width<=0)return
        val x=rect.left+cursorX*rect.width;val y=rect.top+cursorY*rect.height
        val mx=min(margin,viewWidth/2f);val my=min(margin,viewHeight/2f)
        val dx=if(rect.width>viewWidth)x.coerceIn(mx,viewWidth-mx)-x else 0f
        val dy=if(rect.height>viewHeight)y.coerceIn(my,viewHeight-my)-y else 0f
        if(dx!=0f || dy!=0f)panBy(dx,dy)
    }
}
