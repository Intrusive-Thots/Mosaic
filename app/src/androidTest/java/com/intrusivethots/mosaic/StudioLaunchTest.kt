package com.intrusivethots.mosaic

import android.app.UiAutomation
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
 * The title poll does not call [android.app.Instrumentation.waitForIdleSync]: on a loaded
 * emulator that call can block longer than the deadline, so the tree is read once and the
 * labels are reported missing even though they appear a moment later.
 */
@RunWith(AndroidJUnit4::class)
class StudioLaunchTest {
    @Test
    fun studioReachesResumedAndShowsItsTitle() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            }
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val screenshot = checkNotNull(automation.takeScreenshot())
            assertTrue(screenshot.width > 0 && screenshot.height > 0)
            val missing = waitForLabels(automation, listOf("Mosaic", "Studio"))
            assertTrue("The Studio title was not on screen. Missing $missing.", missing.isEmpty())
        }
    }
}

private fun waitForLabels(automation: UiAutomation, labels: List<String>): List<String> {
    val pending = labels.toMutableList()
    val deadline = SystemClock.uptimeMillis() + LABEL_WAIT_MS
    while (pending.isNotEmpty() && SystemClock.uptimeMillis() < deadline) {
        val roots = windowRoots(automation)
        pending.removeAll { label -> roots.any { nodeContains(it, label) } }
        if (pending.isEmpty()) return emptyList()
        SystemClock.sleep(LABEL_POLL_MS)
    }
    return pending
}

private fun windowRoots(automation: UiAutomation): List<AccessibilityNodeInfo> {
    val windows = automation.windows
    if (!windows.isNullOrEmpty()) {
        val roots = windows.mapNotNull { it.root }
        if (roots.isNotEmpty()) return roots
    }
    return listOfNotNull(automation.rootInActiveWindow)
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

private const val LABEL_WAIT_MS = 20_000L
private const val LABEL_POLL_MS = 250L
