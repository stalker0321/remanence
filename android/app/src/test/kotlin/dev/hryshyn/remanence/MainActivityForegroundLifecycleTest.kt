package dev.hryshyn.remanence

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

/**
 * A10c's narrow lifecycle proof. The full activity graph is intentionally not
 * constructed here because it would initialize production identity/Keystore
 * dependencies; the Compose lifecycle bridge is the only new lifecycle seam.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MainActivityForegroundLifecycleTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun effectInvokesRootForegroundHookOnceForEachResumeTransition() {
        val owner = TestLifecycleOwner()
        var calls = 0

        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                ForegroundResumeEffect { calls += 1 }
            }
        }

        composeRule.runOnIdle {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        composeRule.waitForIdle()
        assertEquals(1, calls)

        composeRule.runOnIdle {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        composeRule.waitForIdle()
        assertEquals(2, calls)
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)

        override val lifecycle: Lifecycle
            get() = registry
    }
}
