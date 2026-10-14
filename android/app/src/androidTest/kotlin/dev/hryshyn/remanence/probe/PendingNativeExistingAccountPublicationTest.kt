package dev.hryshyn.remanence.probe

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hryshyn.remanence.RemanenceApplication
import dev.hryshyn.remanence.BuildConfig
import dev.hryshyn.remanence.core.data.network.GiftLookupResult
import dev.hryshyn.remanence.core.data.network.PendingGiftLookupRepository
import dev.hryshyn.remanence.core.data.network.SessionRequestLease
import dev.hryshyn.remanence.core.model.GeneratorEditorialRows
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.pending.PendingBoundaryPublish
import dev.hryshyn.remanence.pending.PendingCodeNormalizer
import dev.hryshyn.remanence.pending.PendingSendRouteFlow
import dev.hryshyn.remanence.pending.PendingSenderIdentity
import dev.hryshyn.remanence.pending.PendingServicePinConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T186 one-shot existing-account native publication prerequisite
 * (device only — root runs it, no JVM claim). Prepares a genuine new
 * capsule with a matchable FRONT using the SAME persisted R33 account,
 * wrapped identity and live session — no registration, login, reset,
 * OTP, proof, or claim anywhere:
 *
 * isolated-build guard → expected owner/bundle instrumentation args →
 * persisted account/bundle/handle + Available identity + ORIGINAL
 * live owner-bound lease/bearer + epoch pin (all missing → explicit
 * prerequisite failure, never seeding or adopting) → durable one-shot
 * attempt marker (test-owned app-private filesDir, BEFORE any R1
 * network effect; a pre-existing marker refuses replay) → R1 freeze
 * through the real wrapper (frozen PENDING, never v1) → full
 * [PendingBoundaryPublish] payload (3 real device JPEGs with recorded
 * dims, corrected canonical FRONT, validated supported expression
 * over exact refs, out-of-band pin) → real R3/R4/R5 → 5-char locator
 * → independent anonymous R6 lookup → durable completion handoff.
 *
 * Instrumentation args (PUBLIC opaque UUIDs only — never
 * passwords/bearers/OTP): `t186_expected_owner` (owner user id) and
 * `t186_expected_bundle` (active key bundle id). The handoff file
 * `pendingtest-publication/publication.json` under the app filesDir
 * carries PUBLIC opaque fields only
 * (capsuleId/deliveryId/locatorCode/stage) — never contacts, tokens,
 * keys, passwords, or OTP. Owned JPEG/FRONT bytes die in `finally` on
 * all outcomes; cancellation propagates; failures name only coarse
 * phases plus status/reason enums.
 */
