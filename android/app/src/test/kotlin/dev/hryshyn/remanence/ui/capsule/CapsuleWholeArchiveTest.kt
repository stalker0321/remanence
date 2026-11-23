package dev.hryshyn.remanence.ui.capsule

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** ADR-021 §6: the whole postcard is one archive with the site's file names. */
class CapsuleWholeArchiveTest {

    @Test
    fun thePictureLetterAndRecordingsComeBackByteForByte() {
        val png = ByteArray(300) { it.toByte() }
        val letter = "Дорогая мама".toByteArray(Charsets.UTF_8)
        val voice = byteArrayOf(0, 0, 0, 16) + "ftypM4A ".toByteArray()
        val video = ByteArray(5000) { 7 }
        val zip = CapsuleWholeArchive.build(png, listOf("letter.txt" to letter, "voice.m4a" to voice, "video.mp4" to video))
        val read = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                read[entry.name] = input.readBytes()
            }
        }
        assertEquals(listOf("postcard.png", "letter.txt", "voice.m4a", "video.mp4"), read.keys.toList())
        assertArrayEquals(png, read["postcard.png"])
        assertArrayEquals(letter, read["letter.txt"])
        assertArrayEquals(voice, read["voice.m4a"])
        assertArrayEquals(video, read["video.mp4"])
        // The same postcard gives the same bytes.
        assertArrayEquals(zip, CapsuleWholeArchive.build(png, listOf("letter.txt" to letter, "voice.m4a" to voice, "video.mp4" to video)))
    }
}
