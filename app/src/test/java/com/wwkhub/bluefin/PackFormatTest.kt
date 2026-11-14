package com.wwkhub.bluefin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PackFormatTest {
    private fun manifest(
        id: String = "format-fixture",
        version: String? = "1.0.0",
        vocab: String = "[]",
        extra: String = "",
    ): String {
        val versionLine = if (version == null) "" else "\"version\": \"$version\","
        return """
            {
              "manifest": 2,
              "api": 2,
              "id": "$id",
              $versionLine
              "name": {"zh": "格式测试"},
              "about": {"zh": "只用于导入测试的占位角色。"},
              "entry": "figure.js",
              "export": "createFixture",
              "vocab": $vocab
              $extra
            }
        """.trimIndent()
    }

    private fun accepted(raw: String): PackManifest {
        val result = PackFormat.validate(raw)
        assertTrue("expected accepted, got $result", result is PackValidation.Accepted)
        return (result as PackValidation.Accepted).manifest
    }

    private fun rejected(raw: String): PackReject {
        val result = PackFormat.validate(raw)
        assertTrue("expected rejected, got $result", result is PackValidation.Rejected)
        return (result as PackValidation.Rejected).reason
    }

    @Test
    fun minimalPackIsAcceptedAndMissingVersionBecomesZero() {
        val parsed = accepted(manifest(version = null))
        assertEquals("format-fixture", parsed.id)
        assertEquals("0.0.0", parsed.version)
        assertEquals("figure.js", parsed.entry)
        assertEquals("createFixture", parsed.export)
        assertTrue(parsed.canWalk)
        assertTrue(parsed.vocab.isEmpty())
        assertEquals("格式测试", parsed.displayName())
    }

    @Test
    fun builtInAndReservedIdsCannotBeImported() {
        assertEquals(PackReject.RESERVED_ID, rejected(manifest(id = "coo")))
        assertEquals(PackReject.BUILT_IN_ID, rejected(manifest(id = PackFormat.BUILT_IN_ID)))
        val builtIn = PackFormat.validate(
            manifest(id = PackFormat.BUILT_IN_ID),
            builtInIds = emptySet(),
            allowBuiltInId = true,
        )
        assertTrue(builtIn is PackValidation.Accepted)
    }

    @Test
    fun invalidIdsAreRejected() {
        listOf("BlueFin", "bad_id", "-leading", "a".repeat(33)).forEach { id ->
            assertEquals(id, PackReject.INVALID_ID, rejected(manifest(id = id)))
        }
    }

    @Test
    fun manifestAndApiMustBeExactlyIntegerTwo() {
        assertEquals(
            PackReject.INCOMPATIBLE_NEW,
            rejected(manifest().replace("\"manifest\": 2", "\"manifest\": 3")),
        )
        assertEquals(
            PackReject.INCOMPATIBLE_OLD,
            rejected(manifest().replace("\"manifest\": 2", "\"manifest\": 1")),
        )
        assertEquals(
            PackReject.INCOMPATIBLE_NEW,
            rejected(manifest().replace("\"api\": 2", "\"api\": 3")),
        )
        assertEquals(
            PackReject.INCOMPATIBLE_OLD,
            rejected(manifest().replace("\"api\": 2", "\"api\": 1")),
        )
        assertEquals(
            PackReject.INCOMPLETE,
            rejected(manifest().replace("\"api\": 2", "\"api\": \"2\"")),
        )
    }

    @Test
    fun missingNameAboutOrVocabIsRejected() {
        assertEquals(PackReject.INCOMPLETE, rejected(manifest().replace("\"name\": {\"zh\": \"格式测试\"},", "")))
        assertEquals(PackReject.INCOMPLETE, rejected(manifest().replace("\"about\": {\"zh\": \"只用于导入测试的占位角色。\"},", "")))
        assertEquals(
            PackReject.INCOMPLETE,
            rejected(manifest().replace(Regex(",\\s*\"vocab\": \\[\\]"), "")),
        )
        assertEquals(PackReject.INVALID_VOCAB, rejected(manifest().replace("\"vocab\": []", "\"vocab\": {}")))
    }

    @Test
    fun badJsonAndUnknownFieldsAreHandled() {
        assertEquals(PackReject.BAD_JSON, rejected("{not json"))
        val parsed = accepted(manifest(extra = ",\n\"unknown\": {\"x\": 1}"))
        assertEquals("format-fixture", parsed.id)
    }

    @Test
    fun unknownVocabKindIsSkippedAndKnownWordsSurvive() {
        val vocab = """
            [
              {"id":"wave","kind":"motion","names":{"zh":["挥手"]},"about":{"zh":"挥手"},"seconds":1.5},
              {"id":"dance-extra","kind":"dance-extra","names":{"zh":["乱舞"]},"about":{"zh":"未知"},"seconds":1.0}
            ]
        """.trimIndent()
        val parsed = accepted(manifest(vocab = vocab))
        assertEquals(1, parsed.vocab.size)
        assertEquals("wave", parsed.vocab.first().id)
        assertTrue(parsed.skipped.any { it.contains("dance-extra") })
    }

    @Test
    fun invalidVocabItemRejectsThePack() {
        val vocab = """[{"id":"bad","kind":"motion","names":{"zh":["坏"]},"about":{"zh":"坏"}}]"""
        assertEquals(PackReject.INVALID_VOCAB, rejected(manifest(vocab = vocab)))
    }

    @Test
    fun optionalAxesAndPresetsAreParsedForThePicker() {
        val extra = """,
            "axes": [{"id":"outfit","name":{"zh":"服装"},"options":[
              {"id":"maid","name":{"zh":"女仆"}},
              {"id":"casual","name":{"zh":"便服"}}
            ]}],
            "presets": [{"id":"maid-set","name":{"zh":"女仆装"},"pick":{"outfit":"maid"}}]
        """.trimIndent()
        val parsed = accepted(manifest(extra = extra))
        assertEquals(1, parsed.axes.size)
        assertEquals(2, parsed.axes.first().options.size)
        assertEquals("maid-set", parsed.defaultScheme())
        assertEquals("maid", parsed.picksFor("maid-set")["outfit"])
        assertEquals("casual", parsed.picksFor("casual")["outfit"])
    }

    @Test
    fun presetThatMissesAnAxisIsRejected() {
        val extra = """,
            "axes": [{"id":"outfit","name":{"zh":"服装"},"options":[{"id":"maid","name":{"zh":"女仆"}}]}],
            "presets": [{"id":"broken","pick":{}}]
        """.trimIndent()
        assertEquals(PackReject.INVALID_PRESETS, rejected(manifest(extra = extra)))
    }

    @Test
    fun defaultPresetAndCustomOutfitKeepTheirOwnSelections() {
        val parsed = accepted(manifest(extra = """,
            "axes": [
              {"id":"color","name":{"zh":"配色"},"options":[
                {"id":"blue","name":{"zh":"蓝"}},{"id":"red","name":{"zh":"红"}}]},
              {"id":"outfit","name":{"zh":"服装"},"options":[
                {"id":"base","name":{"zh":"原装"}},{"id":"winter","name":{"zh":"冬装"}}]}
            ],
            "presets": [{"id":"red-winter","pick":{"color":"red","outfit":"winter"}}]
        """.trimIndent()))
        assertEquals("red-winter", parsed.schemeFor(null))
        assertEquals("red-winter", parsed.schemeFor("removed-preset"))
        assertEquals("blue-winter", parsed.schemeFor("blue-winter"))
        assertEquals(mapOf("color" to "blue", "outfit" to "winter"), parsed.picksFor("blue-winter"))
    }

    @Test
    fun malformedOptionalSoundOrMovesRejectsAndMissingSoundFileSkips() {
        val badSounds = ""","sounds": []""".trimIndent()
        assertEquals(PackReject.INVALID_SOUNDS, rejected(manifest(extra = badSounds)))
        val missingFile = ""","sounds": {"poke": {"file": "sound/missing.ogg", "kind": "touch", "volume": 0.5}}""".trimIndent()
        val result = PackFormat.validate(
            manifest(extra = missingFile),
            files = mapOf("figure.js" to 0L),
        )
        assertTrue(result is PackValidation.Accepted)
        assertTrue((result as PackValidation.Accepted).manifest.skipped.any { it.contains("missing") })
    }

    @Test
    fun theShippedBuiltInManifestStillValidates() {
        val raw = File("src/main/assets/bluefin/figure.json").readText()
        val result = PackFormat.validate(
            raw,
            builtInIds = emptySet(),
            allowBuiltInId = true,
        )
        assertTrue("built-in manifest must validate: $result", result is PackValidation.Accepted)
        val manifest = (result as PackValidation.Accepted).manifest
        assertEquals(PackFormat.BUILT_IN_ID, manifest.id)
        assertTrue(manifest.vocab.isNotEmpty())
        assertTrue(manifest.canWalk)
        val appearance = PackAppearance(PackFormat.BUILT_IN_ID, manifest, null)
        assertEquals("蓝鳍", appearance.displayName)
        assertTrue(appearance.builtIn)
        assertTrue(appearance.canWalk)
    }

    @Test
    fun pathRulesRejectTraversalAndAbsoluteEntries() {
        assertEquals(null, PackPaths.normalizeArchivePath("../evil/figure.json"))
        assertEquals(null, PackPaths.normalizeArchivePath("/tmp/figure.json"))
        assertEquals(null, PackPaths.normalizeArchivePath("C:/figure.json"))
        assertEquals(null, PackPaths.normalizeArchivePath("a/../../b"))
        assertEquals("a/b/figure.json", PackPaths.normalizeArchivePath("./a/b/figure.json"))
        assertFalse(PackPaths.inside("/absolute/figure.js"))
        assertFalse(PackPaths.inside("http://example.com/figure.js"))
        assertTrue(PackPaths.inside("assets/figure.js"))
    }

    @Test
    fun manifestPathsCannotEscapeThePack() {
        assertEquals(
            PackReject.INCOMPLETE,
            rejected(manifest().replace("\"entry\": \"figure.js\"", "\"entry\": \"../evil.js\"")),
        )
        assertEquals(
            PackReject.INCOMPLETE,
            rejected(manifest(extra = ",\n\"model\": \"/tmp/model.json\"")),
        )
        assertEquals(
            PackReject.INCOMPLETE,
            rejected(manifest(extra = ",\n\"thumb\": \"https://example.com/a.png\"")),
        )
        assertEquals(
            PackReject.INCOMPLETE,
            rejected(manifest().replace("\"export\": \"createFixture\"", "\"export\": \"\"")),
        )
    }

    @Test
    fun missingEntryFileStillAcceptsTheManifest() {
        val result = PackFormat.validate(manifest(), files = mapOf("figure.json" to 100L))
        assertTrue(result is PackValidation.Accepted)
        assertEquals("figure.js", (result as PackValidation.Accepted).manifest.entry)
    }

    @Test
    fun unsupportedSoundTypeAndUnknownKindAreSkippedNotRejected() {
        val extra = """,
            "sounds": {
              "poke": {"file": "sound/poke.txt", "kind": "touch"},
              "sleep": {"file": "sound/sleep.ogg", "kind": "whatever"},
              "ok": {"file": "sound/ok.ogg", "kind": "touch"}
            }
        """.trimIndent()
        val result = PackFormat.validate(
            manifest(extra = extra),
            files = mapOf("figure.json" to 1L, "sound/ok.ogg" to 10L),
        )
        assertTrue(result is PackValidation.Accepted)
        val parsed = (result as PackValidation.Accepted).manifest
        assertTrue(parsed.skipped.any { it.contains("poke:type") })
        assertTrue(parsed.skipped.any { it.contains("sleep:kind") })
    }

    @Test
    fun badSoundVolumeStillRejectsTheManifest() {
        val extra = ""","sounds": {"poke": {"file": "sound/poke.ogg", "kind": "touch", "volume": 2}}""".trimIndent()
        assertEquals(PackReject.INVALID_SOUNDS, rejected(manifest(extra = extra)))
    }

    @Test
    fun nonObjectSoundsAreRejectedButNonArrayAxesAreIgnored() {
        assertEquals(PackReject.INVALID_SOUNDS, rejected(manifest(extra = ""","sounds": []""")))
        val parsed = accepted(manifest(extra = ""","axes": {},"presets": {}"""))
        assertTrue(parsed.axes.isEmpty())
        assertTrue(parsed.presets.isEmpty())
    }

    @Test
    fun versionThatIsNotAStringBecomesZero() {
        val parsed = accepted(manifest().replace("\"version\": \"1.0.0\"", "\"version\": 7"))
        assertEquals("0.0.0", parsed.version)
    }

    @Test
    fun badThumbIsSkippedButThePackStillImports() {
        val parsed = accepted(manifest(extra = ",\"thumb\": \"thumb.gif\""))
        assertEquals(null, parsed.thumb)
        assertTrue(parsed.skipped.any { it == "thumb" })
        val tooLarge = PackFormat.validate(
            manifest(extra = ",\"thumb\": \"thumb.png\""),
            files = mapOf("thumb.png" to 2L * 1024L * 1024L),
        )
        assertTrue(tooLarge is PackValidation.Accepted)
        assertEquals(null, (tooLarge as PackValidation.Accepted).manifest.thumb)
    }

    @Test
    fun canWalkFalseAndInvalidValuesFollowTheFormat() {
        val cannotWalk = accepted(manifest(extra = ""","can": {"walk": false}"""))
        assertFalse(cannotWalk.canWalk)
        val invalid = accepted(manifest(extra = ""","can": {"walk": "no"}"""))
        assertTrue(invalid.canWalk)
        assertTrue(invalid.skipped.any { it.contains("can.walk") })
    }
}
