package com.wwkhub.bluefin

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

internal enum class PackImportFailure {
    TOO_LARGE,
    BAD_ZIP,
    PATH_UNSAFE,
    IO,
    REPLACE_REQUIRED,
    CAPACITY,
}

internal class PackImportException(
    val failure: PackImportFailure,
    val detail: String = "",
    cause: Throwable? = null,
) : Exception(detail.ifBlank { failure.name }, cause)

internal data class PackProblem(
    val source: String,
    val rejection: PackReject?,
    val detail: String,
)

internal data class PackCandidate(
    val manifest: PackManifest,
    val sourceDir: File,
    val skipped: List<String>,
)

internal data class PackAnalysis(
    val candidates: List<PackCandidate>,
    val problems: List<PackProblem>,
    val warningLarge: Boolean,
    val workDir: File,
) {
    fun cleanup() {
        runCatching { workDir.deleteRecursively() }
    }
}

/**
 * File-system half of the importer. It is deliberately Android-free: the
 * repository copies a content URI into [workDir] first, then this class scans,
 * validates, extracts and installs.
 */
internal object PackImportEngine {
    const val ZIP_MAX_DEPTH = 3
    const val FOLDER_MAX_DEPTH = 4
    const val LARGE_BYTES = 32L * 1024L * 1024L
    const val HARD_BYTES = 128L * 1024L * 1024L
    const val MANIFEST_FILE = "figure.json"
    const val MAX_IMPORTED_PACKS = 20

