package com.intrusivethots.mosaic

import android.app.Instrumentation
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opens the Studio on a device. The CI emulator is the machine that actually runs this.
 */
@RunWith(AndroidJUnit4::class)
class StudioLaunchTest {
    @Test
    fun studioReachesResumedAndShowsItsTitle() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.waitForIdleSync()
            val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            assertTrue(screenshot.width > 0 && screenshot.height > 0)
            val missing = listOf("Mosaic", "Studio").filterNot { waitForLabel(instrumentation, it) }
            assertTrue("The Studio title was not on screen. Missing $missing.", missing.isEmpty())
        }
    }
}

private fun waitForLabel(instrumentation: Instrumentation, label: String): Boolean {
    val deadline = SystemClock.uptimeMillis() + LABEL_WAIT_MS
    while (SystemClock.uptimeMillis() < deadline) {
        instrumentation.waitForIdleSync()
        val root = instrumentation.uiAutomation.rootInActiveWindow
        if (root != null && nodeContains(root, label)) return true
        SystemClock.sleep(LABEL_POLL_MS)
    }
    return false
}

private fun nodeContains(node: AccessibilityNodeInfo, label: String): Boolean {
    if (node.text?.contains(label) == true) return true
    if (node.contentDescription?.contains(label) == true) return true
    for (index in 0 until node.childCount) {
        val child = node.getChild(index) ?: continue
        if (nodeContains(child, label)) return true
    }
    return false
}

private const val LABEL_WAIT_MS = 8_000L
private const val LABEL_POLL_MS = 250L
