package com.rustvnt.remote

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorInstrumentedTest {
    private fun find(view:View, text:String):View? {
        if(view is TextView && view.text.toString()==text && view.isClickable)return view
        if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i),text)?.let{return it}
        return null
    }
    @Test fun deviceEditorKeepsDraftAndSavesAdvancedOptions() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.navDevices).performClick()
                find(activity.window.decorView,"添加设备")!!.performClick()
                activity.findViewById<EditText>(R.id.deviceName).setText("测试电脑")
                activity.findViewById<EditText>(R.id.deviceHost).setText("192.0.2.12")
                find(activity.window.decorView,"高级选项")!!.performClick()
                find(activity.window.decorView,"30 fps")!!.performClick()
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals("测试电脑",activity.findViewById<EditText>(R.id.deviceName).text.toString())
                assertEquals("192.0.2.12",activity.findViewById<EditText>(R.id.deviceHost).text.toString())
                activity.findViewById<View>(R.id.deviceSave).performClick()
                val model=ViewModelProvider(activity)[RemoteModel::class.java]
                val index=(0 until model.saved.length()).first { model.saved.getJSONObject(it).optString("name")=="测试电脑" }
                assertEquals(30,model.saved.getJSONObject(index).getJSONObject("quality").getInt("fps"))
                model.deleteConnection(index)
            }
        }
    }
    @Test fun serverEditorIsAFullPageAndBackReturnsToList() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.navServers).performClick()
                find(activity.window.decorView,"添加配置")!!.performClick()
                activity.findViewById<EditText>(R.id.serverName).setText("测试网络")
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals("测试网络",activity.findViewById<EditText>(R.id.serverName).text.toString())
                activity.findViewById<View>(R.id.openNavigation).performClick()
                assertEquals("服务端",activity.findViewById<TextView>(R.id.toolbarPage).text.toString())
            }
        }
    }
    @Test fun newServerUsesDefaultsAndSavingDoesNotStartIt() {
        var generated=""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.navServers).performClick()
                find(activity.window.decorView,"添加配置")!!.performClick()
                assertEquals("vnt2.rustvnt.com:29872",activity.findViewById<EditText>(R.id.serverAddress).text.toString())
                generated=activity.findViewById<EditText>(R.id.serverConnectionId).text.toString()
                assertTrue(generated.matches(Regex("[1-9][0-9]{8}")))
                activity.findViewById<EditText>(R.id.serverName).setText("测试默认网络")
                activity.findViewById<EditText>(R.id.serverRoom).setText("123456")
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(generated,activity.findViewById<EditText>(R.id.serverConnectionId).text.toString())
                activity.findViewById<View>(R.id.serverSave).performClick()
                val model=ViewModelProvider(activity)[RemoteModel::class.java]
                val index=(0 until model.profiles.length()).first { model.profiles.getJSONObject(it).optString("name")=="测试默认网络" }
                val profile=model.profiles.getJSONObject(index)
                assertFalse(profile.getBoolean("enabled"))
                assertEquals(generated,profile.getString("connectionId"))
                assertEquals("vnt2.rustvnt.com:29872",profile.getJSONArray("servers").getString(0))
                model.deleteProfile(index)
            }
        }
    }

}
