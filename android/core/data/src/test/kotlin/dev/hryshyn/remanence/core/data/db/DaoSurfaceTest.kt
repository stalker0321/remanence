package dev.hryshyn.remanence.core.data.db

import java.lang.reflect.Modifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DaoSurfaceTest {

    @Test
    fun accountOwnedWritesRequireAnExplicitOwnerArgument() {
        assertPublicWriteHasOwner(RecognitionFingerprintDao::class.java, "insertAll")
        assertPublicWriteHasOwner(OutboxCapsuleDao::class.java, "insertOrAbort")
        assertPublicWriteHasOwner(OutboxBlobDao::class.java, "upsertAll")
        assertPublicWriteHasOwner(SyncCursorDao::class.java, "advance")
    }

    @Test
    fun rawPrimitivesAndGlobalCleanupAreNotPublic() {
        assertNotPublic(
            RecognitionFingerprintDao::class.java,
            "insertStrict",
            "findOwnerOf",
            "clearPreferredForOwner",
            "markPreferredForOwner",
            "clear",
        )
        assertNotPublic(
            OutboxCapsuleDao::class.java,
            "insertStrict",
            "findOwnersOfImmutableIds",
            "clear",
        )
        assertNotPublic(
            OutboxBlobDao::class.java,
            "insertStrict",
            "findOwnerOfBlobId",
            "findOwnerOfCapsuleSlot",
            "clear",
        )
        assertNotPublic(SyncCursorDao::class.java, "updateIfNewer", "insertIgnoring", "deleteByUser", "clear")

        assertPublicMethod(RecognitionFingerprintDao::class.java, "clearForOwner")
        assertPublicMethod(OutboxCapsuleDao::class.java, "clearForOwner")
        assertPublicMethod(OutboxBlobDao::class.java, "clearForOwner")
        assertPublicMethod(SyncCursorDao::class.java, "clearForOwner")
    }

    private fun assertPublicWriteHasOwner(daoClass: Class<*>, methodName: String) {
        assertTrue(
            "$daoClass.$methodName must be public and begin with authoritative ownerUserId",
            daoClass.declaredMethods.any { method ->
                method.name == methodName &&
                    Modifier.isPublic(method.modifiers) &&
                    method.parameterTypes.firstOrNull() == String::class.java
            },
        )
    }

    private fun assertNotPublic(daoClass: Class<*>, vararg methodNames: String) {
        methodNames.forEach { methodName ->
            assertFalse(
                "$daoClass.$methodName must not be public",
                daoClass.declaredMethods.any { method ->
                    method.name == methodName && Modifier.isPublic(method.modifiers)
                },
            )
        }
    }

    private fun assertPublicMethod(daoClass: Class<*>, methodName: String) {
        assertTrue(
            "$daoClass.$methodName must remain public",
            daoClass.declaredMethods.any { method ->
                method.name == methodName && Modifier.isPublic(method.modifiers)
            },
        )
    }
}
