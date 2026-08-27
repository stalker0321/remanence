package dev.hryshyn.remanence.core.data.db

import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DaoSurfaceTest {

    @Test
    fun accountOwnedWritesRequireAnExplicitOwnerArgument() {
        assertPublicWriteHasOwner(RecognitionFingerprintDao::class.java, "insertAll")
        assertPublicWriteHasOwner(OutboxCapsuleDao::class.java, "insertOrAbort")
        assertPublicWriteHasOwner(OutboxBlobDao::class.java, "upsertAll")
        assertPublicMethod(BlobCacheDao::class.java, "upsertForOwner")
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
            "transitionStateForOwner",
            "transitionStateWithErrorForOwner",
            "clear",
        )
        assertNotPublic(
            OutboxBlobDao::class.java,
            "insertStrict",
            "findOwnerOfBlobId",
            "findOwnerOfCapsuleSlot",
            "transitionUploadStateForOwner",
            "clear",
        )
        assertNotPublic(BlobCacheDao::class.java, "transitionStateForOwner", "clear")
        assertNotPublic(SyncCursorDao::class.java, "updateIfNewer", "insertIgnoring", "deleteByUser", "clear")

        assertNoPublicStateOrAllowedFrom(BlobCacheDao::class.java, BlobCacheState::class.java)
        assertNoPublicStateOrAllowedFrom(OutboxBlobDao::class.java, OutboxBlobUploadState::class.java)
        assertNoPublicStateOrAllowedFrom(OutboxCapsuleDao::class.java, OutboxCapsuleState::class.java)

        assertPublicMethod(RecognitionFingerprintDao::class.java, "clearForOwner")
        assertPublicMethod(OutboxCapsuleDao::class.java, "clearForOwner")
        assertPublicMethod(OutboxBlobDao::class.java, "clearForOwner")
        assertPublicMethod(BlobCacheDao::class.java, "clearForOwner")
        assertPublicMethod(SyncCursorDao::class.java, "clearForOwner")

        assertPublicMethod(BlobCacheDao::class.java, "markCachedForOwner")
        assertPublicMethod(BlobCacheDao::class.java, "markCorruptForOwner")
        assertPublicMethod(BlobCacheDao::class.java, "retryDownloadForOwner")
        assertPublicMethod(OutboxBlobDao::class.java, "markStoredForOwner")
        assertPublicMethod(OutboxCapsuleDao::class.java, "markEncryptedForOwner")
        assertPublicMethod(OutboxCapsuleDao::class.java, "beginUploadForOwner")
        assertPublicMethod(OutboxCapsuleDao::class.java, "beginFinalizeForOwner")
        assertPublicMethod(OutboxCapsuleDao::class.java, "markPublishedForOwner")
        assertPublicMethod(OutboxCapsuleDao::class.java, "markRetryableFailureForOwner")
        assertPublicMethod(OutboxCapsuleDao::class.java, "markTerminalFailureForOwner")
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

    private fun assertNoPublicStateOrAllowedFrom(daoClass: Class<*>, stateClass: Class<*>) {
        assertTrue(
            "$daoClass must not expose caller-selected state or allowedFrom",
            daoClass.methods.none { method ->
                method.parameterTypes.any { it == stateClass } ||
                    method.genericParameterTypes.any { parameter ->
                        parameter is ParameterizedType &&
                            parameter.rawType == List::class.java &&
                            parameter.actualTypeArguments.any { it == stateClass }
                    }
            },
        )
    }
}