    fun analyzeZip(zipFile: File, workDir: File): PackAnalysis {
        if (!zipFile.isFile) throw PackImportException(PackImportFailure.BAD_ZIP, zipFile.name)
        if (zipFile.length() > HARD_BYTES) {
            throw PackImportException(PackImportFailure.TOO_LARGE, zipFile.length().toString())
        }
        val staging = File(workDir, "staging")
        staging.mkdirs()
        val entries = LinkedHashMap<String, Pair<java.util.zip.ZipEntry, Long>>()
        var uncompressed = 0L
        try {
            ZipFile(zipFile).use { zip ->
                val iterator = zip.entries()
                while (iterator.hasMoreElements()) {
                    val entry = iterator.nextElement()
                    val normalized = PackPaths.normalizeArchivePath(entry.name)
                        ?: throw PackImportException(PackImportFailure.PATH_UNSAFE, entry.name)
                    if (entry.isDirectory) continue
                    uncompressed += entry.size.coerceAtLeast(0L)
                    if (uncompressed > HARD_BYTES) {
                        throw PackImportException(PackImportFailure.TOO_LARGE, uncompressed.toString())
                    }
                    entries[normalized] = entry to entry.size.coerceAtLeast(0L)
                }
                val roots = findRoots(entries.keys, ZIP_MAX_DEPTH)
                val analysis = scan(
                    roots = roots,
                    workDir = workDir,
                    warningLarge = zipFile.length() > LARGE_BYTES || uncompressed > LARGE_BYTES,
                    readText = { root ->
                        val path = if (root.isEmpty()) MANIFEST_FILE else "$root/$MANIFEST_FILE"
                        val entry = entries[path]?.first
                            ?: throw IOException("missing $path")
                        zip.getInputStream(entry).bufferedReader().use { it.readText() }
                    },
                    filesFor = { root ->
                        val prefix = if (root.isEmpty()) "" else "$root/"
                        val map = LinkedHashMap<String, Long>()
                        entries.forEach { (path, pair) ->
                            if (path.startsWith(prefix)) {
                                map[path.removePrefix(prefix)] = pair.second
                            }
                        }
                        map
                    },
                    materialize = { root, target ->
                        val prefix = if (root.isEmpty()) "" else "$root/"
                        var written = 0L
                        entries.forEach { (path, pair) ->
                            if (!path.startsWith(prefix)) return@forEach
                            val relative = path.removePrefix(prefix)
                            if (relative.isEmpty()) return@forEach
                            val destination = safeChild(target, relative)
                            destination.parentFile?.mkdirs()
                            zip.getInputStream(pair.first).use { input ->
                                destination.outputStream().use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        val read = input.read(buffer)
                                        if (read < 0) break
                                        written += read
                                        if (written > HARD_BYTES) {
                                            throw PackImportException(
                                                PackImportFailure.TOO_LARGE,
                                                written.toString(),
                                            )
                                        }
                                        output.write(buffer, 0, read)
                                    }
                                }
                            }
                        }
                    },
                )
                return analysis
            }
        } catch (error: PackImportException) {
            staging.deleteRecursively()
            throw error
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw PackImportException(PackImportFailure.BAD_ZIP, error.message.orEmpty(), error)
        }
    }

    fun analyzeFolder(source: File, workDir: File): PackAnalysis {
        if (!source.isDirectory) throw PackImportException(PackImportFailure.BAD_ZIP, source.name)
        val staging = File(workDir, "staging")
        staging.mkdirs()
        val files = LinkedHashMap<String, Long>()
        var total = 0L
        source.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            val relativeRaw = file.relativeTo(source).path.replace(File.separatorChar, '/')
            val relative = PackPaths.normalizeArchivePath(relativeRaw)
                ?: throw PackImportException(PackImportFailure.PATH_UNSAFE, relativeRaw)
            val size = file.length().coerceAtLeast(0L)
            total += size
            if (total > HARD_BYTES) {
                throw PackImportException(PackImportFailure.TOO_LARGE, total.toString())
            }
            files[relative] = size
        }
        val roots = findRoots(files.keys, FOLDER_MAX_DEPTH)
        return scan(
            roots = roots,
            workDir = workDir,
            warningLarge = total > LARGE_BYTES,
            readText = { root ->
                val path = if (root.isEmpty()) MANIFEST_FILE else "$root/$MANIFEST_FILE"
                File(source, path).readText()
            },
            filesFor = { root ->
                val prefix = if (root.isEmpty()) "" else "$root/"
                val map = LinkedHashMap<String, Long>()
                files.forEach { (path, size) ->
                    if (path.startsWith(prefix)) map[path.removePrefix(prefix)] = size
                }
                map
            },
            materialize = { root, target ->
                val origin = if (root.isEmpty()) source else File(source, root)
                copyTree(origin, target)
            },
        )
    }

    private fun scan(
        roots: List<String>,
        workDir: File,
        warningLarge: Boolean,
        readText: (String) -> String,
        filesFor: (String) -> Map<String, Long>,
        materialize: (String, File) -> Unit,
    ): PackAnalysis {
        val candidates = ArrayList<PackCandidate>()
        val problems = ArrayList<PackProblem>()
        val ids = HashSet<String>()
        roots.forEachIndexed { index, root ->
            val source = root.ifEmpty { "." }
            val raw = try {
                readText(root)
            } catch (error: Exception) {
                problems.add(PackProblem(source, null, "read:${error.message.orEmpty()}"))
                return@forEachIndexed
            }
            when (val validation = PackFormat.validate(raw, files = filesFor(root))) {
                is PackValidation.Rejected -> {
                    problems.add(PackProblem(source, validation.reason, validation.detail))
                }
                is PackValidation.Accepted -> {
                    val manifest = validation.manifest
                    if (!ids.add(manifest.id)) {
                        problems.add(PackProblem(source, PackReject.INVALID_ID, "duplicate-id:${manifest.id}"))
                        return@forEachIndexed
                    }
                    val target = File(workDir, "candidate-$index")
                    try {
                        target.mkdirs()
                        materialize(root, target)
                    } catch (error: PackImportException) {
                        target.deleteRecursively()
                        throw error
                    } catch (error: Exception) {
                        target.deleteRecursively()
                        problems.add(PackProblem(source, null, "extract:${error.message.orEmpty()}"))
                        return@forEachIndexed
                    }
                    candidates.add(PackCandidate(manifest, target, manifest.skipped))
                }
            }
        }
        return PackAnalysis(candidates, problems, warningLarge, workDir)
    }

    /** Finds the pack roots, keeping only the shallowest when packs are nested. */
    internal fun findRoots(paths: Collection<String>, maxDepth: Int): List<String> {
        val parents = LinkedHashSet<String>()
        paths.forEach { path ->
            if (path != MANIFEST_FILE && !path.endsWith("/$MANIFEST_FILE")) return@forEach
            val parent = path.removeSuffix(MANIFEST_FILE).removeSuffix("/")
            val depth = if (parent.isEmpty()) 0 else parent.count { it == '/' } + 1
            if (depth <= maxDepth) parents.add(parent)
        }
        val sorted = parents.sortedWith(compareBy<String> { if (it.isEmpty()) 0 else it.count { c -> c == '/' } + 1 }.thenBy { it })
        val kept = ArrayList<String>()
        for (candidate in sorted) {
            if (kept.any { keptRoot ->
                    candidate == keptRoot ||
                        (keptRoot.isEmpty() && candidate.isNotEmpty()) ||
                        candidate.startsWith("$keptRoot/")
                }
            ) continue
            kept.add(candidate)
        }
        return kept
    }

    fun install(candidate: PackCandidate, packRoot: File, replace: Boolean): File {
        val id = candidate.manifest.id
        if (!PackPaths.validId(id) || id == PackFormat.BUILT_IN_ID || id == PackFormat.RESERVED_ID) {
            throw PackImportException(PackImportFailure.IO, "bad id")
        }
        packRoot.mkdirs()
        val target = File(packRoot, id)
        if (target.exists() && !replace) {
            throw PackImportException(PackImportFailure.REPLACE_REQUIRED, id)
        }
        val temp = File(packRoot, ".tmp-$id-${System.nanoTime()}")
        try {
            copyTree(candidate.sourceDir, temp)
            if (target.exists() && !target.deleteRecursively()) {
                throw IOException("cannot remove old pack")
            }
            if (!temp.renameTo(target)) {
                copyTree(temp, target)
                temp.deleteRecursively()
            }
            return target
        } catch (error: Exception) {
            temp.deleteRecursively()
            throw PackImportException(PackImportFailure.IO, error.message.orEmpty(), error)
        }
    }

    fun countImported(packRoot: File): Int =
        packRoot.listFiles()?.count { it.isDirectory && !it.name.startsWith(".") } ?: 0

    /**
     * True when installing [id] would add a new pack above the cap. Replacing a
     * pack that already exists is always allowed because it does not grow the
     * list.
     */
    fun overCapacity(packRoot: File, id: String): Boolean =
        !File(packRoot, id).isDirectory && countImported(packRoot) >= MAX_IMPORTED_PACKS

    private fun safeChild(root: File, relative: String): File {
        val child = File(root, relative).canonicalFile
        val canonicalRoot = root.canonicalFile
        if (child != canonicalRoot && !child.path.startsWith(canonicalRoot.path + File.separator)) {
            throw PackImportException(PackImportFailure.PATH_UNSAFE, relative)
        }
        return child
    }

    private fun copyTree(source: File, target: File) {
        if (source.isFile) {
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
            return
        }
        target.mkdirs()
        source.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            val relative = file.relativeTo(source).path
            val destination = File(target, relative)
            destination.parentFile?.mkdirs()
            file.copyTo(destination, overwrite = true)
        }
    }
}
