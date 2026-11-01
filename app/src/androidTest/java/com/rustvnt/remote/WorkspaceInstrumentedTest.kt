package com.rustvnt.remote

import android.graphics.Color
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceInstrumentedTest {
    @Test fun fourSidebarPagesKeepConnectionDraftAndBackClosesDrawerFirst() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val expected=mapOf(R.id.navRemote to "远程控制",R.id.navDevices to "设备列表",R.id.navSettings to "设置",R.id.navServers to "服务端")
                expected.forEach { (id,title)->assertEquals(title,activity.findViewById<TextView>(id).text.toString()) }
                activity.findViewById<EditText>(R.id.quickAddress).setText("[2001:db8::1]:40000")
                activity.findViewById<EditText>(R.id.quickPassword).apply {
                    setText("draft-password")
                    assertTrue("password must be masked by default",transformationMethod is android.text.method.PasswordTransformationMethod)
                }
                for(id in listOf(R.id.navDevices,R.id.navSettings,R.id.navServers))activity.findViewById<View>(id).performClick()
                assertEquals("服务端",activity.findViewById<TextView>(R.id.toolbarPage).text.toString())
                activity.onBackPressedDispatcher.onBackPressed()
                assertEquals("远程控制",activity.findViewById<TextView>(R.id.toolbarPage).text.toString())
                assertEquals("[2001:db8::1]:40000",activity.findViewById<EditText>(R.id.quickAddress).text.toString())
                assertEquals("draft-password",activity.findViewById<EditText>(R.id.quickPassword).text.toString())
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals("[2001:db8::1]:40000",activity.findViewById<EditText>(R.id.quickAddress).text.toString())
                assertNull(ViewModelProvider(activity)[RemoteModel::class.java].info)
                val root=activity.findViewById<DrawerLayout>(R.id.root)
                if(activity.resources.configuration.screenWidthDp<840){
                    root.openDrawer(GravityCompat.START,false)
                    activity.onBackPressedDispatcher.onBackPressed()
                }else assertEquals(View.VISIBLE,activity.findViewById<View>(R.id.sidebarHost).visibility)
            }
            repeat(30) {
                var closed=false
                scenario.onActivity { activity -> closed=!activity.findViewById<DrawerLayout>(R.id.root).isDrawerOpen(GravityCompat.START) }
                if(closed)return@use
                Thread.sleep(100)
            }
            fail("drawer did not close after Back")
        }
    }
    @Test fun desktopLightAndDarkTokensSwitchWithoutLosingTheDraft() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val previous=Appearance.mode(context)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.findViewById<EditText>(R.id.quickAddress).setText("pc.local")
                    Appearance.set(activity,"dark")
                }
                waitForColor(scenario,"#0F1218")
                scenario.onActivity {activity->
                    assertEquals("pc.local",activity.findViewById<EditText>(R.id.quickAddress).text.toString())
                    assertEquals(Color.parseColor("#5D7CFF"),ContextCompat.getColor(activity,R.color.vnt_brand))
                    Appearance.set(activity,"light")
                }
                waitForColor(scenario,"#F4F6FA")
                scenario.onActivity {activity->assertEquals(Color.parseColor("#2F61F0"),ContextCompat.getColor(activity,R.color.vnt_brand))}
            }
        } finally {InstrumentationRegistry.getInstrumentation().runOnMainSync {Appearance.set(context,previous)}}
    }
    private fun waitForColor(scenario:ActivityScenario<MainActivity>,color:String) {
        repeat(40){
            var matches=false
            scenario.onActivity {matches=ContextCompat.getColor(it,R.color.vnt_bg)==Color.parseColor(color)}
            if(matches)return
            Thread.sleep(100)
        }
        fail("appearance did not change to $color")
    }
}
