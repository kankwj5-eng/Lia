package org.lia.accessibility

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.scrollTo
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
            onView(withId(R.id.talkToLiaButton)).check(matches(isDisplayed()))
            onView(withId(R.id.cancelAgentButton)).check(matches(isDisplayed()))

            onView(withId(R.id.plannerStatusText))
                .perform(scrollTo())
                .check(matches(isDisplayed()))
            onView(withId(R.id.importPlannerButton))
                .perform(scrollTo())
                .check(matches(isDisplayed()))

            onView(withId(R.id.assistantStatusText))
                .perform(scrollTo())
                .check(matches(isDisplayed()))
            onView(withId(R.id.accessibilitySettingsButton))
                .perform(scrollTo())
                .check(matches(isDisplayed()))
            onView(withId(R.id.assistantRoleButton))
                .perform(scrollTo())
                .check(matches(isDisplayed()))
            onView(withId(R.id.bubbleSwitch))
                .perform(scrollTo())
                .check(matches(isDisplayed()))

            // La identidad de voz es opcional y sus controles permanecen
            // ocultos hasta que la persona activa la protección por voz.
            onView(withId(R.id.alwaysListeningSwitch))
                .perform(scrollTo())
                .check(matches(isDisplayed()))
            onView(withId(R.id.attentionStatusText))
                .perform(scrollTo())
                .check(matches(isDisplayed()))
            onView(withId(R.id.voiceProtectionSwitch))
                .perform(scrollTo())
                .check(matches(isDisplayed()))

            onView(withId(R.id.commandStatusText))
                .perform(scrollTo())
                .check(matches(isDisplayed()))
            onView(withId(R.id.prepareCommandSpeechButton))
                .perform(scrollTo())
                .check(matches(isDisplayed()))

            onView(withId(R.id.worldVisionButton))
                .perform(scrollTo())
                .check(matches(isDisplayed()))
            onView(withId(R.id.notificationSettingsButton))
                .perform(scrollTo())
                .check(matches(isDisplayed()))
        }
    }
}
