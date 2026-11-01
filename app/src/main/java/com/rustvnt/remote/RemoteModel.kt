package com.rustvnt.remote

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

class RemoteModel(application: Application) : AndroidViewModel(application), NativeBridge.Listener {
    val viewState = RemoteViewState()
    private val store = SecureStore(application)
    val bridge = NativeBridge(application.filesDir.absolutePath, Build.VERSION.SDK_INT, this)
    private val probeWorker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var probeGeneration = 0L
    private var probing = false
    private val audio = AudioPlayer()
    var listener: ((String) -> Unit)? = null
    var config = JSONObject()
        private set
    var networks = JSONArray()
        private set
    var info: JSONObject? = null
        private set
    var screens = JSONArray()
        private set
    var activeStream = 0
        private set
    var revision = 0L
        private set
    var epoch = 0L
        private set
    var connecting = false
        private set
    var foreground = false
        private set
    var controlOnline = false
        private set
    private var receivedScreensState = false
    private var receivedControlState = false
    var switching = false
        private set
    var error = ""
        private set
    private val desktopReasons = mutableMapOf<Int, String>()
    private var remoteAudioReason = ""
    private var localAudioReason = ""
    var status = "准备连接"
        get() = if (info != null && controlOnline) {
            (desktopReasons[activeStream] ?: desktopReasons[0]).orEmpty()
                .ifEmpty { localAudioReason }.ifEmpty { remoteAudioReason }.ifEmpty { field }
        } else field
        private set
    var stats = JSONObject()
        private set
    var renderStats = JSONObject()
        private set
    var decoder = ""
        private set
    var clipboardText: String? = null
        private set
    var connectionName = "远程桌面"
        private set
    init {
        try { config = store.load() } catch (e: Exception) { error = "无法读取加密配置：${e.message}" }
        configureNetworks()
    }
    val profiles: JSONArray get() = config.optJSONArray("profiles") ?: JSONArray()
    val saved: JSONArray get() = config.optJSONArray("connections") ?: JSONArray()
    val settings: JSONObject get() = config.optJSONObject("settings") ?: JSONObject()
    val viewOnly: Boolean get() = info?.optBoolean("viewOnly", true) ?: true
    val canInput: Boolean get() = info != null && foreground && controlOnline && !switching && !viewOnly
    val screen: JSONObject? get() = (0 until screens.length()).map { screens.getJSONObject(it) }.firstOrNull { it.optInt("streamId") == activeStream }
    private fun send(command: JSONObject): Boolean {
        return try { bridge.command(command.toString()); true } catch (e: RuntimeException) { error = e.message ?: "原生核心不可用"; listener?.invoke("error"); false }
    }
    fun command(op: String, fill: JSONObject.() -> Unit = {}) = send(JSONObject().put("op", op).put("epoch", epoch).apply(fill))
    private fun persist() { store.save(config) }
    fun saveSettings(value: JSONObject) { config.put("settings", value); persist(); listener?.invoke("settings") }
    fun saveConnection(value: JSONObject) {
        val list = saved
        val id = value.optString("id").ifEmpty { UUID.randomUUID().toString().also { value.put("id", it) } }
        val index = (0 until list.length()).firstOrNull { list.getJSONObject(it).optString("id") == id }
        if (index == null) list.put(value) else list.put(index, value)
        config.put("connections", list); persist(); listener?.invoke("connections")
    }
    fun deleteConnection(index: Int) { val list = saved; list.remove(index); config.put("connections", list); persist(); listener?.invoke("connections") }
    fun saveProfile(value: JSONObject) {
        val list = JSONArray(profiles.toString())
        val id = value.optString("id").ifEmpty { UUID.randomUUID().toString().also { value.put("id", it) } }
        val index = (0 until list.length()).firstOrNull { list.getJSONObject(it).optString("id") == id }
        if (index == null) list.put(value) else list.put(index, value)
        bridge.command(JSONObject().put("op", "configure").put("profiles", list).toString())
        config.put("profiles", list); persist(); listener?.invoke("profiles")
    }
    fun deleteProfile(index: Int) { val list = profiles; list.remove(index); config.put("profiles", list); persist(); configureNetworks(); listener?.invoke("profiles") }
    private fun configureNetworks() = send(JSONObject().put("op", "configure").put("profiles", profiles))
    fun connect(connection: JSONObject) {
        disconnect(false)
        error = ""; connecting = true; probing = true; activeStream = 0; revision = 0; renderStats = JSONObject(); stats = JSONObject(); decoder = ""; controlOnline = false; status = "正在检测解码能力…"
        connectionName = connection.optString("name", connection.optString("host", "远程桌面"))
        listener?.invoke("connecting")
        val generation = ++probeGeneration
        val quality = JSONObject(settings.toString())
        connection.optJSONObject("quality")?.let { overrides -> overrides.keys().forEach { key -> quality.put(key,overrides.get(key)) } }
        probeWorker.execute {
            val width = quality.optInt("width", 1920)
            val height = when(width) {1280->720;2560->1440;3840->2160;1920->1080;else->quality.optInt("height",1080)}
            val fps = quality.optInt("fps", ConnectionDefaults.FPS)
            val available = CodecProbe.decoders(width, height, fps)
            val codec=quality.optString("codec","auto")
            val hardware=quality.optBoolean("hardwareDecode",true)
            val caps=JSONArray()
            for(i in 0 until available.length()) {
                val decoder=available.getJSONObject(i)
                if((codec=="auto" || decoder.optString("codec")==codec) && (hardware || !decoder.optBoolean("hardware"))) caps.put(decoder)
            }
            main.post {
                if (generation != probeGeneration) return@post
                if (caps.length() == 0) { connecting = false; error = "设备没有可用的视频解码器"; listener?.invoke("error"); return@post }
                val params = JSONObject().put("host", connection.optString("host", "vnt"))
                    .put("port", connection.optInt("port", 29875)).put("password", connection.optString("password"))
                    .put("transport", connection.optString("transport", "quic"))
                    .put("serverConfigId", connection.opt("serverConfigId") ?: JSONObject.NULL)
                    .put("serverNodeId", connection.opt("serverNodeId") ?: JSONObject.NULL)
                    .put("serverNetworkCode", connection.opt("serverNetworkCode") ?: JSONObject.NULL)
                    .put("colorDepth", quality.optInt("colorDepth",24)).put("showWallpaper", quality.optBoolean("showWallpaper",true)).put("dragFullWindows", quality.optBoolean("dragFullWindows",false))
                    .put("fecEnabled", quality.optBoolean("fec", ConnectionDefaults.FEC)).put("resolutionMode", quality.optString("resolutionMode","scale"))
                    .put("targetWidth", width).put("targetHeight", height).put("fps", fps)
                    .put("bitrateKbps", quality.optInt("bitrate", ConnectionDefaults.BITRATE_KBPS)).put("codecs", JSONArray().put("h264"))
                    .put("hardwareCodecs", JSONArray()).put("clipboardEnabled", quality.optBoolean("clipboard", true))
                    .put("audioEnabled", quality.optBoolean("audio", true)).put("audioDeviceId", JSONObject.NULL)
                send(JSONObject().put("op", "connect").put("request", JSONObject().put("params", params).put("decoders", caps)))
            }
        }
    }
    fun disconnect(notify: Boolean = true) {
        viewState.reset()
        ++probeGeneration; probing = false; command("disconnect"); info = null; screens = JSONArray(); connecting = false
        controlOnline = false; switching = false; audio.close(); remoteAudioReason = ""; localAudioReason = ""; desktopReasons.clear(); clipboardText = null
        if (notify) listener?.invoke("closed")
    }
    fun foreground(visible: Boolean) { foreground = visible; command("foreground") { put("visible", visible) }; updateAudio() }
    private fun updateAudio() {
        if (foreground && info?.optBoolean("audioEnabled") == true) {
            val playbackEpoch = epoch
            audio.start(bridge) { reason ->
                main.post {
                    if (epoch == playbackEpoch && foreground && info != null) {
                        localAudioReason = "声音：$reason"; listener?.invoke("audioError")
                    }
                }
            }
        } else { audio.close(); localAudioReason = "" }
    }
    fun chooseScreen(id: Int) {
        if (id == activeStream) return
        val target = (0 until screens.length()).map { screens.getJSONObject(it) }.firstOrNull { it.optInt("streamId") == id }
        if (target?.optBoolean("available") != true) { error = "屏幕当前不可用"; listener?.invoke("error"); return }
        switching = true; command("selectScreen") { put("streamId", id) }; listener?.invoke("switching") }
    fun clipboard(text: String? = null, paste: Boolean = false) = command("clipboard") { if (text != null) put("text", text); put("paste", paste) }
    fun clearError() { error = "" }
    override fun onEvent(event: String, payload: String) {
        if (event == "client://networks") { if (networks.toString() != payload) { networks = JSONArray(payload); listener?.invoke("networks") }; return }
        if (event == "client://closed" && probing) return
        val value = JSONObject(payload)
        if (event == "client://connecting") {
            audio.close(); remoteAudioReason = ""; localAudioReason = ""; desktopReasons.clear()
            probing = false; epoch = value.getLong("epoch"); connecting = true; info = null; controlOnline = false; switching = false
            // Decoder fallback also starts a new epoch, without going through connect().
            screens = JSONArray(); activeStream = 0; revision = 0
            receivedScreensState = false; receivedControlState = false
            status = if (value.optBoolean("softwareFallback")) "正在连接 · H.264 软件解码" else "正在连接…"
        } else if (value.has("epoch") && value.getLong("epoch") != epoch) {
            if (connecting && value.getLong("epoch") > epoch && event in listOf("client://error", "client://closed")) epoch = value.getLong("epoch") else return
        }
        when (event) {
            "client://connected" -> {
                info = value; connecting = false
                // Media callbacks can arrive before this handshake snapshot. Keep their
                // current route: native input rejects stale generation/revision pairs.
                if (!receivedScreensState) {
                    screens = value.getJSONArray("screens")
                    activeStream = (0 until screens.length()).map { screens.getJSONObject(it) }
                        .firstOrNull { it.optBoolean("available") }?.optInt("streamId") ?: 0
                }
                if (!receivedControlState) controlOnline = true
                status = if (!controlOnline) "连接恢复中…" else if (viewOnly) "仅观看" else "已连接"; updateAudio()
            }
            "client://closed" -> { viewState.reset(); info = null; connecting = false; controlOnline = false; switching = false; status = value.optString("reason", "连接已结束"); audio.close(); remoteAudioReason = ""; localAudioReason = ""; desktopReasons.clear() }
            "client://error" -> { switching = false; error = value.optString("reason"); if (connecting && info == null) connecting = false }
            "client://stats" -> stats = value
            "client://renderStats" -> renderStats = value
            "client://decoder" -> decoder = value.optString("name")
            "client://clipboard" -> clipboardText = value.optString("text")
            "client://paste" -> if (!value.optBoolean("success")) error = value.optString("reason","远端粘贴失败")
            "client://fallback" -> status = "正在切换兼容解码器…"
            "client://audio" -> {
                val reason = if (value.optBoolean("enabled") && !value.optBoolean("available")) value.optString("reason") else ""
                if (reason != remoteAudioReason) android.util.Log.i("VntAudio", "Remote audio available=${value.optBoolean("available")}, reason=$reason")
                remoteAudioReason = reason
            }
            "client://media" -> when (value.optString("type")) {
                "screensState" -> { receivedScreensState = true; screens = value.getJSONArray("screens"); activeStream = value.optInt("activeStreamId"); revision = value.optLong("revision"); switching = false }
                "streamInfo" -> {
                    (0 until screens.length()).map { screens.getJSONObject(it) }.firstOrNull { it.optInt("streamId") == value.optInt("streamId") }?.let { screen ->
                        listOf("generation", "width", "height", "codec", "fps").forEach { if (value.has(it)) screen.put(it, value.get(it)) }
                    }
                }
                "transportState" -> if (value.optInt("kind") == 0) {
                    receivedControlState = true
                    controlOnline = value.optBoolean("available"); if (!controlOnline) { status = "连接恢复中…"; command("resetInput") }
                    else status = if (viewOnly) "仅观看" else "已连接"
                }
                "desktopState" -> {
                    val streamId = value.optInt("streamId", 0)
                    if (value.optBoolean("ready", true)) desktopReasons.remove(streamId)
                    else desktopReasons[streamId] = value.optString("reason").ifEmpty { "远端桌面切换中" }
                }
            }
        }
        listener?.invoke(event)
    }
    override fun onCleared() { ++probeGeneration; probeWorker.shutdownNow(); audio.close(); bridge.close(); listener = null }
}
