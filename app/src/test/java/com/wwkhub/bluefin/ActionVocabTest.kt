package com.wwkhub.bluefin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Both action entry points (in-app sheet and orb panel) render these sections,
 * so the shipped packs must expose the full vocabulary with 「站立」 exactly once.
 */
class ActionVocabTest {
    private fun bundledManifest(path: String): PackManifest {
        val raw = File(path).readText()
        val result = PackFormat.validate(raw, builtInIds = emptySet(), allowBuiltInId = true)
        assertTrue("manifest must validate: $result", result is PackValidation.Accepted)
        return (result as PackValidation.Accepted).manifest
    }

    @Test
    fun builtInBluefinExposes21MotionsAnd19Expressions() {
        val sections = ActionVocab.of(bundledManifest("src/main/assets/bluefin/figure.json"))
        assertEquals(21, sections.motions.size)
        assertEquals(19, sections.expressions.size)
        assertEquals(1, sections.motions.count { it.first == "stand" })
        assertEquals("站立", sections.motions.first { it.first == "stand" }.second)
        listOf("jump", "bow", "stretch").forEach { word ->
            assertTrue("motion $word must be listed", sections.motions.any { it.first == word })
        }
        assertTrue("wink must be listed", sections.expressions.any { it.first == "wink" })
    }

    @Test
    fun winterMatteExposesTheSameSectionsFromItsOwnManifest() {
        val sections = ActionVocab.of(
            bundledManifest("src/main/assets/characters/bluefin-winter-matte/figure.json"),
        )
        assertEquals(21, sections.motions.size)
        assertEquals(19, sections.expressions.size)
        assertEquals(1, sections.motions.count { it.first == "stand" })
    }

    @Test
    fun importedPackOnlyListsItsOwnVocabulary() {
        val raw = """
            {
              "manifest": 2,
              "api": 2,
              "id": "vocab-fixture",
              "name": {"zh": "清单测试"},
              "about": {"zh": "只用于动作清单测试。"},
              "entry": "figure.js",
              "export": "createFixture",
              "vocab": [
                {"id": "stand", "kind": "motion", "names": {"zh": ["站立"]},
                 "about": {"zh": "站好"}, "seconds": 1.0},
                {"id": "hop", "kind": "motion", "names": {"zh": ["蹦跳"]},
                 "about": {"zh": "蹦一下"}, "seconds": 1.2},
                {"id": "happy", "kind": "expression", "names": {"zh": ["开心"]},
                 "about": {"zh": "开心表情"}, "seconds": 1.5}
              ]
            }
        """.trimIndent()
        val manifest = (PackFormat.validate(raw) as PackValidation.Accepted).manifest
        val sections = ActionVocab.of(manifest)
        assertEquals(listOf("stand" to "站立", "hop" to "蹦跳"), sections.motions)
        assertEquals(listOf("happy" to "开心"), sections.expressions)
    }

    @Test
    fun missingManifestOrEmptyVocabularyYieldsEmptySections() {
        assertTrue(ActionVocab.of(null).isEmpty)
        val raw = """
            {
              "manifest": 2,
              "api": 2,
              "id": "empty-fixture",
              "name": {"zh": "空清单"},
              "about": {"zh": "没有动作的角色。"},
              "entry": "figure.js",
              "export": "createFixture",
              "vocab": []
            }
        """.trimIndent()
        val manifest = (PackFormat.validate(raw) as PackValidation.Accepted).manifest
        assertTrue(ActionVocab.of(manifest).isEmpty)
    }

    @Test
    fun labelsFallBackAcrossLanguagesThenToTheId() {
        val raw = """
            {
              "manifest": 2,
              "api": 2,
              "id": "label-fixture",
              "name": {"zh": "标签测试"},
              "about": {"zh": "标签回退顺序。"},
              "entry": "figure.js",
              "export": "createFixture",
              "vocab": [
                {"id": "wave", "kind": "motion", "names": {"en": ["Wave"]},
                 "about": {"zh": "挥手"}, "seconds": 1.0}
              ]
            }
        """.trimIndent()
        val manifest = (PackFormat.validate(raw) as PackValidation.Accepted).manifest
        assertEquals(listOf("wave" to "Wave"), ActionVocab.of(manifest).motions)
    }
}