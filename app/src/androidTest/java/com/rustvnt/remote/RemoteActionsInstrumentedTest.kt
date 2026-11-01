package com.rustvnt.remote

import android.view.View
import android.widget.TextView
import android.widget.LinearLayout
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.hamcrest.Matchers.equalTo
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteActionsInstrumentedTest {
    @Before fun allowLocalNetwork() {
        if (android.os.Build.VERSION.SDK_INT >= 37) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                "com.rustvnt.remote", "android.permission.ACCESS_LOCAL_NETWORK"
            )
        }
    }

    private fun connect(model: RemoteModel, system: String, edition: String, viewOnly: Boolean) {
        model.onEvent("client://connecting", """{"epoch":1}""")
        model.onEvent("client://connected", JSONObject().put("epoch", 1)
            .put("remoteSystem", system).put("hostEdition", edition)
            .put("viewOnly", viewOnly).put("clipboardEnabled", true)
            .put("screens", org.json.JSONArray()).toString())
    }

    @Test fun viewOnlyHidesInputActionsAndClosesKeyboardInBothLayouts() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[RemoteModel::class.java]
                connect(model, "windows", "installed", false)
                activity.findViewById<View>(R.id.keyboardToggle).performClick()
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.remoteKeyboard).visibility)
                connect(model, "windows", "installed", true)
            }
            scenario.onActivity { activity ->
                fun assertHidden() {
                    val actions = activity.findViewById<LinearLayout>(R.id.remoteActions)
                    for (i in 0 until actions.childCount) {
                        val action = actions.getChildAt(i)
                        if (action.tag == "input" || action.tag == "clipboard") assertEquals(View.GONE, action.visibility)
                    }
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.fullscreenKeyboard).visibility)
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.remoteKeyboard).visibility)
                    assertFalse(ViewModelProvider(activity)[WorkspaceState::class.java].keyboardVisible)
                }
                assertHidden()
                activity.findViewById<View>(R.id.fullscreenToggle).performClick()
                activity.findViewById<View>(R.id.fullscreenMenu).performClick()
                assertHidden()
                activity.findViewById<View>(R.id.fullscreenExit).performClick()
                connect(ViewModelProvider(activity)[RemoteModel::class.java], "windows", "installed", false)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pointerMode).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.keyboardToggle).visibility)
            }
        }
    }

    @Test fun sasAppearsOnlyInsideKeysForInstalledWindows() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for ((system, edition) in listOf("windows" to "installed", "windows" to "portable", "macos" to "installed", "linux" to "installed")) {
                scenario.onActivity { activity ->
                    connect(ViewModelProvider(activity)[RemoteModel::class.java], system, edition, false)
                }
                scenario.onActivity { activity ->
                    val actions = activity.findViewById<LinearLayout>(R.id.remoteActions)
                    val buttons = (0 until actions.childCount).mapNotNull { actions.getChildAt(it) as? TextView }
                    assertFalse(buttons.any { it.text == "Ctrl+Alt+Del" })
                }
                onView(withText("按键")).check(matches(isDisplayed()))
                scenario.onActivity { activity ->
                    assertTrue(ViewModelProvider(activity)[RemoteModel::class.java].canInput)
                    val actions = activity.findViewById<LinearLayout>(R.id.remoteActions)
                    (0 until actions.childCount).mapNotNull { actions.getChildAt(it) as? TextView }
                        .first { it.text == "按键" }.performClick()
                }
                if (system == "windows" && edition == "installed") onData(equalTo("Ctrl+Alt+Del")).inRoot(isDialog()).check(matches(isDisplayed()))
                else onView(withText("Ctrl+Alt+Del")).inRoot(isDialog()).check(doesNotExist())
                pressBack()
            }
        }
    }
}
