package com.rustvnt.remote

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.widget.doAfterTextChanged
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputLayout
import com.rustvnt.remote.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject

/** Native counterpart of desktop Home.vue. Session and transport ownership stay in RemoteModel. */
class WorkspaceUi(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val model: RemoteModel,
    private val state: WorkspaceState,
) {
    private val ui = DesktopWidgets(activity)
    private var updateButton: MaterialButton? = null
    private val updates = ReleaseUpdateController(activity) { updateCheckButton() }
    private val titles = listOf("远程控制", "设备列表", "设置", "服务端")
    private val wide = activity.resources.configuration.screenWidthDp >= 840
    private val navigation = listOf(binding.navRemote,binding.navDevices,binding.navSettings,binding.navServers)
    private var connectButton: MaterialButton? = null
    private var progress: LinearProgressIndicator? = null
    private var connectionStatus: TextView? = null
    private val advancedUi = AdvancedOptions(ui)
    private fun editDevice(value: JSONObject?) { state.editor=JSONObject((value ?: JSONObject()).toString()); state.editorAdvanced=false; showPage(4) }
    private fun editServer(value: JSONObject?) {
        state.editor=if(value!=null) JSONObject(value.toString()) else JSONObject()
            .put("servers",JSONArray().put("vnt2.rustvnt.com:29875"))
            .put("connectionId",(100_000_000 + java.security.SecureRandom().nextInt(900_000_000)).toString())
            .put("enabled",false)
        showPage(5)
    }

    init {
        if (wide) {
            binding.sideDrawer.removeView(binding.navigationContent)
            binding.sidebarHost.addView(binding.navigationContent)
            binding.sidebarHost.visibility=View.VISIBLE
            binding.sideDrawer.visibility=View.GONE
            binding.openNavigation.visibility=View.GONE
            binding.toolbarLogo.visibility=View.GONE
            binding.toolbarBrand.visibility=View.GONE
            binding.toolbarPage.gravity=Gravity.START
            binding.toolbarPage.setPadding(ui.dp(8),0,0,0)
            binding.navigationContent.getChildAt(0).layoutParams.height=ui.dp(56)
        }
        binding.openNavigation.setOnClickListener {
            if(state.page==4 || state.page==5) goBack() else binding.root.openDrawer(GravityCompat.START)
        }
        navigation.forEachIndexed { index,button -> button.setOnClickListener { showPage(index);closeDrawer() } }
        setSessionVisible(false)
    }
    fun closeDrawer(): Boolean {
        if (!wide && binding.root.isDrawerOpen(GravityCompat.START)) {binding.root.closeDrawer(GravityCompat.START);return true}
        return false
    }
    fun goBack(): Boolean {
        if (closeDrawer()) return true
        if(state.page==4 || state.page==5){showPage(if(state.page==4)1 else 3);return true}
        if(state.page!=0){showPage(0);return true}
        return false
    }
    fun setSessionVisible(visible:Boolean) {
        binding.root.setDrawerLockMode(if(wide || visible)DrawerLayout.LOCK_MODE_LOCKED_CLOSED else DrawerLayout.LOCK_MODE_UNLOCKED)
    }
    fun showPage(page:Int) {state.page=page;render();binding.homeScroll.scrollTo(0,0)}
    fun onModelEvent(event:String) {
        if(event=="client://connecting" || event=="connecting")state.connectionError=""
        if(event=="client://closed" && model.status!="已断开")state.connectionError=model.status
        if(event=="error" || event=="client://error")state.connectionError=model.error
        val changes=event in listOf("connections","profiles","closed","client://closed")
        if(state.page < 4 && (changes || (event=="networks" && state.page==3))) render()
        else updateConnectionStatus()
    }
    fun render() {
        val content=binding.homeContent;content.removeAllViews()
        connectButton=null;progress=null;connectionStatus=null;updateButton=null
        val editing=state.page==4 || state.page==5
        binding.sidebarHost.visibility=if(wide && !editing)View.VISIBLE else View.GONE
        binding.openNavigation.visibility=if(!wide || editing)View.VISIBLE else View.GONE
        binding.openNavigation.setIconResource(if(editing)R.drawable.ic_back else R.drawable.ic_menu)
        binding.openNavigation.contentDescription=if(editing)"返回" else "打开侧边栏"
        binding.toolbarLogo.visibility=if(!wide && !editing)View.VISIBLE else View.GONE
        binding.toolbarBrand.visibility=if(!wide && !editing)View.VISIBLE else View.GONE
        binding.toolbarPage.gravity=if(editing || wide)Gravity.START else Gravity.END
        binding.toolbarPage.text=when(state.page){4->if(state.editor.has("id"))"编辑设备" else "添加设备";5->if(state.editor.has("id"))"编辑服务端配置" else "添加服务端配置";else->titles[state.page]}
        navigation.forEachIndexed { index,button ->
            val selected=index==state.page || (state.page==4 && index==1) || (state.page==5 && index==3)
            button.isSelected=selected
            button.setTextColor(ui.color(if(selected)R.color.vnt_brand else R.color.vnt_text_2))
            button.iconTint=ColorStateList.valueOf(ui.color(if(selected)R.color.vnt_brand else R.color.vnt_text_2))
            button.backgroundTintList=ColorStateList.valueOf(if(selected)ui.color(R.color.vnt_brand_tint) else android.graphics.Color.TRANSPARENT)
        }
        when(state.page){0->remoteControl(content);1->devices(content);2->settings(content);3->servers(content);4->deviceEditor(content);5->serverEditor(content)}
        updateConnectionStatus()
    }
    private fun array(array:JSONArray)= (0 until array.length()).map {array.getJSONObject(it)}
    private fun onlineGroups()=array(model.networks).filter {it.optBoolean("enabled")}
    private fun updateConnectionStatus() {
        connectButton?.apply {isEnabled=!model.connecting;text=if(model.connecting)"正在连接…" else "连接"}
        progress?.visibility=if(model.connecting)View.VISIBLE else View.GONE
        connectionStatus?.apply {
            text=if(model.connecting)model.status else state.connectionError
            setTextColor(ui.color(if(model.connecting)R.color.vnt_text_2 else R.color.vnt_danger))
            visibility=if(text.isNullOrBlank())View.GONE else View.VISIBLE
        }
    }
    private fun remoteControl(content:LinearLayout) {
        ui.panel(content) {
            ui.cardHeading(this,"连接远程设备","输入地址与访问密码，或选择已保存设备。")
            val address=ui.field(this,"设备地址",state.address,InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            address.edit.id=R.id.quickAddress
            address.edit.hint="IP 或域名，可加 :端口"
            address.wrapper.startIconDrawable=androidx.appcompat.content.res.AppCompatResources.getDrawable(activity,R.drawable.ic_monitor)
            address.wrapper.endIconMode=TextInputLayout.END_ICON_CUSTOM
            address.wrapper.endIconDrawable=androidx.appcompat.content.res.AppCompatResources.getDrawable(activity,R.drawable.ic_chevron)
            address.wrapper.endIconContentDescription="从已保存设备或同组设备中选择"
            address.wrapper.setEndIconOnClickListener {chooseDevice()}
            address.edit.doAfterTextChanged {
                state.address=it.toString()
                val selected=state.selectedDevice
                if(selected!=null && state.address!=displayAddress(selected))state.selectedDevice=null
                state.connectionError="";address.wrapper.error=null
            }
            val password=ui.field(this,"访问密码",state.password,InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            password.edit.id=R.id.quickPassword
            password.edit.hint="未设置访问密码时可留空"
            password.wrapper.endIconMode=TextInputLayout.END_ICON_PASSWORD_TOGGLE
            password.edit.doAfterTextChanged {state.password=it.toString()}
            ui.space(this,14)
            connectButton=ui.button("连接",DesktopWidgets.ButtonKind.PRIMARY,R.drawable.ic_arrow) {
                try {
                    state.connectionError=""
                    val entry=connectionDraft()
                    model.connect(entry)
                } catch(e:IllegalArgumentException) {state.connectionError=e.message?:"地址格式无效";address.wrapper.error=state.connectionError;updateConnectionStatus()}
            }.also {addView(it,LinearLayout.LayoutParams(-1,ui.dp(52)))}
            progress=LinearProgressIndicator(activity).apply {isIndeterminate=true;visibility=View.GONE;addView(this,LinearLayout.LayoutParams(-1,-2).apply{topMargin=ui.dp(12)})}
            connectionStatus=ui.label("",12f,R.color.vnt_danger).apply {visibility=View.GONE;addView(this,LinearLayout.LayoutParams(-1,-2).apply{topMargin=ui.dp(10)})}
            ui.divider(this)
            val advanced=ui.button("高级选项",DesktopWidgets.ButtonKind.GHOST,R.drawable.ic_chevron) {}
            advanced.contentDescription="展开或收起高级选项";advanced.isSelected=state.advanced
            addView(advanced,LinearLayout.LayoutParams(-1,-2))
            val body=ui.column();addView(body)
            body.visibility=if(state.advanced)View.VISIBLE else View.GONE
            advanced.setOnClickListener {state.advanced=!state.advanced;advanced.isSelected=state.advanced;body.visibility=if(state.advanced)View.VISIBLE else View.GONE}
            advancedOptions(body, JSONObject(model.settings.toString()).put("transport",state.transport))
        }
    }

    private fun connectionDraft():JSONObject {
        val selected=state.selectedDevice
        if(selected?.optString("transport")=="vnt")return JSONObject(selected.toString()).put("password",state.password).also {it.remove("quality")}
        val parsed=ConnectionAddress.parse(state.address)
        return JSONObject().put("host",parsed.host).put("port",parsed.port).put("password",state.password)
            .put("transport",state.transport).put("name",selected?.optString("name")?.ifEmpty{parsed.host}?:parsed.host)
    }
    private fun displayAddress(entry:JSONObject):String = if(entry.optString("transport")=="vnt")entry.optString("connectionId",entry.optString("serverNodeId"))
        else ConnectionAddress.format(entry.optString("host"),entry.optInt("port",29875))
    private fun useDevice(entry:JSONObject, connect:Boolean=false) {
        state.selectedDevice=JSONObject(entry.toString());state.address=displayAddress(entry);state.password=entry.optString("password")
        if(entry.optString("transport")!="vnt")state.transport=entry.optString("transport","quic")
        entry.optJSONObject("quality")?.let { saved ->
            val merged=JSONObject(model.settings.toString());saved.keys().forEach { key -> if(key!="transport")merged.put(key,saved.get(key)) }
            model.saveSettings(merged)
        }
        state.connectionError="";showPage(0)
        if(connect)model.connect(entry)
    }
    private fun onlineEntry(profile:JSONObject,device:JSONObject):JSONObject = JSONObject()
        .put("name",device.optString("name")).put("transport","vnt").put("host","vnt").put("port",29875)
        .put("serverConfigId",profile.optString("configId")).put("serverNodeId",device.optString("id"))
        .put("serverNetworkCode",profile.optString("networkCode")).put("connectionId",device.optString("connectionId")).put("password","")
    private fun chooseDevice() {
        val entries=mutableListOf<Pair<String,JSONObject>>()
        array(model.saved).forEach {entries.add("${it.optString("name")}  ·  ${displayAddress(it)}" to it)}
        onlineGroups().forEach {group->array(group.optJSONArray("devices")?:JSONArray()).forEach {device->
            entries.add("${group.optString("name")} / ${device.optString("name")}  ·  ${device.optString("connectionId")}" to onlineEntry(group,device))
        }}
        val dialog=MaterialAlertDialogBuilder(activity).setTitle("选择远程设备").setNegativeButton("取消",null)
        if(entries.isEmpty())dialog.setMessage("暂无已保存或同组在线设备。可在设备列表添加设备，或在服务端中配置网络。")
        else dialog.setItems(entries.map{it.first}.toTypedArray()){_,index->useDevice(entries[index].second)}
        dialog.show()
    }
    private fun advancedOptions(parent:LinearLayout, draft:JSONObject) {
        advancedUi.build(parent,draft,state.selectedDevice?.optString("transport")=="vnt") {
            state.transport=draft.optString("transport",state.transport)
            model.saveSettings(JSONObject(draft.toString()).also { it.remove("transport") })
        }
    }
    private fun devices(content:LinearLayout) {
        ui.heading(content,"设备列表","保存常用设备的地址与连接方式，一键发起连接。")
        content.addView(ui.button("添加设备",DesktopWidgets.ButtonKind.PRIMARY,R.drawable.ic_plus){editDevice(null)},LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=ui.dp(20)})
        content.addView(ui.label("本地设备配置",12f,R.color.vnt_text_3));ui.space(content,12)
        val saved=array(model.saved)
        if(saved.isEmpty())emptyState(content,"暂无保存的设备","添加设备后，可在这里快速连接与管理。",R.drawable.ic_monitor,"添加设备"){editDevice(null)}
        saved.forEachIndexed {index,entry->
            ui.panel(content) {
                ui.cardHeading(this,entry.optString("name","远程设备"),displayAddress(entry),R.drawable.ic_monitor)
                val tags=ui.row();tags.addView(ui.badge(transportLabel(entry.optString("transport"))))
                tags.addView(ui.badge("自动协商"),LinearLayout.LayoutParams(-2,-2).apply{marginStart=ui.dp(8)})
                addView(tags);ui.space(this,16)
                val actions=ui.row()
                actions.addView(ui.button("连接",DesktopWidgets.ButtonKind.PRIMARY,R.drawable.ic_arrow){useDevice(entry,true)},LinearLayout.LayoutParams(0,-2,1f))
                actions.addView(ui.button("编辑",DesktopWidgets.ButtonKind.GHOST,R.drawable.ic_edit){editDevice(entry)})
                actions.addView(ui.button("删除",DesktopWidgets.ButtonKind.GHOST){
                    MaterialAlertDialogBuilder(activity).setTitle("删除设备？").setMessage("将移除 ${entry.optString("name")} 的本地连接配置。").setNegativeButton("取消",null).setPositiveButton("删除"){_,_->model.deleteConnection(index)}.show()
                })
                addView(actions)
            }
        }
    }
    private fun draftField(parent:LinearLayout, title:String, key:String, default:String="", type:Int=InputType.TYPE_CLASS_TEXT):DesktopWidgets.Field {
        if(!state.editor.has(key)) state.editor.put(key,default)
        val field=ui.field(parent,title,state.editor.optString(key,default),type)
        field.edit.doAfterTextChanged { state.editor.put(key,it.toString()) }
        return field
    }
    private fun deviceEditor(content:LinearLayout) {
        val entry=state.editor;val isVnt=entry.optString("transport")=="vnt"
        ui.panel(content) {
            ui.cardHeading(this,"设备信息",glyph=R.drawable.ic_monitor)
            val name=draftField(this,"设备名称","name")
            val host=if(!isVnt)draftField(this,"主机地址 / IPv6（不含端口）","host",type=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) else null
            val port=if(!isVnt)draftField(this,"端口","portText",entry.optInt("port",29875).toString(),InputType.TYPE_CLASS_NUMBER) else null
            name.edit.id=R.id.deviceName;host?.edit?.id=R.id.deviceHost;port?.edit?.id=R.id.devicePort
            val password=draftField(this,"访问密码","password",type=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            password.wrapper.endIconMode=TextInputLayout.END_ICON_PASSWORD_TOGGLE
            ui.divider(this)
            val advanced=ui.button("高级选项",DesktopWidgets.ButtonKind.GHOST,R.drawable.ic_chevron){}
            addView(advanced,LinearLayout.LayoutParams(-1,-2))
            val body=ui.column();addView(body);body.visibility=if(state.editorAdvanced)View.VISIBLE else View.GONE
            advanced.setOnClickListener {state.editorAdvanced=!state.editorAdvanced;body.visibility=if(state.editorAdvanced)View.VISIBLE else View.GONE}
            val quality=entry.optJSONObject("quality") ?: JSONObject(model.settings.toString()).also{entry.put("quality",it)}
            if(!isVnt)quality.put("transport",entry.optString("transport",quality.optString("transport","quic")))
            advancedUi.build(body,quality,isVnt) {if(!isVnt)entry.put("transport",quality.optString("transport","quic"))}
            ui.divider(this)
            fun save(connect:Boolean) {
                val hostname=host?.edit?.text?.toString()?.trim().orEmpty()
                val portValue=port?.edit?.text?.toString()?.toIntOrNull() ?: if(isVnt)29875 else 0
                if(!isVnt && (hostname.isEmpty() || hostname.any{it.isWhitespace() || it=='/' || it=='\\'})) {host?.wrapper?.error="请输入有效的地址";return}
                if(portValue !in 1..65535){port?.wrapper?.error="端口须为 1–65535";return}
                val value=JSONObject(entry.toString()).put("name",name.edit.text.toString().trim().ifEmpty{if(isVnt)"VNT 设备" else hostname})
                    .put("password",password.edit.text.toString()).put("port",portValue)
                value.remove("portText")
                if(!isVnt)value.put("host",hostname.removeSurrounding("[","]")).put("transport",quality.optString("transport","quic"))
                try {model.saveConnection(value);showPage(1);if(connect)useDevice(value,true)} catch(e:Exception){name.wrapper.error=e.message}
            }
            addView(ui.button("保存配置",DesktopWidgets.ButtonKind.PRIMARY,R.drawable.ic_check){save(false)}.apply {id=R.id.deviceSave},LinearLayout.LayoutParams(-1,-2))
            addView(ui.button("保存并连接",DesktopWidgets.ButtonKind.SECONDARY){save(true)},LinearLayout.LayoutParams(-1,-2).apply{topMargin=ui.dp(8)})
        }
    }
    private fun serverEditor(content:LinearLayout) {
        val value=state.editor
        ui.panel(content) {
            ui.cardHeading(this,"服务端配置",glyph=R.drawable.ic_server)
            val name=draftField(this,"配置名称","name");name.edit.id=R.id.serverName
            draftField(this,"本机显示名称","deviceName",android.os.Build.MODEL)
            val mode=ui.spinner(this,"配置方式",listOf("手动配置","订阅地址"),if(value.optString("mode")=="subscription")1 else 0)
            val subscription=draftField(this,"订阅地址","subscription",type=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            val initialServers=value.optJSONArray("servers")?.let{a->(0 until a.length()).joinToString(","){a.getString(it)}} ?: ""
            val server=draftField(this,"服务器地址（多个以逗号分隔）","serversText",initialServers,InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI);server.edit.id=R.id.serverAddress
            val room=draftField(this,"房间编号","networkCode");room.edit.id=R.id.serverRoom
            val id=draftField(this,"本机连接 ID（1–9 位数字）","connectionId",type=InputType.TYPE_CLASS_NUMBER);id.edit.id=R.id.serverConnectionId
            val password=draftField(this,"加密密码","encryptionPassword",type=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            password.wrapper.endIconMode=TextInputLayout.END_ICON_PASSWORD_TOGGLE
            fun refresh(){val manual=mode.selectedItemPosition==0;subscription.visible(!manual);listOf(server,room,id,password).forEach{it.visible(manual)}}
            mode.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(p:AdapterView<*>?)=Unit
                override fun onItemSelected(p:AdapterView<*>?,v:View?,position:Int,row:Long){value.put("mode",if(position==0)"manual" else "subscription");refresh()}
            }
            refresh();ui.divider(this)
            addView(ui.button("保存",DesktopWidgets.ButtonKind.PRIMARY,R.drawable.ic_check){
                val manual=mode.selectedItemPosition==0;val title=name.edit.text.toString().trim()
                val servers=server.edit.text.toString().split(',', '，','\n').map{it.trim()}.filter{it.isNotEmpty()}
                if(title.isBlank()||title.length>64){name.wrapper.error="填写 1–64 字符的名称";return@button}
                if(!manual&&subscription.edit.text.isNullOrBlank()){subscription.wrapper.error="请填写订阅地址";return@button}
                if(manual&&(servers.isEmpty()||room.edit.text.isNullOrBlank()||!id.edit.text.toString().matches(Regex("[0-9]{1,9}")))){id.wrapper.error="请填写服务器、房间及有效连接 ID";return@button}
                val updated=JSONObject(value.toString()).put("name",title).put("enabled",value.optBoolean("enabled",false))
                    .put("mode",if(manual)"manual" else "subscription").put("servers",JSONArray(servers))
                updated.remove("serversText")
                try{model.saveProfile(updated);showPage(3)}catch(e:Exception){name.wrapper.error=e.message}
            }.apply {this.id=R.id.serverSave},LinearLayout.LayoutParams(-1,-2))
        }
    }
    private fun servers(content:LinearLayout) {
        ui.heading(content,"服务端","管理服务器连接，在多个网络中发现远程设备。")
        val statuses=array(model.networks);val count=statuses.count{it.optString("phase")=="connected"}
        val summary=ui.row();summary.addView(ui.badge("$count 个已连接",count>0))
        summary.addView(Space(activity),LinearLayout.LayoutParams(0,1,1f))
        summary.addView(ui.button("添加配置",DesktopWidgets.ButtonKind.PRIMARY,R.drawable.ic_plus){editServer(null)})
        content.addView(summary);ui.space(content,20)
        val profiles=array(model.profiles)
        if(profiles.isEmpty())emptyState(content,"暂无服务端配置","添加服务器地址或订阅，连接你的设备网络。",R.drawable.ic_server,"添加配置"){editServer(null)}
        profiles.forEachIndexed {index,profile->
            val status=statuses.firstOrNull{it.optString("configId")==profile.optString("id")}
            ui.panel(content) {
                val header=ui.row();header.addView(ui.label(profile.optString("name"),16f,bold=true),LinearLayout.LayoutParams(0,-2,1f))
                header.addView(ui.badge(phase(status?.optString("phase")),status?.optString("phase")=="connected"));addView(header)
                ui.space(this,8);addView(ui.label(if(profile.optString("mode")=="subscription")"订阅配置" else profile.optJSONArray("servers")?.optString(0).orEmpty(),12f,R.color.vnt_text_3))
                ui.divider(this)
                val info=ui.row();val room=ui.column();room.addView(ui.label("房间编号",11f,R.color.vnt_text_3));room.addView(ui.label(status?.optString("networkCode")?.ifEmpty{profile.optString("networkCode")}?:profile.optString("networkCode"),14f))
                info.addView(room,LinearLayout.LayoutParams(0,-2,1f));val peers=ui.column();peers.addView(ui.label("在线设备",11f,R.color.vnt_text_3));peers.addView(ui.label("${status?.optJSONArray("devices")?.length()?:0} 台",14f));info.addView(peers,LinearLayout.LayoutParams(0,-2,1f));addView(info)
                val error=status?.optString("error").orEmpty()
                if(error.isNotBlank()&&error!="null"){ui.space(this,10);addView(ui.label(error,12f,R.color.vnt_danger))}
                ui.divider(this)
                addView(ui.label("本机连接 ID  ${status?.optString("localConnectionId")?.ifEmpty{"尚未连接"}?:"尚未连接"}",12f,R.color.vnt_text_3).apply{typeface=Typeface.MONOSPACE})
                ui.space(this,10)
                val actions=ui.row()
                actions.addView(ui.button("编辑",DesktopWidgets.ButtonKind.GHOST,R.drawable.ic_edit){editServer(profile)})
                actions.addView(ui.button("删除",DesktopWidgets.ButtonKind.GHOST){MaterialAlertDialogBuilder(activity).setTitle("删除服务端配置？").setMessage("此操作会停止该网络连接。").setNegativeButton("取消",null).setPositiveButton("删除"){_,_->model.deleteProfile(index)}.show()})
                actions.addView(Space(activity),LinearLayout.LayoutParams(0,1,1f))
                actions.addView(ui.button(if(profile.optBoolean("enabled"))"停止" else "启动",if(profile.optBoolean("enabled"))DesktopWidgets.ButtonKind.SECONDARY else DesktopWidgets.ButtonKind.PRIMARY){
                    try{model.saveProfile(JSONObject(profile.toString()).put("enabled",!profile.optBoolean("enabled")))}catch(e:Exception){Toast.makeText(activity,e.message,Toast.LENGTH_LONG).show()}
                });addView(actions)
            }
        }
    }
    private fun phase(value:String?)=when(value){"connected"->"已连接";"connecting"->"连接中";"reconnecting"->"重新连接";"error"->"连接失败";else->"已停止"}
    private fun transportLabel(value:String)=when(value){"vnt"->"VNT 通道";"tcp"->"纯 TCP";"hybrid"->"混合 TCP + UDP";else->"QUIC"}
    private fun emptyState(parent:LinearLayout,title:String,subtitle:String,icon:Int,action:String,clicked:()->Unit) {
        ui.panel(parent) {
            val tile=FrameLayout(activity).apply{background=ui.background(R.color.vnt_surface_2,radius=12)}
            tile.addView(ui.icon(icon,R.color.vnt_text_3,24),FrameLayout.LayoutParams(ui.dp(24),ui.dp(24),Gravity.CENTER))
            addView(tile,LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));ui.space(this,14)
            addView(ui.label(title,16f,bold=true));ui.space(this,6);addView(ui.label(subtitle,13f,R.color.vnt_text_2));ui.space(this,14)
            addView(ui.button(action,DesktopWidgets.ButtonKind.SECONDARY,R.drawable.ic_plus,clicked))
        }
    }
    private fun updateCheckButton() {
        updateButton?.apply {
            isEnabled=!updates.checking
            text=if(updates.checking)"正在检查…" else "检查更新"
        }
    }
    private fun settings(content:LinearLayout) {
        ui.heading(content,"设置","设置应用外观与检查更新。")
        ui.panel(content) {
            ui.cardHeading(this,"通用",glyph=R.drawable.ic_settings)
            addView(ui.label("主题",14f,bold=true));ui.space(this,4)
            addView(ui.label("主界面跟随此设置；远程控制画面始终保持深色。",12f,R.color.vnt_text_2));ui.space(this,12)
            ui.segments(this,listOf("浅色","深色","跟随系统"),listOf("light","dark","system").indexOf(Appearance.mode(activity)).coerceAtLeast(0)) {Appearance.set(activity,listOf("light","dark","system")[it])}
        }
        ui.panel(content) {
            ui.cardHeading(this,"VNT Remote","${updates.currentVersion} · Android 主控",R.drawable.ic_monitor)
            addView(ui.label("进入后台会暂停画面与声音，返回前台恢复连接。",13f,R.color.vnt_text_2))
            ui.space(this,12)
            addView(ui.label("从 GitHub 获取最新正式版本，下载后安装更新。",12f,R.color.vnt_text_2))
            ui.space(this,8)
            updateButton=ui.button("检查更新",DesktopWidgets.ButtonKind.SECONDARY) { updates.check() }
            addView(updateButton)
            updateCheckButton()
        }
    }
}
