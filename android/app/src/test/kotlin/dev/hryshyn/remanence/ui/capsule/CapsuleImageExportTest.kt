package dev.hryshyn.remanence.ui.capsule

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Match the existing JVM UI suite (Java 17); API36 runtime is a device check.
@Config(sdk = [35], application = Application::class)
class CapsuleImageExportTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun attachProviderToThisSandbox() {
        // Robolectric uses a fresh cache root for each method, whereas AndroidX
        // caches provider paths by authority. Reattach using the actual manifest
        // to invalidate the previous sandbox's root, not loosen the provider.
        val info = checkNotNull(context.packageManager.resolveContentProvider(
            context.packageName + ".capsuleexport", PackageManager.GET_META_DATA,
        ))
        assertFalse(info.exported)
        assertTrue(info.grantUriPermissions)
        FileProvider().attachInfo(context, info)
    }

    @Test fun sharingUsesOnlyReadOnlyContentUriAndNoPrivatePayload() {
        val file = CapsuleExportFiles.write(context, byteArrayOf(1, 2, 3))
        val intent = CapsuleExportFiles.shareIntent(context, file)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("image/png", intent.type)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        assertEquals("content", intent.clipData!!.getItemAt(0).uri.scheme)
        assertEquals(context.packageName + ".capsuleexport", intent.clipData!!.getItemAt(0).uri.authority)
        assertNull(intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(setOf(Intent.EXTRA_STREAM), intent.extras!!.keySet())
    }

    @Test fun exportedFilesAreOpaqueBoundedAndOldExportsAreRemovedOnNextExport() {
        val first = CapsuleExportFiles.write(context, byteArrayOf(1))
        first.setLastModified(0)
        repeat(5) { CapsuleExportFiles.write(context, byteArrayOf(2)) }
        assertFalse(first.exists())
        val files = File(context.cacheDir, "capsule-exports").listFiles()!!
        assertTrue(files.size <= 3)
        assertTrue(files.all { it.name.matches(Regex("remanence-[a-f0-9-]+\\.png")) })
    }

    @Test fun providerCannotShareKeysOrOtherCacheDirectories() {
        val outside = File(context.cacheDir, "identity-private.png").apply { writeBytes(byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { CapsuleExportFiles.shareIntent(context, outside) }
    }

    @Test fun oversizedAndEmptyExportsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { CapsuleExportFiles.write(context, byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { CapsuleExportFiles.write(context, ByteArray(CapsuleExportFiles.MAX_BYTES + 1)) }
    }

    @Test fun expirySweepDoesNotNeedAnotherShareAndKeepsActiveReaderLease() {
        val file = CapsuleExportFiles.write(context, byteArrayOf(1))
        val written = file.lastModified()
        CapsuleExportFiles.sweep(context, written + CapsuleExportFiles.TTL_MILLIS - 1)
        assertTrue(file.exists()) // asynchronous receiver can still read
        CapsuleExportFiles.sweep(context, written + CapsuleExportFiles.TTL_MILLIS)
        assertFalse(file.exists())
    }

    @Test fun accountBoundaryPurgesFreshOwnedExportsNotOtherCacheFiles() {
        val file = CapsuleExportFiles.write(context, byteArrayOf(1))
        val unrelated = File(context.cacheDir, "unrelated-test-cache").apply { writeBytes(byteArrayOf(2)) }
        CapsuleExportFiles.sweep(context, clearAll = true)
        assertFalse(file.exists())
        assertTrue(unrelated.exists())
        CapsuleExportFiles.sweep(context, clearAll = true) // idempotent
    }
}
