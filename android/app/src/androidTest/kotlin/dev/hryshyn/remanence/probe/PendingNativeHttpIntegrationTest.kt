package dev.hryshyn.remanence.probe

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hryshyn.remanence.RemanenceApplication
import dev.hryshyn.remanence.core.data.network.GiftLookupResult
import dev.hryshyn.remanence.core.data.network.PendingGiftLookupRepository
import dev.hryshyn.remanence.core.data.network.SessionRequestLease
import dev.hryshyn.remanence.core.model.GeneratorEditorialRows
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.pending.PendingBoundaryPublish
import dev.hryshyn.remanence.pending.PendingCodeNormalizer
import dev.hryshyn.remanence.pending.PendingSendRouteFlow
import dev.hryshyn.remanence.pending.PendingSenderIdentity
import dev.hryshyn.remanence.pending.PendingServicePinConfig
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T186 native sender HTTP fixture (device only — root runs it, no JVM
 * claim). One genuine isolated sender leg over real HTTP against the
 * isolated dev epoch, using the production container wiring end to end:
 *
 * real registration (generated Tink identity wrapped + persisted through
 * the normal path, synthetic account/handle) → held ORIGINAL lease +
 * bearer (fenced pre/post every phase, never recaptured) → R1 freeze
 * through the real [PendingSendRouteFlow] wrapper (frozen PENDING
 * route) → [PendingBoundaryPublish] full payload (3 real device JPEGs
 * with recorded dimensions, canonical SIFT FRONT framing, validated
 * supported expression whose photo refs carry the exact JPEG bytes'
 * dimensions and SHA-256, out-of-band pin) → real R3/R4/R5 → 5-char
 * locator → independent anonymous R6 lookup.
 *
 * Honest limits: sender leg ONLY — no OTP/proof/claim/R15, so nothing
 * here implies receiver admission, opening, camera match, grant, or
 * retirement. No MainActivity/Google/PRF/camera/receiver acceptance.
 * Failures name only the coarse phase plus status/reason enums (never
 * tokens, passwords, keys, bodies, or URLs); test-owned JPEG/FRONT
 * bytes and the password chars are wiped in `finally` on all outcomes
 * (cancellation still propagates). Each run registers a fresh
 * synthetic account; a pre-existing local account or identity is an
 * explicit missing prerequisite, never overwritten.
 */
