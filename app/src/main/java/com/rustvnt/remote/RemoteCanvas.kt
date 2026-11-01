package com.rustvnt.remote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.InputDevice
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.hypot
import kotlin.math.roundToInt

class RemoteCanvas @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs), SurfaceHolder.Callback {
    private val surface = SurfaceView(context)
    private val overlay = PointerView(context)
    private var model: RemoteModel? = null
    private var input: RemoteInput? = null
    private var state = RemoteViewState()
    private var viewport = Viewport(0f,0f,0f,0f)
    var onViewChanged: (() -> Unit)? = null
    val viewState: RemoteViewState get() = state
    var trackpad: Boolean
        get() = state.trackpad
        set(value) { release();state.trackpad=value;changed() }
    var movePicture: Boolean
        get() = state.movePicture
        set(value) { release();state.movePicture=value;changed() }
    private var dragging = false
    private var directStarted = false
    private var multitouch = false
    private var mouseMask = 0
    private var firstPointer = -1
    private var secondPointer = -1
    private var centroidX = 0f
    private var centroidY = 0f
    private var span = 0f
    private var startSpan = 0f
    private var startX = 0f
    private var startY = 0f
    private var twoFingerMode = TwoFingerMode.PENDING
    private val gestureSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private enum class TwoFingerMode { PENDING, SCROLL, PINCH }
    init {
        setBackgroundColor(Color.BLACK);clipChildren=true
        addView(surface,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT))
        addView(overlay,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT))
        surface.holder.setFixedSize(state.remoteWidth,state.remoteHeight)
        surface.holder.addCallback(this)
        contentDescription="远程桌面画面"
    }
    fun bind(value:RemoteModel,batch:RemoteInput) {
        model=value;attachInput(value.viewState,batch)
        value.screen?.let {remoteSize(it.optInt("width",1920),it.optInt("height",1080))}
        if(surface.holder.surface.isValid)value.bridge.surface(this,surface.holder.surface)
    }
    internal fun attachInput(viewState:RemoteViewState,sink:RemoteInput) {
        state=viewState;input=sink;state.resize(width,height)
        surface.holder.setFixedSize(state.remoteWidth,state.remoteHeight);changed()
    }
    fun remoteSize(w:Int,h:Int) {
        if(w<=0 || h<=0)return
        val dimensionsChanged=w!=state.remoteWidth || h!=state.remoteHeight
        if(state.source(model?.epoch ?: 0,model?.activeStream ?: 0,w,h)) {
            if(dimensionsChanged)surface.holder.setFixedSize(w,h)
            changed()
        }
    }
    fun resetZoom() {state.fit();changed()}
    fun originalSize() {state.original();changed()}
    fun zoomBy(factor:Float) {state.zoomBy(factor);changed()}
    fun refreshView() = changed()
    fun release() {
        val now=android.os.SystemClock.uptimeMillis()
        val cancel=MotionEvent.obtain(now,now,MotionEvent.ACTION_CANCEL,0f,0f,0)
        gesture.onTouchEvent(cancel);cancel.recycle()
        if(dragging)input?.button(1,false)
        dragging=false;mouseMask=0;input?.release()
    }
    override fun surfaceCreated(holder:SurfaceHolder) {model?.bridge?.surface(this,holder.surface)}
    override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int) {model?.bridge?.surface(this,holder.surface)}
    override fun surfaceDestroyed(holder:SurfaceHolder) {model?.bridge?.surface(this,null)}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {super.onSizeChanged(w,h,oldw,oldh);state.resize(w,h);changed()}
    override fun onLayout(changed:Boolean,left:Int,top:Int,right:Int,bottom:Int) {
        surface.layout(viewport.left.toInt(),viewport.top.toInt(),(viewport.left+viewport.width).toInt(),(viewport.top+viewport.height).toInt())
        overlay.layout(0,0,right-left,bottom-top)
    }
    private fun changed() {
        val rect=state.viewport()
        if(rect.width>0 && rect.height>0) {
            // Pixel-aligned geometry is also used for input, including Surface layout rounding.
            viewport=Viewport(rect.left.roundToInt().toFloat(),rect.top.roundToInt().toFloat(),rect.width.roundToInt().coerceAtLeast(1).toFloat(),rect.height.roundToInt().coerceAtLeast(1).toFloat())
            val params=surface.layoutParams as LayoutParams
            if(params.width!=viewport.width.toInt() || params.height!=viewport.height.toInt() || params.leftMargin!=viewport.left.toInt() || params.topMargin!=viewport.top.toInt()) {
                surface.layoutParams=LayoutParams(viewport.width.toInt(),viewport.height.toInt()).apply {leftMargin=viewport.left.toInt();topMargin=viewport.top.toInt()}
            }
        } else viewport=rect
        overlay.invalidate();onViewChanged?.invoke()
    }
    private fun moveTo(x:Float,y:Float):Boolean {
        val point=viewport.point(x,y) ?: return false
        state.cursor(point.first,point.second);input?.move(point.first,point.second);overlay.invalidate();return true
    }
    private val gesture=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e:MotionEvent)=true // A second finger may still turn this into local navigation.
        override fun onSingleTapUp(e:MotionEvent):Boolean {
            if(multitouch || input?.enabled!=true)return true
            if(trackpad) {state.followCursor(24*resources.displayMetrics.density);changed();input?.move(state.cursorX,state.cursorY)}
            else if(!directStarted || !moveTo(e.x,e.y))return true
            input?.click();return true
        }
        override fun onLongPress(e:MotionEvent) {
            if(multitouch || input?.enabled!=true || (!trackpad && !directStarted))return
            if(trackpad) {
                state.followCursor(24*resources.displayMetrics.density);changed();input?.move(state.cursorX,state.cursorY)
                input?.button(1,true);dragging=true
            } else if(moveTo(e.x,e.y))input?.click(2)
        }
        override fun onScroll(e1:MotionEvent?,e2:MotionEvent,distanceX:Float,distanceY:Float):Boolean {
            if(multitouch || input?.enabled!=true)return true
            if(trackpad) {
                state.moveCursor(-distanceX,-distanceY,24*resources.displayMetrics.density);changed()
                input?.move(state.cursorX,state.cursorY)
            } else if(directStarted && viewport.point(e2.x,e2.y)!=null) {
                if(!dragging) {e1?.let {moveTo(it.x,it.y)};input?.button(1,true);dragging=true}
                moveTo(e2.x,e2.y)
            }
            return true
        }
    })
    override fun onInterceptTouchEvent(ev:MotionEvent)=true
    private fun baseline(e:MotionEvent,skip:Int=-1) {
        val indices=(0 until e.pointerCount).filter{it!=skip}
        if(indices.size<2){firstPointer=-1;secondPointer=-1;return}
        val a=indices[0];val b=indices[1]
        firstPointer=e.getPointerId(a);secondPointer=e.getPointerId(b)
        centroidX=(e.getX(a)+e.getX(b))/2;centroidY=(e.getY(a)+e.getY(b))/2
        span=hypot(e.getX(a)-e.getX(b),e.getY(a)-e.getY(b))
        startSpan=span;startX=centroidX;startY=centroidY;twoFingerMode=TwoFingerMode.PENDING
    }
    private fun twoFingerMove(x:Float,y:Float,distance:Float) {
        if(movePicture) {
            state.gesture(centroidX,centroidY,x,y,if(span>1f && distance>1f)distance/span else 1f)
            changed()
        } else {
            val spanChange=kotlin.math.abs(distance-startSpan)
            val translation=hypot(x-startX,y-startY)
            val pinchThreshold=maxOf(gestureSlop,startSpan*.04f)
            if(twoFingerMode==TwoFingerMode.PENDING) {
                twoFingerMode=when {
                    spanChange>pinchThreshold && spanChange>translation*.5f -> TwoFingerMode.PINCH
                    translation>gestureSlop -> TwoFingerMode.SCROLL
                    else -> return
                }
            } else if(twoFingerMode==TwoFingerMode.SCROLL && spanChange>pinchThreshold*2 && kotlin.math.abs(distance-span)>hypot(x-centroidX,y-centroidY)) {
                twoFingerMode=TwoFingerMode.PINCH
            }
            if(twoFingerMode==TwoFingerMode.PINCH) {
                state.gesture(centroidX,centroidY,x,y,if(span>1f && distance>1f)distance/span else 1f)
                changed()
            } else if(input?.enabled==true) {
                val step=48*resources.displayMetrics.density
                input?.wheel((x-centroidX)/step,(y-centroidY)/step)
            }
        }
        centroidX=x;centroidY=y;span=distance
    }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        if(e.isFromSource(InputDevice.SOURCE_MOUSE))return mouse(e)
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multitouch=false;directStarted=trackpad || viewport.point(e.x,e.y)!=null
                firstPointer=-1;secondPointer=-1;parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if(!multitouch) {
                    multitouch=true
                    release()
                }
                baseline(e)
            }
            MotionEvent.ACTION_POINTER_UP -> if(multitouch)baseline(e,e.actionIndex)
            MotionEvent.ACTION_MOVE -> if(multitouch && firstPointer>=0 && secondPointer>=0) {
                val a=e.findPointerIndex(firstPointer);val b=e.findPointerIndex(secondPointer)
                if(a>=0 && b>=0) {
                    val x=(e.getX(a)+e.getX(b))/2;val y=(e.getY(a)+e.getY(b))/2
                    val distance=hypot(e.getX(a)-e.getX(b),e.getY(a)-e.getY(b))
                    twoFingerMove(x,y,distance)
                }
            }
        }
        if(!multitouch)gesture.onTouchEvent(e)
        if(e.actionMasked==MotionEvent.ACTION_UP || e.actionMasked==MotionEvent.ACTION_CANCEL) {
            if(dragging)input?.button(1,false)
            dragging=false;input?.flush()
            if(e.actionMasked==MotionEvent.ACTION_CANCEL)release()
            firstPointer=-1;secondPointer=-1;parent?.requestDisallowInterceptTouchEvent(false)
            performClick()
        }
        return true
    }
    override fun performClick():Boolean {super.performClick();return true}
    override fun onGenericMotionEvent(event:MotionEvent):Boolean=if(event.isFromSource(InputDevice.SOURCE_MOUSE))mouse(event) else super.onGenericMotionEvent(event)
    private fun mouse(e:MotionEvent):Boolean {
        if(input?.enabled!=true)return true
        val inside=moveTo(e.x,e.y)
        if(inside && e.actionMasked==MotionEvent.ACTION_SCROLL)input?.wheel(e.getAxisValue(MotionEvent.AXIS_HSCROLL),e.getAxisValue(MotionEvent.AXIS_VSCROLL))
        var mask=if(e.actionMasked==MotionEvent.ACTION_UP || e.actionMasked==MotionEvent.ACTION_CANCEL)0 else e.buttonState
        if(!inside)mask=mask and mouseMask // Release existing buttons, never press a button outside the image.
        for((flag,button) in listOf(MotionEvent.BUTTON_PRIMARY to 1,MotionEvent.BUTTON_SECONDARY to 2,MotionEvent.BUTTON_TERTIARY to 3,MotionEvent.BUTTON_BACK to 4,MotionEvent.BUTTON_FORWARD to 5)) {
            if((mask and flag)!=(mouseMask and flag))input?.button(button,(mask and flag)!=0)
        }
        mouseMask=mask;return true
    }
    private inner class PointerView(context:Context):View(context) {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(93,124,255);strokeWidth=2*resources.displayMetrics.density;style=Paint.Style.STROKE}
        override fun onDraw(canvas:Canvas) {
            if(!trackpad || model?.viewOnly==true)return
            val x=viewport.left+state.cursorX*viewport.width;val y=viewport.top+state.cursorY*viewport.height;val r=8*resources.displayMetrics.density
            canvas.drawCircle(x,y,r,paint);canvas.drawLine(x-r*1.5f,y,x+r*1.5f,y,paint);canvas.drawLine(x,y-r*1.5f,x,y+r*1.5f,paint)
        }
    }
}
