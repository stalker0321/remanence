package dev.hryshyn.remanence.core.model

import com.google.protobuf.ByteString
import com.google.protobuf.CodedOutputStream
import dev.hryshyn.remanence.recognition.v2.SiftRootSiftFingerprint as FingerprintWire
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SiftRootSiftFingerprintCodecTest {
    @Test
    fun `format 3 round trips canonically with exact descriptor rows`() {
        val original = fingerprint(1)
        val encoded = SiftRootSiftFingerprintCodec.serialize(original)
        val parsed = SiftRootSiftFingerprintCodec.parse(encoded)

        assertContentEquals(encoded, SiftRootSiftFingerprintCodec.serialize(parsed))
        assertEquals(SiftRootSiftFingerprintCodec.PROFILE_ID, parsed.profileId)
        assertEquals(3, parsed.keypoints.size)
        original.quantizedSiftDescriptors.zip(parsed.quantizedSiftDescriptors).forEach { (expected, actual) ->
            assertContentEquals(expected, actual)
        }
        assertTrue(parsed.descriptorRowIsMatcherUsable(0))
        assertFalse(parsed.descriptorRowIsMatcherUsable(1))
        parsed.wipe()
        assertTrue(parsed.quantizedSiftDescriptors.all { row -> row.all { it == 0.toByte() } })
    }

    @Test
    fun `all zero descriptor row is storage valid but matcher unusable`() {
        val fingerprint = fingerprint(1)
        fingerprint.quantizedSiftDescriptors[1].fill(0)

        val parsed = SiftRootSiftFingerprintCodec.parse(SiftRootSiftFingerprintCodec.serialize(fingerprint))

        assertFalse(parsed.descriptorRowIsMatcherUsable(1))
    }

    @Test
    fun `quantization uses ties to even and clips raw SIFT values`() {
        val values = DoubleArray(SiftRootSiftFingerprintCodec.DESCRIPTOR_BYTES)
        values[0] = 0.5
        values[1] = 1.5
        values[2] = 2.5
        values[3] = -0.25
        values[4] = 1.25
        values[5] = 255.25

        val quantized = SiftRootSiftFingerprintCodec.quantizeRawSift(values)

        assertEquals(0, quantized[0].toInt() and 0xFF)
        assertEquals(2, quantized[1].toInt() and 0xFF)
        assertEquals(2, quantized[2].toInt() and 0xFF)
        assertEquals(0, quantized[3].toInt() and 0xFF)
        assertEquals(1, quantized[4].toInt() and 0xFF)
        assertEquals(255, quantized[5].toInt() and 0xFF)
        assertFailsWith<IllegalArgumentException> {
            SiftRootSiftFingerprintCodec.quantizeRawSift(values.copyOf().also { it[6] = Double.NaN })
        }
    }

    @Test
    fun `profile version count and descriptor bounds fail closed`() {
        val valid = SiftRootSiftFingerprintCodec.serialize(fingerprint(1))
        val wrongVersion = FingerprintWire.parseFrom(valid).toBuilder()
            .setFormatVersion(2)
            .build()
        assertFailsWith<IllegalArgumentException> {
            SiftRootSiftFingerprintCodec.parse(wrongVersion.toByteArray())
        }

        val wrongProfile = FingerprintWire.parseFrom(valid).toBuilder()
            .setRecognitionProfileId("postcard-sift-rootsift-v9")
            .build()
        assertFailsWith<IllegalArgumentException> {
            SiftRootSiftFingerprintCodec.parse(wrongProfile.toByteArray())
        }

        val zeroKeypoints = FingerprintWire.newBuilder()
            .setFormatVersion(3)
            .setRecognitionProfileId(SiftRootSiftFingerprintCodec.PROFILE_ID)
            .setCanonicalWidthPx(1600)
            .setCanonicalHeightPx(1000)
            .setQuantizedSiftDescriptors(ByteString.EMPTY)
            .build()
        assertFailsWith<IllegalArgumentException> {
            SiftRootSiftFingerprintCodec.parse(zeroKeypoints.toByteArray())
        }

        val misaligned = FingerprintWire.parseFrom(valid).toBuilder()
            .setQuantizedSiftDescriptors(ByteString.copyFrom(ByteArray(127)))
            .build()
        assertFailsWith<IllegalArgumentException> {
            SiftRootSiftFingerprintCodec.parse(misaligned.toByteArray())
        }

        val tooMany = FingerprintWire.parseFrom(valid).toBuilder().clearKeypoints()
        repeat(SiftRootSiftFingerprintCodec.MAX_KEYPOINTS + 1) {
            tooMany.addKeypoints(validKeypoint())
        }
        tooMany.setQuantizedSiftDescriptors(
            ByteString.copyFrom(ByteArray((SiftRootSiftFingerprintCodec.MAX_KEYPOINTS + 1) * 128)),
        )
        assertFailsWith<IllegalArgumentException> {
            SiftRootSiftFingerprintCodec.parse(tooMany.build().toByteArray())
        }
    }

    @Test
    fun `bounded integer metadata fails closed`() {
        val valid = FingerprintWire.parseFrom(SiftRootSiftFingerprintCodec.serialize(fingerprint(1)))
        val invalids = listOf(
            valid.toBuilder().setKeypoints(0, validKeypoint().toBuilder().setXMicro(1_000_001)).build(),
            valid.toBuilder().setKeypoints(0, validKeypoint().toBuilder().setYMicro(1_000_001)).build(),
            valid.toBuilder().setKeypoints(0, validKeypoint().toBuilder().setScaleMicro(1_000_001)).build(),
            valid.toBuilder().setKeypoints(0, validKeypoint().toBuilder().setAngleCentiDegrees(36_000)).build(),
            valid.toBuilder().setKeypoints(0, validKeypoint().toBuilder().setResponseQuantized(1_000_001)).build(),
            valid.toBuilder().setKeypoints(0, validKeypoint().toBuilder().setOctave(9)).build(),
        )
        invalids.forEach { wire ->
            assertFailsWith<IllegalArgumentException> {
                SiftRootSiftFingerprintCodec.parse(wire.toByteArray())
            }
        }
    }

    @Test
    fun `unknown duplicate and noncanonical fields fail exact byte equality`() {
        val valid = SiftRootSiftFingerprintCodec.serialize(fingerprint(1))
        val unknown = valid + byteArrayOf(0x50, 0x01)
        val duplicateFormat = valid + byteArrayOf(0x08, 0x03)
        val outOfOrder = byteArrayOf(0x28, 0x01) + valid

        listOf(unknown, duplicateFormat, outOfOrder).forEach { bytes ->
            assertFailsWith<IllegalArgumentException> {
                SiftRootSiftFingerprintCodec.parse(bytes)
            }
        }
    }

    @Test
    fun `empty malformed and over-cap payloads fail before unbounded parsing`() {
        assertFailsWith<IllegalArgumentException> {
            SiftRootSiftFingerprintCodec.parse(ByteArray(0))
        }
        assertFailsWith<IllegalArgumentException> {
            SiftRootSiftFingerprintCodec.parse(
                ByteArray(SiftRootSiftFingerprintCodec.MAX_SERIALIZED_BYTES + 1) { 0x7F.toByte() },
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SiftRootSiftFingerprintCodec.parse(byteArrayOf(0x08, 0x03))
        }
    }

    @Test
    fun `identical repeated keypoint messages remain accepted`() {
        val source = fingerprint(2)
        val repeated = SiftRootSiftFingerprint(
            profileId = source.profileId,
            canonicalWidthPx = source.canonicalWidthPx,
            canonicalHeightPx = source.canonicalHeightPx,
            coarseHash64 = source.coarseHash64,
            keypoints = listOf(source.keypoints[0], source.keypoints[0]),
            quantizedSiftDescriptors = listOf(
                source.quantizedSiftDescriptors[0].copyOf(),
                source.quantizedSiftDescriptors[1].copyOf(),
            ),
        )

        val parsed = SiftRootSiftFingerprintCodec.parse(SiftRootSiftFingerprintCodec.serialize(repeated))

        assertEquals(2, parsed.keypoints.size)
        assertEquals(parsed.keypoints[0], parsed.keypoints[1])
        assertContentEquals(repeated.quantizedSiftDescriptors[0], parsed.quantizedSiftDescriptors[0])
        assertContentEquals(repeated.quantizedSiftDescriptors[1], parsed.quantizedSiftDescriptors[1])
    }

    @Test
    fun `malformed UTF-8 in a known string field is rejected`() {
        val output = ByteArrayOutputStream()
        val wire = CodedOutputStream.newInstance(output)
        wire.writeUInt32(1, SiftRootSiftFingerprintCodec.FORMAT_VERSION)
        wire.writeTag(2, 2)
        wire.writeUInt32NoTag(2)
        wire.writeRawBytes(byteArrayOf(0xC3.toByte(), 0x28))
        wire.flush()

        assertFailsWith<IllegalArgumentException> {
            SiftRootSiftFingerprintCodec.parse(output.toByteArray())
        }
    }

    @Test
    fun `actual known-field wire reordering is rejected as noncanonical`() {
        val source = fingerprint(2)
        val output = ByteArrayOutputStream()
        val wire = CodedOutputStream.newInstance(output)
        // These are all known fields, deliberately emitted in a noncanonical
        // order. Parsing succeeds, but the codec's rebuilt bytes differ.
        wire.writeFixed64(5, source.coarseHash64)
        wire.writeUInt32(1, SiftRootSiftFingerprintCodec.FORMAT_VERSION)
        wire.writeString(2, SiftRootSiftFingerprintCodec.PROFILE_ID)
        wire.writeUInt32(3, source.canonicalWidthPx)
        wire.writeUInt32(4, source.canonicalHeightPx)
        source.keypoints.forEach { keypoint ->
            val encodedKeypoint = wireKeypoint(keypoint).toByteArray()
            wire.writeTag(6, 2)
            wire.writeUInt32NoTag(encodedKeypoint.size)
            wire.writeRawBytes(encodedKeypoint)
        }
        val descriptors = ByteArray(source.quantizedSiftDescriptors.size * SiftRootSiftFingerprintCodec.DESCRIPTOR_BYTES)
        source.quantizedSiftDescriptors.forEachIndexed { index, row ->
            row.copyInto(descriptors, index * SiftRootSiftFingerprintCodec.DESCRIPTOR_BYTES)
        }
        wire.writeBytes(7, ByteString.copyFrom(descriptors))
        wire.flush()

        assertFailsWith<IllegalArgumentException> {
            SiftRootSiftFingerprintCodec.parse(output.toByteArray())
        }
    }

    @Test
    fun `descriptor byte FF is preserved and interpreted as unsigned`() {
        val source = fingerprint(2)
        source.quantizedSiftDescriptors[0][0] = 0xFF.toByte()

        val parsed = SiftRootSiftFingerprintCodec.parse(SiftRootSiftFingerprintCodec.serialize(source))

        assertEquals(255, parsed.quantizedSiftDescriptors[0][0].toInt() and 0xFF)
        assertTrue(parsed.descriptorRowIsMatcherUsable(0))
    }

    private fun fingerprint(seed: Int): SiftRootSiftFingerprint = SiftRootSiftFingerprint(
        profileId = SiftRootSiftFingerprintCodec.PROFILE_ID,
        canonicalWidthPx = 1600,
        canonicalHeightPx = 1000,
        coarseHash64 = 0x1122334455667788L,
        keypoints = List(3) { index ->
            SiftRootSiftKeypoint(
                xMicro = 100_000 + index * 10,
                yMicro = 200_000 + index * 10,
                scaleMicro = 300_000 + index * 10,
                angleCentiDegrees = 90 + index,
                responseQuantized = 400_000 + index,
                octave = index - 1,
            )
        },
        quantizedSiftDescriptors = List(3) { row ->
            ByteArray(SiftRootSiftFingerprintCodec.DESCRIPTOR_BYTES) { index ->
                if (seed == 1 && row == 1) 0 else (index + row + seed).toByte()
            }
        },
    )

    private fun validKeypoint(): FingerprintWire.Keypoint = FingerprintWire.Keypoint.newBuilder()
        .setXMicro(100_000)
        .setYMicro(200_000)
        .setScaleMicro(300_000)
        .setAngleCentiDegrees(90)
        .setResponseQuantized(400_000)
        .setOctave(0)
        .build()

    private fun wireKeypoint(keypoint: SiftRootSiftKeypoint): FingerprintWire.Keypoint =
        FingerprintWire.Keypoint.newBuilder()
            .setXMicro(keypoint.xMicro)
            .setYMicro(keypoint.yMicro)
            .setScaleMicro(keypoint.scaleMicro)
            .setAngleCentiDegrees(keypoint.angleCentiDegrees)
            .setResponseQuantized(keypoint.responseQuantized)
            .setOctave(keypoint.octave)
            .build()
}
