package com.rustvnt.remote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.rustvnt.remote.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var model: RemoteModel
    private lateinit var input: InputBatch
    private lateinit var clipboard: ClipboardManager
    private lateinit var widgets: DesktopWidgets
    private lateinit var workspace: WorkspaceUi
    private lateinit var workspaceState: WorkspaceState
    private var remotePage = false
    private var appliedFullscreen: Boolean? = null
    private var centerToast: Toast? = null
    private var textDialog = false
    private var applyingClipboard = false
    private var lastClipboard: String? = null
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!applyingClipboard && model.foreground && model.info?.optBoolean("clipboardEnabled") == true && !model.viewOnly) {
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            if (text != null && text != lastClipboard) { lastClipboard = text; model.clipboard(text) }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater); setContentView(binding.root)
        model = ViewModelProvider(this)[RemoteModel::class.java]
        input = InputBatch(model); binding.remoteCanvas.bind(model,input)
        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        widgets = DesktopWidgets(this)
        workspaceState = ViewModelProvider(this)[WorkspaceState::class.java]
        savedInstanceState?.let {
            workspaceState.page = it.getInt("workspacePage", workspaceState.page)
        }
        workspace = WorkspaceUi(this, binding, model, workspaceState)
        remotePage = model.info != null
        binding.leaveRemote.setOnClickListener { disconnectDialog() }
        binding.remoteKeyboard.bind(
            allowed = { model.canInput && window.decorView.hasWindowFocus() },
            route = { "${model.epoch}:${model.activeStream}:${model.screen?.optInt("generation")}:${model.revision}" },
            send = { scan, down -> input.key(scan.code, scan.extended, down) }
        )
        binding.remoteKeyboard.close = { showKeyboard(false) }
        binding.fullscreenKeyboard.setOnClickListener { showKeyboard(!workspaceState.keyboardVisible) }
        binding.fullscreenExit.setOnClickListener { setFullscreen(false) }
        binding.movePicture.setOnClickListener {
            if(model.info!=null) {
                val checked=binding.movePicture.isChecked
                binding.remoteCanvas.movePicture=checked
                showCenterToast(if(checked)"移动画面已开启：双指滑动移动画面" else "移动画面已关闭：双指滑动滚动远端")
            } else updateViewControls()
        }
        binding.fullscreenMenu.setOnClickListener {
            binding.remoteCanvas.release()
            workspaceState.fullscreenActions=!workspaceState.fullscreenActions
            applyRemoteLayout(true)
        }
        onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (remotePage && workspaceState.keyboardVisible) { showKeyboard(false); return }; if (remotePage && workspaceState.remoteFullscreen) {setFullscreen(false);return}; if (!remotePage && workspace.goBack()) return; if (model.info != null || model.connecting) disconnectDialog() else finish() }
        })
        buildRemoteActions(); showHome(); update("initial")
        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf("android.permission.ACCESS_LOCAL_NETWORK"), 7)
        }
    }
    override fun onStart() { super.onStart(); model.listener = ::update; clipboard.addPrimaryClipChangedListener(clipboardListener); model.foreground(true); update("start") }
    override fun onStop() {
        binding.remoteKeyboard.cancelComposition()
        centerToast?.cancel();centerToast=null
        binding.remoteCanvas.release(); clipboard.removePrimaryClipChangedListener(clipboardListener)
        if (!isChangingConfigurations) model.foreground(false)
        model.listener = null; super.onStop()
    }
    override fun onDestroy() { binding.remoteCanvas.onViewChanged=null; input.clear(); binding.remoteCanvas.release(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("workspacePage",workspaceState.page)
        super.onSaveInstanceState(outState)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && ::input.isInitialized) { binding.remoteKeyboard.cancelComposition(); binding.remoteCanvas.release(); updateKeyboard() }
        if (hasFocus && ::workspaceState.isInitialized && ::binding.isInitialized) {
            appliedFullscreen=null
            applyRemoteLayout(remotePage && (model.info!=null || model.connecting))
            updateKeyboard()
        }
    }
    private fun showKeyboard(visible: Boolean) {
        if (visible && !model.canInput) return
        binding.remoteCanvas.release()
        binding.remoteKeyboard.cancelComposition()
        workspaceState.keyboardVisible = visible
        updateKeyboard()
    }
    private fun updateKeyboard() {
        if (!remotePage || model.info == null) workspaceState.keyboardVisible = false
        binding.remoteKeyboard.visibility = if (workspaceState.keyboardVisible) View.VISIBLE else View.GONE
        binding.remoteKeyboard.sync(model.info?.optString("remoteSystem") ?: "")
        binding.fullscreenKeyboard.isEnabled = model.canInput
        binding.fullscreenKeyboard.isSelected = workspaceState.keyboardVisible
        binding.fullscreenKeyboard.text = if (workspaceState.keyboardVisible) "收起键盘" else "键盘"
        binding.remoteActions.findViewById<TextView>(R.id.keyboardToggle)?.text = if (workspaceState.keyboardVisible) "收起键盘" else "键盘"
    }
    private fun setFullscreen(enabled:Boolean) {
        binding.remoteKeyboard.cancelComposition()
        binding.remoteCanvas.release()
        workspaceState.remoteFullscreen=enabled
        workspaceState.fullscreenActions=false
        applyRemoteLayout(remotePage && (model.info!=null || model.connecting))
    }
    private fun applyRemoteLayout(showRemote:Boolean) {
        if (!showRemote) {workspaceState.remoteFullscreen=false;workspaceState.fullscreenActions=false}
        val fullscreen=showRemote && workspaceState.remoteFullscreen
        val orientation=if(showRemote) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (requestedOrientation!=orientation) requestedOrientation=orientation
        binding.remoteHeader.visibility=if(fullscreen)View.GONE else View.VISIBLE
        binding.remoteStats.visibility=if(fullscreen)View.GONE else View.VISIBLE
        binding.inputHint.visibility=if(fullscreen)View.GONE else View.VISIBLE
        binding.remoteActionScroll.visibility=if(fullscreen && !workspaceState.fullscreenActions)View.GONE else View.VISIBLE
        binding.fullscreenControls.visibility=if(fullscreen)View.VISIBLE else View.GONE
        val cutout=ViewCompat.getRootWindowInsets(binding.root)?.getInsets(WindowInsetsCompat.Type.displayCutout())
        val padding=widgets.dp(4)
        binding.fullscreenControls.setPadding(padding,padding,padding,padding)
        binding.remoteViewport.setPadding(
            if(fullscreen)cutout?.left ?: 0 else 0,
            if(fullscreen)cutout?.top ?: 0 else 0,
            if(fullscreen)cutout?.right ?: 0 else 0,
            if(fullscreen)cutout?.bottom ?: 0 else 0,
        )
        binding.fullscreenMenu.text=if(workspaceState.fullscreenActions)"收起操作" else "操作"
        binding.remoteActions.findViewById<MaterialButton>(R.id.fullscreenToggle)?.visibility=if(fullscreen)View.GONE else View.VISIBLE
        binding.remoteActions.findViewById<MaterialButton>(R.id.keyboardToggle)?.visibility=if(fullscreen)View.GONE else View.VISIBLE
        if (appliedFullscreen!=fullscreen) {
            // DrawerLayout keeps legacy inset margins after bars disappear; clear them
            // so the video viewport can use the entire window in immersive mode.
            binding.root.fitsSystemWindows=!fullscreen
            if(fullscreen) {
                (binding.workspaceFrame.layoutParams as androidx.drawerlayout.widget.DrawerLayout.LayoutParams).apply {setMargins(0,0,0,0)}
                binding.workspaceFrame.requestLayout()
            }
            ViewCompat.requestApplyInsets(binding.root)
            val controller=WindowCompat.getInsetsController(window,binding.root)
            controller.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if(fullscreen)controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
            appliedFullscreen=fullscreen
        }
    }
    private fun update(event: String) {
        if (event == "client://connected") remotePage = true
        if (event == "closed" || event == "client://closed") { remotePage = false; input.clear() }
        if (event == "client://closed") Toast.makeText(this, model.status, Toast.LENGTH_LONG).show()
        val showRemote = remotePage && (model.info != null || model.connecting)
        binding.homePanel.visibility = if (showRemote) View.GONE else View.VISIBLE
        binding.remotePanel.visibility = if (showRemote) View.VISIBLE else View.GONE
        workspace.setSessionVisible(showRemote)
        applyRemoteLayout(showRemote)
        val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window,binding.root).apply {
            isAppearanceLightStatusBars = !showRemote && !dark
            isAppearanceLightNavigationBars = !showRemote && !dark
        }
        binding.root.setStatusBarBackgroundColor(if(showRemote) Color.parseColor("#0F1218") else widgets.color(R.color.vnt_surface))
        binding.root.setBackgroundColor(if(showRemote) Color.parseColor("#0F1218") else widgets.color(R.color.vnt_bg))
        window.statusBarColor = if(showRemote) Color.parseColor("#0F1218") else widgets.color(R.color.vnt_surface)
        window.navigationBarColor = if(showRemote || Build.VERSION.SDK_INT<27) Color.parseColor("#0F1218") else widgets.color(R.color.vnt_bg)
        if (showRemote) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.remoteTitle.text = model.connectionName
        binding.remoteState.text = if (model.switching) "正在切屏" else model.status
        val s=model.stats; val r=model.renderStats
        binding.remoteStats.text = String.format(Locale.ROOT,"%s  ·  %d FPS  ·  RTT %d ms  ·  %d kbps  ·  排队 %.1f ms",
            model.info?.optString("codec")?.uppercase() ?: "",r.optInt("renderFps"),s.optInt("rttMs"),s.optInt("serverBitrateKbps"),r.optDouble("queueMs",0.0))
        model.screen?.let { binding.remoteCanvas.remoteSize(it.optInt("width",1920),it.optInt("height",1080)) }
        updateViewControls()
        for (i in 0 until binding.remoteActions.childCount) {
            val action = binding.remoteActions.getChildAt(i)
            action.isEnabled = when (action.tag) {
                "input" -> model.canInput
                "clipboard" -> model.canInput && model.info?.optBoolean("clipboardEnabled") == true
                "sas" -> model.canInput && model.info?.optString("hostEdition") == "installed"
                else -> true
            }
        }
        updateKeyboard()
        if (event == "client://clipboard" && model.foreground) {
            model.clipboardText?.let { text ->
                applyingClipboard=true; lastClipboard=text; clipboard.setPrimaryClip(ClipData.newPlainText("VNT Remote",text)); applyingClipboard=false
            }
        }
        if (!showRemote) workspace.onModelEvent(event)
        if (model.error.isNotEmpty()) { Toast.makeText(this,model.error,Toast.LENGTH_LONG).show(); model.clearError() }
    }
    private fun label(text: String, size: Float = 15f) = widgets.label(text,size)
    private fun button(text: String, action: () -> Unit) = widgets.button(text,DesktopWidgets.ButtonKind.GHOST,action=action)
    private fun field(parent: LinearLayout, title: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT) = widgets.field(parent,title,value,type)
    private fun form(): LinearLayout = widgets.column(22)
    private fun scroll(form: LinearLayout) = ScrollView(this).apply {addView(form)}
    private fun showHome() = workspace.render()
    private fun buildRemoteActions() {
        val actions=binding.remoteActions
        actions.addView(button("全屏") {setFullscreen(true)}.apply {id=R.id.fullscreenToggle})
        actions.addView(button("适应窗口") {binding.remoteCanvas.resetZoom()}.apply {id=R.id.zoomFit})
        actions.addView(button("触控板") {
            val canvas=binding.remoteCanvas
            canvas.trackpad=!canvas.trackpad
            showCenterToast(if(canvas.trackpad) "触控板：按住悬浮左右键可拖动；拖动鼠标底部调整位置" else "直接触控：轻点点击，滑动拖动，长按右键；双指捏合缩放")
        }.apply {id=R.id.pointerMode})
        actions.addView(button("键盘") {showKeyboard(!workspaceState.keyboardVisible)}.apply {id=R.id.keyboardToggle; tag="input"})
        actions.addView(button("按键") {keyDialog()}.apply{tag="input"})
        actions.addView(button("发送文本") {sendText()}.apply{tag="clipboard"})
        actions.addView(button("剪贴板 ↑") {clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.let {model.clipboard(it)}}.apply{tag="clipboard"})
        actions.addView(button("剪贴板 ↓") {model.clipboard()}.apply{tag="clipboard"})
        actions.addView(button("Ctrl+Alt+Del") {model.command("sas")}.apply{tag="sas"})
        for (i in 0 until actions.childCount) (actions.getChildAt(i) as? MaterialButton)?.apply {
            setTextColor(Color.parseColor("#A2ABBD")); iconTint=android.content.res.ColorStateList.valueOf(Color.parseColor("#A2ABBD"))
        }
        binding.remoteCanvas.onViewChanged=::updateViewControls
        updateViewControls()
    }
    private fun updateViewControls() {
        val canvas=binding.remoteCanvas
        canvas.syncInputAvailability()
        val pointerTitle=if(canvas.trackpad)"触控板" else "直接触控"
        binding.remoteActions.findViewById<TextView>(R.id.pointerMode)?.let {if(it.text.toString()!=pointerTitle)it.text=pointerTitle}
        if(binding.movePicture.isChecked!=canvas.movePicture) {
            binding.movePicture.isChecked=canvas.movePicture
        }
        val gestures=if(canvas.movePicture)"双指平移画面 · 捏合缩放" else "双指滚动远端 · 捏合缩放"
        val hint=when {
            model.viewOnly && model.info!=null -> if(canvas.movePicture)"仅观看 · 双指平移画面 · 捏合缩放" else "仅观看 · 捏合缩放 · 开启移动画面可双指平移"
            canvas.trackpad -> "单指移动 · 点击悬浮鼠标左右键 · 按住按键拖动 · 拖动底部调整位置 · $gestures"
            else -> "轻点点击 · 滑动拖动 · 长按右键 · $gestures"
        }
        if(binding.inputHint.text.toString()!=hint)binding.inputHint.text=hint
    }
    @Suppress("DEPRECATION")
    private fun showCenterToast(message:String) {
        centerToast?.cancel()
        val content=TextView(this).apply {
            text=message;textSize=14f;setTextColor(Color.WHITE);gravity=android.view.Gravity.CENTER
            maxWidth=resources.displayMetrics.widthPixels-widgets.dp(48)
            setPadding(widgets.dp(18),widgets.dp(12),widgets.dp(18),widgets.dp(12))
            background=android.graphics.drawable.GradientDrawable().apply {setColor(Color.parseColor("#ED202630"));cornerRadius=widgets.dp(10).toFloat()}
        }
        // Text-only platform toasts ignore gravity on Android 11+; a foreground custom toast stays centered.
        centerToast=Toast(this).apply {view=content;duration=Toast.LENGTH_SHORT;setGravity(android.view.Gravity.CENTER,0,0);show()}
    }
    private fun keyDialog() {
        if(!model.canInput){Toast.makeText(this,"当前会话不能输入",Toast.LENGTH_SHORT).show();return}
        val mac = model.info?.optString("remoteSystem") == "macos"
        val meta = RemoteKeyboardState.metaLabel(model.info?.optString("remoteSystem") ?: "")
        val choices = mutableListOf(
            "Esc" to listOf(0x01 to false), "Tab" to listOf(0x0f to false),
            "Enter" to listOf(0x1c to false), "Backspace" to listOf(0x0e to false),
            "Delete" to listOf(0x53 to true), "Ctrl+C" to listOf(0x1d to false, 0x2e to false),
            "Ctrl+V" to listOf(0x1d to false, 0x2f to false), "Ctrl+A" to listOf(0x1d to false, 0x1e to false),
            "${if(mac) "Option" else "Alt"}+Tab" to listOf(0x38 to false, 0x0f to false),
            meta to listOf(0x5b to true), "F5" to listOf(0x3f to false)
        )
        if (mac) choices += "Command+V" to listOf(0x5b to true, 0x2f to false)
        val route = "${model.epoch}:${model.activeStream}:${model.screen?.optInt("generation")}:${model.revision}"
        MaterialAlertDialogBuilder(this).setTitle("发送按键").setItems(choices.map { it.first }.toTypedArray()){_,i->
            if (!model.canInput || route != "${model.epoch}:${model.activeStream}:${model.screen?.optInt("generation")}:${model.revision}") return@setItems
            val sequence=choices[i].second
            sequence.forEach{input.key(it.first,it.second,true)};sequence.reversed().forEach{input.key(it.first,it.second,false)}
        }.show()
    }

    private fun sendText() {
        if(!model.canInput||model.info?.optBoolean("clipboardEnabled")!=true){Toast.makeText(this,"需要允许控制与文本剪贴板",Toast.LENGTH_SHORT).show();return}
        val form=form();val text=field(form,"输入要发送的文本");text.edit.isSingleLine=false;text.edit.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE;text.edit.minLines=3
        textDialog=true
        MaterialAlertDialogBuilder(this).setTitle("发送文本到远端").setMessage("通过远端剪贴板粘贴，支持中文输入。").setView(scroll(form)).setNegativeButton("取消",null).setPositiveButton("发送并粘贴"){_,_->model.clipboard(text.edit.text.toString(),true)}.setOnDismissListener{textDialog=false}.show()
    }
    private fun disconnectDialog() {MaterialAlertDialogBuilder(this).setTitle("结束远程会话？").setNegativeButton("继续控制",null).setPositiveButton("断开"){_,_->binding.remoteCanvas.release();model.disconnect()}.show()}
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if(remotePage&&!textDialog&&model.canInput&&window.decorView.hasWindowFocus()){
            val scan=Keymap.scan(event.keyCode)
            if(scan!=null&&(event.action==KeyEvent.ACTION_DOWN||event.action==KeyEvent.ACTION_UP)){
                input.key(scan.code,scan.extended,event.action==KeyEvent.ACTION_DOWN);return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