@RunWith(AndroidJUnit4::class)
class PendingNativeExistingAccountPublicationTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as RemanenceApplication).container

    /**
     * Static instrumentation arguments (the `Instrumentation` base type
     * itself carries no `arguments` accessor — the registry static
     * does). PUBLIC opaque UUIDs only.
     */
    private fun stringArgument(name: String): String? = try {
        InstrumentationRegistry.getArguments().getString(name)
    } catch (_: Exception) {
        null
    }

    private val random = SecureRandom()

    private fun randomHex(chars: Int): String {
        val alphabet = "0123456789abcdef"
        return (0 until chars).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** One real device JPEG with recorded dimensions (solid color field). */
    private fun deviceJpeg(widthPx: Int, heightPx: Int, color: Int): Triple<ByteArray, Int, Int> {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            val bytes = ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
                output.toByteArray()
            }
            return Triple(bytes, widthPx, heightPx)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Validated supported expression through the production planner,
     * built over the ACTUAL authored photo refs (exact JPEG dimensions
     * and SHA-256 of the exact bytes, same order) so the manifest and
     * expression source agreement is genuine, never contradictory.
     */
    private fun supportedExpression(
        refs: List<GeneratorExpression.PhotoRef>,
    ): GeneratorExpression.ResolvedExpression {
        val input = GeneratorExpression.GeneratorInput(
            ownerId = "owner-1",
            epoch = 1L,
            photos = refs,
            note = null,
            music = null,
        )
        val plan = GeneratorEditorialRows.plan(input)
        check(plan is GeneratorEditorialRows.PlanResult.Planned) { "expression plan not available" }
        return (plan as GeneratorEditorialRows.PlanResult.Planned).expression
    }

    /**
     * ORIGINAL-lease fence (ingest-txn pattern, never fresh recapture):
     * the lease captured at entry must still be live and still bound to
     * the same owner. Throws — never a boolean a caller could ignore.
     */
    private fun checkLease(lease: SessionRequestLease, ownerUserId: String) {
        val live = try {
            container.pendingSessionGate.isLive(lease)
        } catch (_: Exception) {
            false
        }
        val leaseOwner = try {
            lease.ownerUserId.toRestString()
        } catch (_: Exception) {
            ""
        }
        if (!live || leaseOwner.isBlank() || leaseOwner != ownerUserId) {
            throw AssertionError("sender session changed")
        }
    }

    private fun markerFile(): File {
        val dir = File(context.filesDir, "pendingtest-publication").apply { mkdirs() }
        return File(dir, "publication.json")
    }

    /**
     * Durable marker write (explicit file sync + directory-entry sync
     * around an atomic tmp+rename): PUBLIC opaque fields only. A failed
     * rename leaves the temp file in place as diagnostics (never
     * deleted — no delete-to-rerun) and fails closed. Never contacts,
     * tokens, keys, passwords, or OTP.
     */
    private fun writeMarker(
        capsuleId: String,
        stage: String,
        deliveryId: String? = null,
        locatorCode: String? = null,
    ) {
        val json = JSONObject()
            .put("capsuleId", capsuleId)
            .put("stage", stage)
        if (deliveryId != null) json.put("deliveryId", deliveryId)
        if (locatorCode != null) json.put("locatorCode", locatorCode)
        val marker = markerFile()
        val dir = marker.parentFile
            ?: throw AssertionError("publication marker not durable")
        val tmp = File(dir, "publication.json.tmp")
        try {
            java.io.FileOutputStream(tmp).use { out ->
                out.write(json.toString().toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            syncDir(dir)
            if (!tmp.renameTo(marker)) {
                throw AssertionError("publication marker not durable")
            }
            syncDir(dir)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw AssertionError("publication marker not durable")
        }
    }

    /** Directory-entry durability for the atomic rename above. */
    private fun syncDir(dir: File) {
        try {
            val fd = android.system.Os.open(
                dir.absolutePath,
                android.system.OsConstants.O_RDONLY,
                0,
            )
            try {
                android.system.Os.fsync(fd)
            } finally {
                android.system.Os.close(fd)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw AssertionError("publication marker not durable")
        }
    }

    @Test
    fun existingAccountPublishesMatchableFrontOverRealHttp(): Unit = runBlocking {
        withTimeout(300_000) {
            // 0. Isolated-build guard FIRST (before any container,
            // account, identity, or network touch).
            if (!BuildConfig.REMANENCE_PENDING_ENABLED ||
                context.packageName != "dev.hryshyn.remanence.pendingtest" ||
                BuildConfig.API_BASE_URL != "http://127.0.0.1:18042/"
            ) {
                throw AssertionError(
                    "missing prerequisite: isolated pendingtest build and loopback epoch required",
                )
            }
            val expectedOwner = stringArgument("t186_expected_owner")
            val expectedBundle = stringArgument("t186_expected_bundle")
            if (expectedOwner.isNullOrBlank() || expectedBundle.isNullOrBlank()) {
                throw AssertionError(
                    "missing prerequisite: expected owner/bundle arguments required",
                )
            }
            try {
                UUID.fromString(expectedOwner)
                UUID.fromString(expectedBundle)
            } catch (_: Exception) {
                throw AssertionError("missing prerequisite: expected arguments not UUIDs")
            }

            // 1. Existing-account prerequisites (never register, seed,
            // adopt, or replace — any gap fails closed here).
            val account = try {
                container.currentAccountStore.load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw AssertionError("prerequisite account store unreadable")
            } ?: throw AssertionError("missing prerequisite: no persisted account")
            if (account.userId != expectedOwner || account.activeKeyBundleId != expectedBundle) {
                throw AssertionError("missing prerequisite: persisted account is not expected")
            }
            val handle = account.handle
            if (handle.isNullOrBlank()) {
                throw AssertionError("missing prerequisite: persisted account has no handle")
            }
            val signing = when (
                val loaded = try {
                    container.identityRepository.load()
                } catch (_: Exception) {
                    null
                }
            ) {
                is dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.LoadResult.Available ->
                    loaded.signingHandle
                else -> throw AssertionError("missing prerequisite: no available identity")
            }
            val lease = try {
                container.pendingSessionGate.capture()
            } catch (_: Exception) {
                null
            } ?: throw AssertionError("missing prerequisite: no live session lease")
            val leaseOwner = try {
                lease.ownerUserId.toRestString()
            } catch (_: Exception) {
                ""
            }
            if (leaseOwner != account.userId) {
                throw AssertionError("missing prerequisite: lease owner mismatch")
            }
            val bearer = try {
                container.pendingSessionGate.accessTokenFor(lease)
            } catch (_: Exception) {
                null
            }.takeIf { !it.isNullOrBlank() }
                ?: throw AssertionError("missing prerequisite: no session bearer")
            val pin = container.pendingServicePin
                ?: throw AssertionError("missing prerequisite: no service pin")
            if (!PendingServicePinConfig.usable(pin)) {
                throw AssertionError("missing prerequisite: service pin unusable")
            }

            // 2. One-shot marker BEFORE any R1 network effect. A
            // pre-existing marker refuses replay (no deletion, no
            // auto-retry — root decides deliberately).
            val capsuleId = UUID.randomUUID().toString()
            val marker = markerFile()
            if (marker.isFile) {
                throw AssertionError("prior publication attempt already recorded; refusing replay")
            }
            try {
                writeMarker(capsuleId, "attempted")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw AssertionError("publication marker not durable")
            }

            val recipient = "t186recv${randomHex(8)}@example.com"
            // Owned buffers accumulate INSIDE the try/finally below so a
            // later allocation failure still wipes partial allocations.
            var photos: List<Triple<ByteArray, Int, Int>> = emptyList()
            var front: ByteArray = ByteArray(0)
            try {
                photos = listOf(
                    deviceJpeg(800, 600, 0xFF336699.toInt()),
                    deviceJpeg(1024, 768, 0xFF993366.toInt()),
                    deviceJpeg(640, 480, 0xFF669933.toInt()),
                )
                front = CanonicalFrontFixture.distinctFront(11)
                // 3. R1 freeze through the real wrapper: frozen PENDING
                // only (never v1/fallback), under the ORIGINAL lease.
                checkLease(lease, account.userId)
                val routeFlow = PendingSendRouteFlow(
                    PendingSendRouteFlow.SendRoutePort { capsule, kind, value, key, token ->
                        container.pendingSendRoutePort.freeze(capsule, kind, value, key, token)
                    },
                )
                val frozenState = withTimeout(60_000) {
                    try {
                        routeFlow.freeze(
                            capsuleId,
                            "email",
                            recipient,
                            UUID.randomUUID().toString(),
                            bearer,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        throw AssertionError("R1 route freeze unavailable")
                    }
                }
                val pending = when (frozenState) {
                    is PendingSendRouteFlow.State.Frozen -> when (val identity = frozenState.identity) {
                        is PendingSendRouteFlow.FrozenIdentity.Pending -> identity
                        is PendingSendRouteFlow.FrozenIdentity.V1 ->
                            throw AssertionError("R1 froze a v1 route for the synthetic recipient")
                    }
                    is PendingSendRouteFlow.State.Failed ->
                        throw AssertionError("R1 route freeze failed(${frozenState.reason})")
                    else -> throw AssertionError("R1 route freeze unexpected state")
                }
                checkLease(lease, account.userId)
                assertTrue(pending.deliveryTargetId.isNotBlank())
                assertEquals(recipient, pending.normalizedDestination)
                assertEquals(pin.deploymentUuid, pending.serviceKeyId)

                // 4. Full authored payload (never photos-only): canonical
                // FRONT parse-verified locally, supported expression over
                // the exact authored refs.
                try {
                    dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec.parse(front).wipe()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    throw AssertionError("sender FRONT framing invalid")
                }
                val photoRefs = photos.mapIndexed { index, (bytes, widthPx, heightPx) ->
                    GeneratorExpression.PhotoRef(
                        contentId = "p${index + 1}",
                        ordinal = index,
                        widthPx = widthPx,
                        heightPx = heightPx,
                        contentHash = sha256Hex(bytes),
                    )
                }
                val expression = try {
                    supportedExpression(photoRefs)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    throw AssertionError("sender expression plan unavailable")
                }
                val authored = PendingBoundaryPublish.Authored(
                    capsuleId = capsuleId,
                    photos = photos.map { (bytes, widthPx, heightPx) ->
                        PendingBoundaryPublish.PhotoBytes(bytes, widthPx, heightPx)
                    },
                    manifests = PendingBoundaryPublish.ManifestInputs(
                        senderHandleSnapshot = handle,
                        frontFingerprintBytes = front,
                        noteUtf8 = null,
                        expression = dev.hryshyn.remanence.core.crypto.PendingCapsulePublisher.ExpressionInput(
                            "cand-${randomHex(8)}",
                            expression,
                            photoRefs.map { it.contentId },
                            null,
                        ),
                    ),
                    claimExpiresAt = pending.claimDeadline,
                    createdAtEpochSeconds = System.currentTimeMillis() / 1000L,
                )
                val ports = container.pendingBoundaryPorts
                checkLease(lease, account.userId)
                val published = withTimeout(180_000) {
                    try {
                        PendingBoundaryPublish.run(
                            frozen = pending,
                            authored = authored,
                            identity = PendingSenderIdentity(
                                account.userId,
                                account.activeKeyBundleId,
                                signing,
                            ),
                            pin = pin,
                            sessionGate = container.pendingSessionGate,
                            accountProvider = container.pendingFeedAccount,
                            draftPort = ports.draft,
                            blobPort = ports.blob,
                            finalizePort = ports.finalize,
                            expectedLease = lease,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        throw AssertionError("sender boundary publish unavailable")
                    }
                }
                checkLease(lease, account.userId)
                val locator = when (published) {
                    is PendingBoundaryPublish.Result.Published -> published
                    is PendingBoundaryPublish.Result.Failed ->
                        throw AssertionError("sender boundary publish failed(${published.reason})")
                }
                assertTrue(locator.deliveryId.isNotBlank())
                assertEquals(PendingCodeNormalizer.CODE_LENGTH, locator.locatorCode.length)
                assertTrue(
                    locator.locatorCode.all { it in PendingCodeNormalizer.ALPHABET },
                )
                try {
                    writeMarker(capsuleId, "published", locator.deliveryId, locator.locatorCode)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    throw AssertionError("publication marker not durable")
                }

                // 5. Independent anonymous R6 lookup (sender scope ends;
                // no proof/claim/retire inference).
                checkLease(lease, account.userId)
                val found = withTimeout(60_000) {
                    try {
                        PendingGiftLookupRepository.create(
                            container.apiBaseUrl,
                        ).lookup(locator.locatorCode)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        throw AssertionError("R6 locator lookup unavailable")
                    }
                }
                checkLease(lease, account.userId)
                when (found) {
                    is GiftLookupResult.Found -> {
                        assertTrue(found.giftReference.isNotBlank())
                        assertEquals("email", found.channel)
                    }
                    is GiftLookupResult.Failure ->
                        throw AssertionError(
                            "R6 locator lookup failed(${found.reason}," +
                                "status=${found.httpStatus})",
                        )
                }
                // Durable completion handoff: public opaque fields only.
                try {
                    writeMarker(capsuleId, "verified", locator.deliveryId, locator.locatorCode)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    throw AssertionError("publication handoff not durable")
                }
            } finally {
                photos.forEach { (bytes, _, _) -> bytes.fill(0) }
                front.fill(0)
            }
        }
    }
}
