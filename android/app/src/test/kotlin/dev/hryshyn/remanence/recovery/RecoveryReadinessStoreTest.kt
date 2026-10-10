package dev.hryshyn.remanence.recovery

import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.hryshyn.remanence.ui.navigation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecoveryReadinessStoreTest {
    @Test fun readinessSurvivesRecreationButNeverCrossesOwnerOrBundle() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("recovery_readiness_v1", 0).edit().clear().commit()
        val first = RecoveryReadinessStore(context)
        assertFalse(first.isReady("account-a", "bundle-a"))
        first.markVerified("account-a", "bundle-a")
        val restarted = RecoveryReadinessStore(context)
        assertTrue(restarted.isReady("account-a", "bundle-a"))
        assertFalse(restarted.isReady("account-b", "bundle-a"))
        assertFalse(restarted.isReady("account-a", "bundle-b"))
    }

    @Test fun unfinishedRecoveryCannotReachAnyProductRoute() {
        val state = AuthUiState.RecoverySetupRequired("a", "handle", "bundle")
        RouteGuard.allDestinations().forEach {
            assertEquals(AppDestination.Authentication, RouteGuard.resolve(state, it))
        }
    }
}
