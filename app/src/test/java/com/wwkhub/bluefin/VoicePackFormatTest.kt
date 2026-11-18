package com.wwkhub.bluefin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `voice.json` is user-supplied on import, so every rejection path is part of
 * the contract: a bad pack must fail loudly at import time instead of producing
 * a timbre that synthesises garbage later.
 */
class VoicePackFormatTest {

    private fun body(
        id: String = "my-timbre",
        name: String = "我的音色",
        kind: String = "zipvoice",
        reference: String = "reference.wav",
        text: String = "今天也要好好休息哦。",
        sampleRate: Int = 24_000,
        speed: String = "1.0",
        steps: String = "4",
        format: Int = 1,
    ): String = """
        {
          "format": $format,
          "id": "$id",
          "name": "$name",
          "kind": "$kind",
          "reference": "$reference",
          "referenceText": "$text",
          "sampleRate": $sampleRate,
          "speed": $speed,
          "numSteps": $steps,
          "about": "导入的音色"
        }
    """.trimIndent()

    private fun reject(expected: VoicePackReject, block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue("expected VoicePackException but got $error", error is VoicePackException)
        assertEquals(expected, (error as VoicePackException).reason)
    }

    @Test
    fun parsesAMinimalPack() {
        val pack = VoicePackFormat.parse(body())
        assertEquals("my-timbre", pack.id)
        assertEquals("我的音色", pack.name)
        assertEquals(VoicePackFormat.KIND_ZIPVOICE, pack.kind)
        assertEquals("reference.wav", pack.reference)
        assertEquals(24_000, pack.sampleRate)
        assertEquals(1.0f, pack.speed, 0.001f)
        assertEquals(4, pack.numSteps)
        assertFalse(pack.builtIn)
    }

    @Test
    fun bundledIdsAreMarkedAsBuiltIn() {
        // Loading a bundled pack allows the built-in ids; the flag on the parsed
        // object is derived from the id, not from the caller.
        assertTrue(VoicePackFormat.parse(body(id = "timbre1")).builtIn)
        assertEquals(listOf("timbre1", "timbre2"), VoicePackFormat.BUILT_IN_IDS)
    }

    @Test
    fun importRejectsTheBundledIds() {
        reject(VoicePackReject.BUILT_IN_ID) {
            VoicePackFormat.parse(body(id = "timbre2"), rejectBuiltInIds = true)
        }
    }

    @Test
    fun importRejectsTheReservedId() {
        reject(VoicePackReject.RESERVED_ID) {
            VoicePackFormat.parse(body(id = PackFormat.RESERVED_ID), rejectBuiltInIds = true)
        }
    }

    @Test
    fun rejectsAnInvalidId() {
        reject(VoicePackReject.INVALID_ID) {
            VoicePackFormat.parse(body(id = "Bad Id"))
        }
    }

    @Test
    fun rejectsAnUnknownFormatVersion() {
        reject(VoicePackReject.INCOMPATIBLE) {
            VoicePackFormat.parse(body(format = 2))
        }
    }

    @Test
    fun rejectsAnUnknownKind() {
        reject(VoicePackReject.INCOMPATIBLE) {
            VoicePackFormat.parse(body(kind = "vits"))
        }
    }

    @Test
    fun rejectsAReferenceOutsideThePack() {
        reject(VoicePackReject.INVALID_REFERENCE) {
            VoicePackFormat.parse(body(reference = "../escape.wav"))
        }
    }

    @Test
    fun rejectsANonWavReference() {
        reject(VoicePackReject.INVALID_REFERENCE) {
            VoicePackFormat.parse(body(reference = "reference.mp3"))
        }
    }

    @Test
    fun rejectsAnEmptyTranscript() {
        reject(VoicePackReject.INVALID_TEXT) {
            VoicePackFormat.parse(body(text = "   "))
        }
    }

    @Test
    fun rejectsAnOverlongTranscript() {
        reject(VoicePackReject.INVALID_TEXT) {
            VoicePackFormat.parse(body(text = "啊".repeat(VoicePackFormat.MAX_TEXT_CHARS + 1)))
        }
    }

    @Test
    fun rejectsATuningValueOutOfRange() {
        reject(VoicePackReject.INVALID_TUNING) {
            VoicePackFormat.parse(body(sampleRate = 4_000))
        }
        reject(VoicePackReject.INVALID_TUNING) {
            VoicePackFormat.parse(body(speed = "3.0"))
        }
        reject(VoicePackReject.INVALID_TUNING) {
            VoicePackFormat.parse(body(steps = "0"))
        }
    }

    @Test
    fun rejectsBrokenJson() {
        reject(VoicePackReject.BAD_JSON) {
            VoicePackFormat.parse("{not json")
        }
    }

    @Test
    fun rejectsANonObjectRoot() {
        reject(VoicePackReject.BAD_JSON) {
            VoicePackFormat.parse("[]")
        }
    }

    @Test
    fun rejectsAMissingFormatField() {
        reject(VoicePackReject.INCOMPLETE) {
            VoicePackFormat.parse("""{"id":"x","name":"x"}""")
        }
    }

    @Test
    fun acceptsCloudKindWithFishReferenceId() {
        val fishId = "9a9cf47702da476aa4629e2506d4a857"
        val pack = VoicePackFormat.parse(
            body(kind = "fish").replace(
                "\"about\": \"导入的音色\"",
                "\"about\": \"导入的音色\", \"fishReferenceId\": \"$fishId\"",
            ),
        )
        assertEquals(VoicePackFormat.KIND_FISH, pack.kind)
        assertEquals(fishId, pack.fishReferenceId)
        // The local binding survives: one pack serves both engines.
        assertEquals("reference.wav", pack.reference)
    }

    @Test
    fun legacyZipVoicePacksKeepWorkingWithoutFishId() {
        val pack = VoicePackFormat.parse(body(kind = "zipvoice"))
        assertEquals(VoicePackFormat.KIND_ZIPVOICE, pack.kind)
        assertEquals(null, pack.fishReferenceId)
    }

    @Test
    fun rejectsAMalformedFishReferenceId() {
        reject(VoicePackReject.INVALID_TUNING) {
            VoicePackFormat.parse(
                body(kind = "fish").replace(
                    "\"about\": \"导入的音色\"",
                    "\"about\": \"导入的音色\", \"fishReferenceId\": \"not-hex!\"",
                ),
            )
        }
    }

    @Test
    fun tuningDefaultsWhenOmitted() {
        val pack = VoicePackFormat.parse(
            """
            {
              "format": 1,
              "id": "plain",
              "name": "素色",
              "kind": "zipvoice",
              "reference": "reference.wav",
              "referenceText": "好。",
              "sampleRate": 24000
            }
            """.trimIndent(),
        )
        assertEquals(1.0f, pack.speed, 0.001f)
        assertEquals(4, pack.numSteps)
    }
}
