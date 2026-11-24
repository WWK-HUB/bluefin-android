package com.wwkhub.bluefin

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsNavigationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences(BluefinStore.PREFS, Context.MODE_PRIVATE)

    @Test
    fun freshGuideRetainsItsStepAcrossSetupAndRecreation() = withGuideState(completed = false) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                assertTitle(it, R.string.guide_title)
                click(it, R.id.guide_next)
                click(it, R.id.guide_next)
                click(it, R.id.guide_open_feature)
                assertTitle(it, R.string.settings_model)
                assertNotNull(it.findViewById<View>(R.id.model_key))
            }
            scenario.recreate()
            scenario.onActivity {
                assertTitle(it, R.string.settings_model)
                click(it, R.id.settings_back)
                assertTitle(it, R.string.guide_title)
                val action = it.findViewById<TextView>(R.id.guide_open_feature)
                assertEquals(it.getString(R.string.guide_chat_action), action.text.toString())
                click(it, R.id.guide_skip)
                assertNull(it.findViewById<View>(R.id.guide_skip))
                assertTrue(prefs.getBoolean(BluefinStore.KEY_TIPS_DONE, false))
                click(it, R.id.open_settings)
                openGuideFromSettings(it)
                click(it, R.id.guide_skip)
                assertTitle(it, R.string.settings_title)
            }
        }
    }

    @Test
    fun completedGuideDoesNotBlockSettingsAndModelRouteReturnsToIndex() = withGuideState(completed = true) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                assertNull(it.findViewById<View>(R.id.guide_next))
                click(it, R.id.open_settings)
                assertTitle(it, R.string.settings_title)
                assertNull(it.findViewById<View>(R.id.model_key))
                it.openModelSettings()
                assertTitle(it, R.string.settings_model)
                click(it, R.id.settings_back)
                assertTitle(it, R.string.settings_title)
                click(it, R.id.settings_back)
                assertNull(it.findViewById<View>(R.id.settings_scroll))
            }
        }
    }

    private fun openGuideFromSettings(activity: MainActivity) {
        val matches = arrayListOf<View>()
        activity.findViewById<View>(R.id.settings_scroll).findViewsWithText(
            matches, activity.getString(R.string.guide_title), View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION,
        )
        assertTrue(matches.single().performClick())
        assertTitle(activity, R.string.guide_title)
    }

    private fun assertTitle(activity: MainActivity, expected: Int) {
        val title = activity.findViewById<TextView>(R.id.settings_title)
        // The underlying guide view stays attached while its settings page is open.
        val visibleTitles = arrayListOf<View>()
        activity.window.decorView.findViewsWithText(
            visibleTitles, activity.getString(expected), View.FIND_VIEWS_WITH_TEXT,
        )
        assertNotNull(title)
        assertTrue(visibleTitles.any { it.isShown && (it as? TextView)?.text == activity.getString(expected) })
    }

    private fun click(activity: MainActivity, id: Int) {
        val matches = arrayListOf<View>()
        fun collect(view: View) {
            if (!view.isShown) return
            if (view.id == id) matches += view
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) collect(view.getChildAt(index))
            }
        }
        collect(activity.window.decorView)
        assertTrue(matches.single().performClick())
    }

    private fun withGuideState(completed: Boolean, run: () -> Unit) {
        val previous = prefs.all
        val state = BluefinStore.KEY_TIPS_DONE
        val step = "guide_step"
        prefs.edit().putBoolean(state, completed).remove(step).commit()
        try {
            run()
        } finally {
            val restore = prefs.edit()
            (previous[state] as? Boolean)?.let { restore.putBoolean(state, it) } ?: restore.remove(state)
            (previous[step] as? Int)?.let { restore.putInt(step, it) } ?: restore.remove(step)
            restore.commit()
        }
    }
}
