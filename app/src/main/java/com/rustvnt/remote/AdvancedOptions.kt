package com.rustvnt.remote

import android.view.View
import android.content.res.ColorStateList
import com.google.android.material.button.MaterialButton
import android.widget.AdapterView
import android.widget.LinearLayout
import com.google.android.material.switchmaterial.SwitchMaterial
import org.json.JSONObject

/** Shared connection options for the quick connection and saved device editor. */
class AdvancedOptions(private val ui: DesktopWidgets) {
    fun build(parent: LinearLayout, draft: JSONObject, vnt: Boolean, changed: () -> Unit) {
        fun select(title: String, key: String, labels: List<String>, values: List<Any>, default: Any) {
            val selected=values.indexOf(draft.opt(key) ?: default).coerceAtLeast(0)
            if(key in setOf("transport","codec","fps","bitrate")) {
                parent.addView(ui.label(title,12f,R.color.vnt_text_2));ui.space(parent,7)
                lateinit var rail: LinearLayout
                rail=ui.segments(parent,labels,selected) { position ->
                    draft.put(key,values[position]);changed()
                    for(i in 0 until rail.childCount) (rail.getChildAt(i) as MaterialButton).apply {
                        isSelected=i==position
                        setTextColor(ui.color(if(isSelected)R.color.vnt_brand else R.color.vnt_text_2))
                        backgroundTintList=ColorStateList.valueOf(ui.color(if(isSelected)R.color.vnt_surface else R.color.vnt_surface_2))
                    }
                }
                ui.space(parent,14);return
            }
            val spinner=ui.spinner(parent,title,labels,selected)
            var initialized=false
            spinner.onItemSelectedListener=object: AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(p: AdapterView<*>?) = Unit
                override fun onItemSelected(p: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if(!initialized) { initialized=true; return }
                    draft.put(key,values[position]);changed()
                }
            }
            ui.space(parent,14)
        }
        fun toggle(title:String, description:String, key:String, default:Boolean) {
            val row=ui.row(); val words=ui.column()
            words.addView(ui.label(title,14f));words.addView(ui.label(description,12f,R.color.vnt_text_3))
            row.addView(words,LinearLayout.LayoutParams(0,-2,1f).apply{marginEnd=ui.dp(12)})
            val control=SwitchMaterial(ui.context).apply {contentDescription=title;isChecked=draft.optBoolean(key,default)}
            control.setOnCheckedChangeListener { _,checked -> draft.put(key,checked);changed() }
            row.addView(control);parent.addView(row,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=ui.dp(16)})
        }
        if(vnt) {parent.addView(ui.label("通信协议  ·  VNT 通道",13f,R.color.vnt_text_2));ui.space(parent,14)}
        else select("通信协议","transport",listOf("混合 TCP + UDP","纯 TCP","QUIC"),listOf("hybrid","tcp","quic"),"quic")
        toggle("自适应 FEC","丢包时稳定画面（纯 TCP 不使用）","fec",ConnectionDefaults.FEC)
        select("视频编码","codec",listOf("自动协商","H.264","H.265","AV1"),listOf("auto","h264","hevc","av1"),"auto")
        select("目标帧率","fps",listOf("30 fps","60 fps"),listOf(30,60),ConnectionDefaults.FPS)
        select("目标码率","bitrate",listOf("8 Mbps","16 Mbps","30 Mbps","50 Mbps"),listOf(8000,16000,30000,50000),ConnectionDefaults.BITRATE_KBPS)
        select("颜色深度","colorDepth",listOf("15 位","16 位","24 位","32 位"),listOf(15,16,24,32),24)
        select("分辨率模式","resolutionMode",listOf("不处理分辨率","缩放分辨率","修改被控分辨率"),listOf("none","scale","change"),"scale")
        select("目标分辨率","width",listOf("1920 × 1080","1280 × 720","2560 × 1440","3840 × 2160"),listOf(1920,1280,2560,3840),1920)
        toggle("桌面背景","关闭时隐藏被控桌面背景","showWallpaper",true)
        toggle("拖动时显示窗口内容","关闭时仅显示窗口边框","dragFullWindows",false)
        toggle("硬件解码","优先硬件解码，失败时回退系统 H.264 解码器","hardwareDecode",true)
        toggle("音频播放","同步远端设备的声音","audio",true)
        toggle("剪贴板同步","与远程设备互相同步文本","clipboard",true)
    }
}
