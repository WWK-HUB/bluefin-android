package com.wwkhub.bluefin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PackImportEngineTest {
    private fun tempDir(): File = Files.createTempDirectory("bluefin-pack-test").toFile()

    private fun figure(id: String = "format-fixture", name: String = "格式测试"): String = """
        {
          "manifest": 2,
          "api": 2,
          "id": "$id",
          "name": {"zh": "$name"},
          "about": {"zh": "占位角色"},
          "entry": "figure.js",
          "export": "createFixture",
          "vocab": []
        }
    """.trimIndent()

    private fun zip(dir: File, entries: Map<String, String>): File {
        val file = File(dir, "pack.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, text) ->
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
        }
        return file
    }

    private fun analyze(entries: Map<String, String>): PackAnalysis {
        val root = tempDir()
        val zip = zip(root, entries)
        return PackImportEngine.analyzeZip(zip, File(root, "work"))
    }

    @Test
    fun zipRootAndGithubNestedPackAreAccepted() {
        val root = analyze(mapOf("figure.json" to figure(), "figure.js" to "export function createFixture(){}"))
        assertEquals(1, root.candidates.size)
        assertEquals("format-fixture", root.candidates.first().manifest.id)
        root.cleanup()

        val nested = analyze(
            mapOf(
                "some-pack-main/figure.json" to figure(),
                "some-pack-main/figure.js" to "export function createFixture(){}",
            ),
        )
        assertEquals(1, nested.candidates.size)
        nested.cleanup()
    }

    @Test
    fun zipDeeperThanThreeDirectoriesIsNotFound() {
        val analysis = analyze(mapOf("a/b/c/d/figure.json" to figure()))
        assertTrue(analysis.candidates.isEmpty())
        assertTrue(analysis.problems.isEmpty())
        analysis.cleanup()
    }

    @Test
    fun missingFigureIsNotABadZipButIsNotFound() {
        val analysis = analyze(mapOf("readme.txt" to "hello"))
        assertTrue(analysis.candidates.isEmpty())
        assertTrue(analysis.problems.isEmpty())
        analysis.cleanup()
    }

    @Test
    fun badZipAndTraversalFailTheWholeImport() {
        val root = tempDir()
        val bad = File(root, "bad.zip")
        bad.writeText("not a zip")
        try {
            PackImportEngine.analyzeZip(bad, File(root, "work"))
            assertTrue("bad zip should fail", false)
        } catch (error: PackImportException) {
            assertEquals(PackImportFailure.BAD_ZIP, error.failure)
        }

        val traversal = zip(root, mapOf("../evil/figure.json" to figure()))
        try {
            PackImportEngine.analyzeZip(traversal, File(root, "work2"))
            assertTrue("traversal should fail", false)
        } catch (error: PackImportException) {
            assertEquals(PackImportFailure.PATH_UNSAFE, error.failure)
        }
    }

    @Test
    fun oneBrokenDirectoryDoesNotBlockAValidPack() {
        val analysis = analyze(
            mapOf(
                "good/figure.json" to figure(id = "good-pack"),
                "good/figure.js" to "export function createFixture(){}",
                "broken/figure.json" to "{bad json",
            ),
        )
        assertEquals(1, analysis.candidates.size)
        assertEquals("good-pack", analysis.candidates.first().manifest.id)
        assertEquals(1, analysis.problems.size)
        assertEquals(PackReject.BAD_JSON, analysis.problems.first().rejection)
        analysis.cleanup()
    }

    @Test
    fun duplicateIdsKeepTheFirstAndProblemTheSecond() {
        val analysis = analyze(
            mapOf(
                "one/figure.json" to figure(id = "same-pack"),
                "two/figure.json" to figure(id = "same-pack"),
            ),
        )
        assertEquals(1, analysis.candidates.size)
        assertEquals(1, analysis.problems.size)
        assertEquals("same-pack", analysis.candidates.first().manifest.id)
        analysis.cleanup()
    }

    @Test
    fun folderImportAllowsOneExtraLayer() {
        val root = tempDir()
        val source = File(root, "chosen")
        File(source, "a/b/c/d").mkdirs()
        File(source, "a/b/c/d/figure.json").writeText(figure())
        val work = File(root, "work")
        work.mkdirs()
        val analysis = PackImportEngine.analyzeFolder(source, work)
        assertEquals(1, analysis.candidates.size)
        analysis.cleanup()

        val root5 = tempDir()
        val deep = File(root5, "chosen/a/b/c/d/e")
        deep.mkdirs()
        deep.resolve("figure.json").writeText(figure())
        val work5 = File(root5, "work")
        work5.mkdirs()
        val tooDeep = PackImportEngine.analyzeFolder(File(root5, "chosen"), work5)
        assertTrue(tooDeep.candidates.isEmpty())
        tooDeep.cleanup()
    }

    @Test
    fun installCopiesTheWholePackDirectoryAndReplacesOnlyWhenAsked() {
        val analysis = analyze(
            mapOf(
                "figure.json" to figure(id = "install-fixture"),
                "figure.js" to "export function createFixture(){}",
                "assets/thumb.png" to "png",
                "textures/a.txt" to "texture",
            ),
        )
        val candidate = analysis.candidates.first()
        val packRoot = File(tempDir(), "character-packs")
        val installed = PackImportEngine.install(candidate, packRoot, replace = false)
        assertTrue(File(installed, "figure.json").isFile)
        assertTrue(File(installed, "figure.js").isFile)
        assertTrue(File(installed, "assets/thumb.png").isFile)
        assertTrue(File(installed, "textures/a.txt").isFile)
        try {
            PackImportEngine.install(candidate, packRoot, replace = false)
            assertTrue("replace should require confirmation", false)
        } catch (error: PackImportException) {
            assertEquals(PackImportFailure.REPLACE_REQUIRED, error.failure)
        }
        assertNotNull(PackImportEngine.install(candidate, packRoot, replace = true))
        analysis.cleanup()
    }

    @Test
    fun rootsStopDescendingIntoAFoundPack() {
        val roots = PackImportEngine.findRoots(
            listOf("nested/figure.json", "nested/inside/figure.json", "other/figure.json"),
            PackImportEngine.ZIP_MAX_DEPTH,
        )
        assertEquals(listOf("nested", "other"), roots)
        assertEquals(
            listOf(""),
            PackImportEngine.findRoots(
                listOf("figure.json", "nested/figure.json", "other/figure.json"),
                PackImportEngine.ZIP_MAX_DEPTH,
            ),
        )
        assertEquals(128L * 1024L * 1024L, PackImportEngine.HARD_BYTES)
        assertEquals(32L * 1024L * 1024L, PackImportEngine.LARGE_BYTES)
        assertFalse(roots.contains("nested/inside"))
    }

    @Test
    fun missingEntryFileStillImportsThePack() {
        val analysis = analyze(mapOf("figure.json" to figure(id = "no-entry-file")))
        assertEquals(1, analysis.candidates.size)
        assertFalse(File(analysis.candidates.first().sourceDir, "figure.js").exists())
        analysis.cleanup()
    }

    @Test
    fun aFileAboveTheHardLimitIsRejectedByLength() {
        val root = tempDir()
        val large = File(root, "large.zip")
        RandomAccessFile(large, "rw").use { file ->
            file.setLength(PackImportEngine.HARD_BYTES + 1L)
        }
        try {
            PackImportEngine.analyzeZip(large, File(root, "work"))
            assertTrue("oversized zip should fail", false)
        } catch (error: PackImportException) {
            assertEquals(PackImportFailure.TOO_LARGE, error.failure)
        } finally {
            large.delete()
        }
    }

    @Test
    fun theImportedPackCapCountsOnlyVisibleDirectories() {
        val root = tempDir()
        repeat(PackImportEngine.MAX_IMPORTED_PACKS) { index ->
            File(root, "pack-$index").mkdirs()
        }
        File(root, ".tmp-hidden").mkdirs()
        File(root, "staging").mkdirs()
        assertEquals(PackImportEngine.MAX_IMPORTED_PACKS + 1, PackImportEngine.countImported(root))
        assertTrue(PackImportEngine.overCapacity(root, "new-pack"))
        assertFalse(PackImportEngine.overCapacity(root, "pack-0"))
        assertEquals(20, PackImportEngine.MAX_IMPORTED_PACKS)
        root.deleteRecursively()
    }

    @Test
    fun folderImportAcceptsDotPrefixedFileNames() {
        val root = tempDir()
        val source = File(root, "chosen")
        source.mkdirs()
        File(source, "figure.json").writeText(figure())
        File(source, "..hidden").writeText("bad")
        val work = File(root, "work")
        work.mkdirs()
        // A literal "..hidden" name is safe; a real traversal path cannot be
        // created inside the selected tree, so this verifies the folder half
        // still imports normal files with dot-prefixed names.
        val analysis = PackImportEngine.analyzeFolder(source, work)
        assertEquals(1, analysis.candidates.size)
        analysis.cleanup()
    }
}
