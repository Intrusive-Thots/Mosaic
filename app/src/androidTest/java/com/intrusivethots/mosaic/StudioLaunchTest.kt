package com.intrusivethots.mosaic

import android.os.SystemClock
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opens the Studio on a device. The CI emulator is the machine that actually runs this.
 * Labels are read from the in-process Compose semantics tree. The accessibility bridge
 * has omitted both "Mosaic" and "Studio" for a full poll window after the activity was
 * already resumed, including when the poll did not call waitForIdleSync.
 */
@RunWith(AndroidJUnit4::class)
class StudioLaunchTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun studioReachesResumedAndShowsItsTitle() {
        assertTrue(composeRule.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val screenshot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        assertTrue(screenshot.width > 0 && screenshot.height > 0)
        assertTrue(
            "The Studio title was not on screen. Missing Mosaic.",
            waitUntilLaidOut("Mosaic")
        )
        assertTrue(
            "The Studio title was not on screen. Missing Studio.",
            waitUntilLaidOut("Studio")
        )
    }

    private fun waitUntilLaidOut(label: String): Boolean {
        val deadline = SystemClock.uptimeMillis() + LABEL_WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (labelIsLaidOut(label)) return true
            SystemClock.sleep(LABEL_POLL_MS)
        }
        return labelIsLaidOut(label)
    }

    private fun labelIsLaidOut(label: String): Boolean {
        val matcher = hasText(label, substring = true) or hasContentDescription(label, substring = true)
        val nodes = try {
            composeRule.onAllNodes(matcher, useUnmergedTree = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
        } catch (notReady: IllegalStateException) {
            return false
        }
        return nodes.any { node ->
            val bounds = node.boundsInRoot
            bounds.width > 0f && bounds.height > 0f
        }
    }
}

private const val LABEL_WAIT_MS = 20_000L
private const val LABEL_POLL_MS = 100L