@RunWith(AndroidJUnit4::class)
class PendingNativeHttpIntegrationTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as RemanenceApplication).container

    private val random = SecureRandom()

    private fun randomHex(chars: Int): String {
        val alphabet = "0123456789abcdef"
        return (0 until chars).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    private fun epochTag(): String =
        java.lang.Long.toString(System.currentTimeMillis(), 36) + randomHex(4)

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
     * Canonical SIFT FRONT framing (mirrors the unit-test canonical
     * fixture byte-for-byte in structure — hand-built keypoints plus
     * descriptors serialized through the frozen codec; no extractor
     * algorithm change). The driver self-checks validity by parsing
     * before publish.
     */
    private fun syntheticFront(seed: Int): ByteArray {
        val profile = RecognitionProfile.postcardSiftRootSiftV1()
        val micro = dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec.MICRO_UNITS
        val keypoints = List(64) {
            dev.hryshyn.remanence.core.model.SiftRootSiftKeypoint(
                xMicro = Math.rint((it % 8) / 8.0 * micro).toInt(),
                yMicro = Math.rint((it / 8) / 8.0 * micro).toInt(),
                scaleMicro = micro,
                angleCentiDegrees = 0,
                responseQuantized = it,
                octave = 0,
            )
        }
        val fp = dev.hryshyn.remanence.core.model.SiftRootSiftFingerprint(
            profileId = profile.profileId,
            canonicalWidthPx = profile.capture.canonicalLongEdgePx,
            canonicalHeightPx = 1000,
            coarseHash64 = seed.toLong(),
            keypoints = keypoints,
            quantizedSiftDescriptors = List(64) { i ->
                ByteArray(dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec.DESCRIPTOR_BYTES) {
                    ((it * 7 + i * 13 + seed * 29).coerceIn(1, 255)).toByte()
                }
            },
        )
        return try {
            dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec.serialize(fp)
        } finally {
            fp.wipe()
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

    @Test
    fun senderLegPublishesFullPayloadOverRealHttp(): Unit = runBlocking {
        withTimeout(300_000) {
            val tag = epochTag()
            val email = "t186s$tag@example.test"
            val passwordChars = CharArray(20) {
                "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"[random.nextInt(62)]
            }
            val handle = "t186snd${randomHex(6)}"
            val recipient = "t186r$tag@example.test"
            val photos = listOf(
                deviceJpeg(800, 600, 0xFF336699.toInt()),
                deviceJpeg(1024, 768, 0xFF993366.toInt()),
                deviceJpeg(640, 480, 0xFF669933.toInt()),
            )
            val front = syntheticFront(11)
            try {
                // 0. Pristine guard: never register over an existing local
                // account or replace existing wrapped keys. Anything present
                // is an explicit missing prerequisite, not fake success.
                val preAccount = try {
                    container.currentAccountStore.load()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    throw AssertionError("prerequisite account store unreadable")
                }
                val identityExists = try {
                    container.identityRepository.exists()
                } catch (_: Exception) {
                    true
                }
                if (preAccount != null || identityExists) {
                    throw AssertionError(
                        "missing prerequisite: local account or identity already present",
                    )
                }

                // 1. Real registration: generated identity wrapped +
                // persisted through the normal path (no seeded ready, no
                // mock session).
                val registered = withTimeout(60_000) {
                    try {
                        container.registrationUseCase.register(
                            email,
                            String(passwordChars),
                            handle,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        throw AssertionError("sender registration unavailable")
                    }
                }
                val registeredUser = when (registered) {
                    is dev.hryshyn.remanence.auth.RegistrationUseCase.Outcome.Registered -> registered
                    is dev.hryshyn.remanence.auth.RegistrationUseCase.Outcome.Rejected ->
                        throw AssertionError(
                            "sender registration rejected(status=${registered.httpStatus}," +
                                "reason=${registered.problemCode})",
                        )
                    is dev.hryshyn.remanence.auth.RegistrationUseCase.Outcome.NetworkUnreachable ->
                        throw AssertionError("sender registration network unreachable")
                    is dev.hryshyn.remanence.auth.RegistrationUseCase.Outcome.InvalidResponse ->
                        throw AssertionError("sender registration invalid response")
                    is dev.hryshyn.remanence.auth.RegistrationUseCase.Outcome.RecoveryRequired ->
                        throw AssertionError("sender registration recovery required")
                }
                assertTrue(registeredUser.userId.isNotBlank())
                assertTrue(registeredUser.activeKeyBundleId.isNotBlank())

                // 2. ORIGINAL held lease + bearer (captured once; fenced
                // pre/post every phase below, never recaptured).
                val lease = try {
                    container.pendingSessionGate.capture()
                } catch (_: Exception) {
                    null
                } ?: throw AssertionError("sender session lease unavailable")
                val leaseOwner = try {
                    lease.ownerUserId.toRestString()
                } catch (_: Exception) {
                    ""
                }
                assertEquals(registeredUser.userId, leaseOwner)
                val bearer = try {
                    container.pendingSessionGate.accessTokenFor(lease)
                } catch (_: Exception) {
                    null
                }.takeIf { !it.isNullOrBlank() }
                    ?: throw AssertionError("sender session bearer unavailable")

                // 3. R1 freeze through the real wrapper: must come back a
                // frozen PENDING route (never v1 for the synthetic
                // recipient, never a failure).
                val capsuleId = UUID.randomUUID().toString()
                checkLease(lease, registeredUser.userId)
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
                checkLease(lease, registeredUser.userId)
                assertTrue(pending.deliveryTargetId.isNotBlank())
                assertEquals(recipient, pending.normalizedDestination)

                // Out-of-band pin: exact bound pair, matching the frozen
                // service key id (never an R-carried key as authority).
                val pin = container.pendingServicePin
                    ?: throw AssertionError("sender service pin unavailable")
                assertTrue(PendingServicePinConfig.usable(pin))
                assertEquals(pin.deploymentUuid, pending.serviceKeyId)

                // 4. Full authored payload (never the photos-only path):
                // canonical FRONT (parse-verified locally first) plus a
                // supported expression over the exact authored refs.
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
                        senderHandleSnapshot = registeredUser.handle ?: handle,
                        frontFingerprintBytes = front,
                        noteUtf8 = null,
                        expression = dev.hryshyn.remanence.core.crypto.PendingCapsulePublisher.ExpressionInput(
                            "cand-$tag".take(24),
                            expression,
                            photoRefs.map { it.contentId },
                            null,
                        ),
                    ),
                    claimExpiresAt = pending.claimDeadline,
                    createdAtEpochSeconds = System.currentTimeMillis() / 1000L,
                )
                val signing = when (
                    val loaded = try {
                        container.identityRepository.load()
                    } catch (_: Exception) {
                        null
                    }
                ) {
                    is dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.LoadResult.Available ->
                        loaded.signingHandle
                    else -> throw AssertionError("sender signing identity unavailable")
                }
                val ports = container.pendingBoundaryPorts
                checkLease(lease, registeredUser.userId)
                val published = withTimeout(180_000) {
                    try {
                        PendingBoundaryPublish.run(
                            frozen = pending,
                            authored = authored,
                            identity = PendingSenderIdentity(
                                registeredUser.userId,
                                registeredUser.activeKeyBundleId,
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
                checkLease(lease, registeredUser.userId)
                val locator = when (published) {
                    is PendingBoundaryPublish.Result.Published -> published
                    is PendingBoundaryPublish.Result.Failed ->
                        throw AssertionError("sender boundary publish failed(${published.reason})")
                }
                assertTrue(locator.deliveryId.isNotBlank())
                // Five-char Crockford locator (public routing, never secret).
                assertEquals(PendingCodeNormalizer.CODE_LENGTH, locator.locatorCode.length)
                assertTrue(
                    locator.locatorCode.all { it in PendingCodeNormalizer.ALPHABET },
                )

                // 5. Independent anonymous R6 lookup verifies the locator
                // without any recipient proof or claim (sender scope ends).
                checkLease(lease, registeredUser.userId)
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
                checkLease(lease, registeredUser.userId)
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
            } finally {
                // Test-owned bytes and password chars die on all outcomes;
                // cancellation still propagates (no swallowing).
                passwordChars.fill('0')
                photos.forEach { (bytes, _, _) -> bytes.fill(0) }
                front.fill(0)
            }
        }
    }
}
