package org.lia.accessibility

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.accessibility.AccessibilityChecks
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class MainActivitySmokeTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun enableAccessibilityChecks() {
            AccessibilityChecks.enable()
                .setRunChecksFromRootView(true)
        }
    }

    @Test
    fun mainActivityShowsCriticalAccessibilityControls() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("Lía")).check(matches(isDisplayed()))
            onView(withId(R.id.statusText)).check(matches(isDisplayed()))
            onView(withId(R.id.recordSampleButton)).check(matches(isDisplayed()))
            onView(withId(R.id.saveVoiceButton)).check(matches(isDisplayed()))
            onView(withId(R.id.testVoiceButton)).check(matches(isDisplayed()))
            onView(withId(R.id.worldVisionButton)).check(matches(isDisplayed()))
            onView(withId(R.id.accessibilitySettingsButton)).check(matches(isDisplayed()))
        }
    }
}
