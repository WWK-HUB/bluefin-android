package com.wwkhub.bluefin

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException

internal data class PackAppearance(
    val id: String,
    val manifest: PackManifest,
    val dir: File?,
) {
    val builtIn: Boolean get() = id == PackFormat.BUILT_IN_ID
    val displayName: String get() = if (builtIn) BUILT_IN_DISPLAY_NAME else manifest.displayName()
    val canWalk: Boolean get() = if (builtIn) true else manifest.canWalk

    companion object {
        const val BUILT_IN_DISPLAY_NAME = "蓝鳍"
    }
}

internal data class CurrentAppearance(
    val appearance: PackAppearance,
    val fellBack: Boolean,
)

/**
 * Installed character packs live in app-private storage under
 * `<filesDir>/character-packs/<id>/`. There is exactly one current appearance;
 * model, history and persona stay global and are never touched here.
 */
internal object PackRepository {
    const val MAX_IMPORTED = PackImportEngine.MAX_IMPORTED_PACKS
    private const val DIR = "character-packs"
    private const val TEMP_PREFIX = ".tmp-"
    private const val IMPORT_PREFIX = "pack-import-"

    @Volatile
    private var builtInCache: PackManifest? = null

    fun packRoot(context: Context): File = File(context.applicationContext.filesDir, DIR)

    fun builtInManifest(context: Context): PackManifest {
        builtInCache?.let { return it }
        val raw = context.applicationContext.assets.open("bluefin/figure.json")
            .bufferedReader()
            .use { it.readText() }
        val parsed = when (val result = PackFormat.validate(raw, builtInIds = emptySet(), allowBuiltInId = true)) {
            is PackValidation.Accepted -> result.manifest
            is PackValidation.Rejected -> throw IllegalStateException("built-in pack invalid: ${result.reason}")
        }
        builtInCache = parsed
        return parsed
    }

    fun builtIn(context: Context): PackAppearance = PackAppearance(
        id = PackFormat.BUILT_IN_ID,
        manifest = builtInManifest(context),
        dir = null,
    )

