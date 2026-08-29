package dev.hryshyn.remanence.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import dev.hryshyn.remanence.core.model.UserId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IncomingCapsuleSyncSchedulingTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.ERROR)
                .build(),
        )
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun duplicateEnqueueUsesOneCanonicalKeepChainWithOwnerOnlyInput() {
        val owner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000c601")
        val identity = AccountWorkIdentity.incomingSync(owner)

        IncomingCapsuleSyncWorker.enqueue(workManager, owner)
        val firstId = workManager.getWorkInfosForUniqueWork(identity.uniqueName).get().single().id
        IncomingCapsuleSyncWorker.enqueue(workManager, owner)

        val infos = workManager.getWorkInfosForUniqueWork(identity.uniqueName).get()

        assertEquals(1, infos.size)
        val info = infos.single()
        assertEquals(firstId, info.id)
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertTrue(info.tags.containsAll(identity.tags))
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
    }
}
