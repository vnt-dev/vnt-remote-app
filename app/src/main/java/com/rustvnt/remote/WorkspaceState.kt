package com.rustvnt.remote

import androidx.lifecycle.ViewModel
import org.json.JSONObject

/** UI drafts survive navigation, rotation and appearance changes without ending a session. */
class WorkspaceState : ViewModel() {
    var page = 0
    var address = ""
    var password = ""
    var transport = "quic"
    var selectedDevice: JSONObject? = null
    var advanced = false
    var connectionError = ""
    var editor = JSONObject()
    var editorAdvanced = false
    var remoteFullscreen = false
    var fullscreenActions = false
    var keyboardVisible = false
}