    fun listImported(context: Context): List<PackAppearance> {
        val root = packRoot(context)
        val dirs = root.listFiles()?.filter {
            it.isDirectory && !it.name.startsWith(".") && it.name != "staging"
        }.orEmpty()
        return dirs.mapNotNull { dir ->
            val manifest = readManifest(dir) ?: return@mapNotNull null
            PackAppearance(id = manifest.id, manifest = manifest, dir = dir)
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
    }

    fun findImported(context: Context, id: String): PackAppearance? {
        if (!PackPaths.validId(id) || id == PackFormat.BUILT_IN_ID) return null
        val dir = File(packRoot(context), id)
        if (!dir.isDirectory) return null
        val manifest = readManifest(dir) ?: return null
        if (manifest.id != id) return null
        return PackAppearance(id = id, manifest = manifest, dir = dir)
    }

    fun currentId(context: Context): String =
        prefs(context).getString(BluefinStore.KEY_CHARACTER_ID, PackFormat.BUILT_IN_ID)
            ?.takeIf { PackPaths.validId(it) }
            ?: PackFormat.BUILT_IN_ID

    fun setCurrent(context: Context, id: String) {
        val normalized = if (id == PackFormat.BUILT_IN_ID || findImported(context, id) != null) {
            id
        } else {
            PackFormat.BUILT_IN_ID
        }
        prefs(context).edit().putString(BluefinStore.KEY_CHARACTER_ID, normalized).apply()
    }

    fun resolveCurrent(context: Context): CurrentAppearance {
        val id = currentId(context)
        if (id == PackFormat.BUILT_IN_ID) return CurrentAppearance(builtIn(context), false)
        val imported = findImported(context, id)
        if (imported != null) return CurrentAppearance(imported, false)
        prefs(context).edit().putString(BluefinStore.KEY_CHARACTER_ID, PackFormat.BUILT_IN_ID).apply()
        return CurrentAppearance(builtIn(context), true)
    }

    fun current(context: Context): PackAppearance = resolveCurrent(context).appearance

    fun readScheme(context: Context, pack: PackManifest): String {
        val saved = prefs(context).getString(BluefinStore.KEY_CHARACTER_SCHEME_PREFIX + pack.id, null)
        return pack.schemeFor(saved)
    }

    fun writeScheme(context: Context, pack: PackManifest, scheme: String) {
        prefs(context).edit()
            .putString(BluefinStore.KEY_CHARACTER_SCHEME_PREFIX + pack.id, pack.schemeFor(scheme))
            .apply()
    }

    fun delete(context: Context, id: String): Boolean {
        if (id == PackFormat.BUILT_IN_ID || !PackPaths.validId(id)) return false
        val dir = File(packRoot(context), id)
        if (!dir.isDirectory) return false
        val deleted = dir.deleteRecursively()
        if (deleted && currentId(context) == id) {
            prefs(context).edit().putString(BluefinStore.KEY_CHARACTER_ID, PackFormat.BUILT_IN_ID).apply()
        }
        return deleted
    }

    fun importZip(context: Context, uri: Uri): PackAnalysis {
        val work = newWorkDir(context)
        return try {
            val zip = File(work, "source.zip")
            copyUri(context, uri, zip, PackImportEngine.HARD_BYTES)
            PackImportEngine.analyzeZip(zip, work)
        } catch (error: PackImportException) {
            work.deleteRecursively()
            throw error
        } catch (error: Exception) {
            work.deleteRecursively()
            throw PackImportException(PackImportFailure.BAD_ZIP, error.message.orEmpty(), error)
        }
    }

    fun importTree(context: Context, uri: Uri): PackAnalysis {
        val work = newWorkDir(context)
        return try {
            val source = File(work, "source")
            source.mkdirs()
            copyDocumentTree(context, uri, source)
            PackImportEngine.analyzeFolder(source, work)
        } catch (error: PackImportException) {
            work.deleteRecursively()
            throw error
        } catch (error: Exception) {
            work.deleteRecursively()
            throw PackImportException(PackImportFailure.BAD_ZIP, error.message.orEmpty(), error)
        }
    }

    fun install(context: Context, candidate: PackCandidate, replace: Boolean): PackAppearance {
        val root = packRoot(context)
        if (PackImportEngine.overCapacity(root, candidate.manifest.id)) {
            throw PackImportException(PackImportFailure.CAPACITY)
        }
        val dir = PackImportEngine.install(candidate, root, replace)
        val manifest = readManifest(dir)
        if (manifest == null) {
            dir.deleteRecursively()
            throw PackImportException(PackImportFailure.IO, "manifest")
        }
        return PackAppearance(id = manifest.id, manifest = manifest, dir = dir)
    }

    private fun readManifest(dir: File): PackManifest? {
        val file = File(dir, PackImportEngine.MANIFEST_FILE)
        if (!file.isFile) return null
        return try {
            when (val result = PackFormat.validate(file.readText(), builtInIds = setOf(PackFormat.BUILT_IN_ID))) {
                is PackValidation.Accepted -> result.manifest
                is PackValidation.Rejected -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun newWorkDir(context: Context): File {
        val root = File(context.applicationContext.cacheDir, "$IMPORT_PREFIX${System.currentTimeMillis()}")
        root.mkdirs()
        return root
    }

    private fun copyUri(context: Context, uri: Uri, target: File, limit: Long) {
        val input = context.applicationContext.contentResolver.openInputStream(uri)
            ?: throw PackImportException(PackImportFailure.IO, "open")
        input.use { source ->
            target.outputStream().use { sink ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > limit) throw PackImportException(PackImportFailure.TOO_LARGE, total.toString())
                    sink.write(buffer, 0, read)
                }
            }
        }
    }

    private fun copyDocumentTree(context: Context, uri: Uri, target: File) {
        val rootId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (error: Exception) {
            throw PackImportException(PackImportFailure.BAD_ZIP, "tree", error)
        }
        val total = longArrayOf(0L)
        copyDocumentDir(context, uri, rootId, target, total)
    }

    private fun copyDocumentDir(
        context: Context,
        treeUri: Uri,
        documentId: String,
        target: File,
        total: LongArray,
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        context.applicationContext.contentResolver.query(childrenUri, projection, null, null, null)
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    val childId = cursor.getString(0)
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2).orEmpty()
                    val normalized = PackPaths.normalizeArchivePath(name)
                        ?: throw PackImportException(PackImportFailure.PATH_UNSAFE, name)
                    val child = safeChild(target, normalized)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        child.mkdirs()
                        copyDocumentDir(context, treeUri, childId, child, total)
                    } else {
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                        val input = context.applicationContext.contentResolver.openInputStream(childUri)
                            ?: throw PackImportException(PackImportFailure.IO, name)
                        input.use { source ->
                            child.parentFile?.mkdirs()
                            child.outputStream().use { sink ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val read = source.read(buffer)
                                    if (read < 0) break
                                    total[0] += read
                                    if (total[0] > PackImportEngine.HARD_BYTES) {
                                        throw PackImportException(
                                            PackImportFailure.TOO_LARGE,
                                            total[0].toString(),
                                        )
                                    }
                                    sink.write(buffer, 0, read)
                                }
                            }
                        }
                    }
                }
            } ?: throw PackImportException(PackImportFailure.IO, "query")
    }

    private fun safeChild(root: File, relative: String): File {
        val canonicalRoot = root.canonicalFile
        val child = File(root, relative).canonicalFile
        if (child != canonicalRoot && !child.path.startsWith(canonicalRoot.path + File.separator)) {
            throw PackImportException(PackImportFailure.PATH_UNSAFE, relative)
        }
        return child
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(BluefinStore.PREFS, Context.MODE_PRIVATE)
}
