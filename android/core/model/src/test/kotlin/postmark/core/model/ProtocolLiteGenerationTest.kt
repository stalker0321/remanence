package postmark.core.model

import app.postmark.protocol.v1.ContentManifest
import app.postmark.protocol.v1.PublishStatement
import com.google.protobuf.GeneratedMessageLite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtocolLiteGenerationTest {
    @Test
    fun generatedTypesAreLiteAndOptionalNoteRoundTrips() {
        assertEquals(GeneratedMessageLite::class.java.name, PublishStatement::class.java.superclass.name)
        assertEquals(GeneratedMessageLite::class.java.name, ContentManifest::class.java.superclass.name)

        val statement = PublishStatement.newBuilder()
            .setProtocolVersion(1)
            .setCreatedAtEpochSeconds(1)
            .build()
        val parsedStatement = PublishStatement.parseFrom(statement.toByteArray())
        assertEquals(1, parsedStatement.protocolVersion)
        assertEquals(1, parsedStatement.createdAtEpochSeconds)

        val withoutNote = ContentManifest.newBuilder()
            .setProtocolVersion(1)
            .build()
        assertFalse(withoutNote.hasNote())
        val withNote = ContentManifest.newBuilder()
            .setProtocolVersion(1)
            .setNote("hello")
            .build()
        assertTrue(withNote.hasNote())
        assertEquals("hello", withNote.note)
        val parsedManifest = ContentManifest.parseFrom(withNote.toByteArray())
        assertTrue(parsedManifest.hasNote())
        assertEquals("hello", parsedManifest.note)
        assertFalse(ContentManifest.parseFrom(withoutNote.toByteArray()).hasNote())
    }
}
