package com.wwkhub.bluefin

import android.content.Context
import android.net.Uri
import com.wwkhub.bluefin.voice.audio.AudioTranscoder
import com.wwkhub.bluefin.voice.audio.VoicePackDraft
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Installed 朗读 voice packs live in app-private storage under
 * `<filesDir>/voice-packs/<id>/`; the two bundled timbres ship inside
 * `assets/voice-packs/` and are never copied out.
 *
 * Import is intentionally smaller than the character-pack importer: a voice
 * pack is a flat `voice.json` + `reference.wav`, so there is no folder walk, no
 * nested archive and a much tighter byte ceiling.
 */
internal object VoicePackRepository {
    private const val DIR = "voice-packs"
    private const val ASSET_ROOT = "voice-packs"
    private const val TEMP_PREFIX = ".tmp-"
    private const val WORK_PREFIX = "voice-import-"

    /** A reference clip plus manifest is a few hundred KB; 16 MiB is generous. */
    const val MAX_BYTES = 16L * 1024 * 1024
    const val MAX_IMPORTED = 20

    private const val ZIP_MAX_ENTRIES = 64

    fun packRoot(context: Context): File = File(context.applicationContext.filesDir, DIR)

    fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(BluefinStore.PREFS, Context.MODE_PRIVATE)

    fun currentId(context: Context): String {
        val stored = BluefinStore.readVoicePackId(prefs(context))
        val available = list(context).map { it.id }
        if (stored != null && stored in available) return stored
        return VoicePackFormat.BUILT_IN_IDS.firstOrNull { it in available }
            ?: available.firstOrNull()
            ?: VoicePackFormat.BUILT_IN_IDS.first()
    }

    fun setCurrent(context: Context, id: String) {
        BluefinStore.writeVoicePackId(prefs(context), id)
    }

    /** Bundled first (in declaration order), then imported sorted by id. */
    fun list(context: Context): List<VoicePack> {
        val builtIn = listBuiltIn(context)
        val builtInIds = builtIn.map { it.id }.toSet()
        val imported = listImported(context).filter { it.id !in builtInIds }
        return builtIn + imported
    }

    fun find(context: Context, id: String): VoicePack? = list(context).firstOrNull { it.id == id }

    fun listBuiltIn(context: Context): List<VoicePack> {
        val assets = context.applicationContext.assets
        val present = assets.list(ASSET_ROOT).orEmpty().toSet()
        return VoicePackFormat.BUILT_IN_IDS.mapNotNull { id ->
            if (id !in present) return@mapNotNull null
            runCatching {
                val raw = assets.open("$ASSET_ROOT/$id/${VoicePackFormat.MANIFEST_FILE}")
                    .bufferedReader()
                    .use { it.readText() }
                VoicePackFormat.parse(raw).takeIf { it.id == id }
            }.getOrNull()
        }
    }

    fun listImported(context: Context): List<VoicePack> {
        val root = packRoot(context)
        if (!root.isDirectory) return emptyList()
        return root.listFiles().orEmpty()
            .filter {
                it.isDirectory && PackPaths.validId(it.name) &&
                    !com.wwkhub.bluefin.voice.tts.CloudVoiceDeletion.isPending(context, it.name)
            }
            .sortedBy { it.name }
            .mapNotNull { readPackDir(it) }
    }

    fun readPackDir(dir: File): VoicePack? = runCatching {
        val raw = File(dir, VoicePackFormat.MANIFEST_FILE).takeIf { it.isFile }?.readText()
            ?: return@runCatching null
        VoicePackFormat.parse(raw).takeIf { it.id == dir.name }
    }.getOrNull()

    fun referenceFile(context: Context, pack: VoicePack): File? {
        if (pack.builtIn) return null
        val file = File(File(packRoot(context), pack.id), pack.reference)
        return file.takeIf { it.isFile }
    }

    fun referenceBytes(context: Context, pack: VoicePack): ByteArray? {
        if (pack.builtIn) {
            return runCatching {
                context.applicationContext.assets
                    .open("$ASSET_ROOT/${pack.id}/${pack.reference}")
                    .use { it.readBytes() }
            }.getOrNull()
        }
        return runCatching { referenceFile(context, pack)?.readBytes() }.getOrNull()
    }

    /** Bundled packs cannot be deleted; the two timbres are part of the APK. */
    fun delete(context: Context, id: String): Boolean {
        if (VoicePackFormat.isBuiltInId(id)) return false
        val dir = File(packRoot(context), id)
        if (!dir.isDirectory) return false
        val wasCurrent = BluefinStore.readVoicePackId(prefs(context)) == id
        val removed = dir.deleteRecursively()
        if (removed && wasCurrent) {
            BluefinStore.writeVoicePackId(prefs(context), null)
        }
        return removed
    }

    fun revision(context: Context, id: String): Long =
        prefs(context).getLong("voice_pack_revision_$id", 0L)

    /**
     * Validates a candidate zip without installing it. Returns the parsed pack;
     * throws [VoicePackException] when the archive or manifest is unusable.
     */
    fun analyzeZip(context: Context, uri: Uri): Pair<VoicePack, File> {
        val work = File(
            context.applicationContext.cacheDir,
            "$WORK_PREFIX${System.currentTimeMillis()}",
        )
        work.mkdirs()
        val zipFile = File(work, "source.zip")
        try {
            copyUri(context, uri, zipFile, MAX_BYTES)
            extract(zipFile, work)
            val manifest = File(work, VoicePackFormat.MANIFEST_FILE)
                .takeIf { it.isFile }
                ?: throw VoicePackException(VoicePackReject.INCOMPLETE, "voice.json missing")
            val pack = VoicePackFormat.parse(manifest.readText(), rejectBuiltInIds = true)
            val reference = File(work, pack.reference)
            if (!reference.isFile || reference.length() == 0L) {
                throw VoicePackException(VoicePackReject.INVALID_REFERENCE, pack.reference)
            }
            val duration = WavHeader.durationSeconds(reference)
            if (duration != null && duration > VoicePackFormat.MAX_REFERENCE_SECONDS) {
                throw VoicePackException(
                    VoicePackReject.INVALID_REFERENCE,
                    "reference is %.1f s".format(duration),
                )
            }
            return pack to work
        } catch (error: VoicePackException) {
            work.deleteRecursively()
            throw error
        } catch (error: Exception) {
            work.deleteRecursively()
            throw VoicePackException(VoicePackReject.BAD_JSON, error.message.orEmpty())
        }
    }

    /**
     * Installs a pack the user assembled inside the app (recorded or transcoded
     * reference clip) instead of importing a zip.
     *
     * The work directory is built here and then handed to [install], so the
     * installed shape, the revision bump and the pack-count ceiling stay in one
     * place; this method only supplies what [analyzeZip] would have extracted.
     */
    fun create(
        context: Context,
        id: String,
        name: String,
        about: String,
        referenceText: String,
        referenceWav: File,
        sampleRate: Int = AudioTranscoder.TARGET_SAMPLE_RATE,
    ): VoicePack {
        if (!referenceWav.isFile || referenceWav.length() == 0L) {
            throw VoicePackException(VoicePackReject.INVALID_REFERENCE, "missing clip")
        }
        val duration = WavHeader.durationSeconds(referenceWav)
        if (duration != null && duration > VoicePackFormat.MAX_REFERENCE_SECONDS) {
            throw VoicePackException(
                VoicePackReject.INVALID_REFERENCE,
                "reference is %.1f s".format(duration),
            )
        }
        val work = File(
            context.applicationContext.cacheDir,
            "$WORK_PREFIX${System.currentTimeMillis()}",
        )
        work.mkdirs()
        try {
            File(work, VoicePackFormat.MANIFEST_FILE).writeText(
                VoicePackDraft.manifest(
                    id = id,
                    name = name,
                    about = about,
                    referenceText = referenceText,
                    sampleRate = sampleRate,
                ),
            )
            referenceWav.copyTo(
                File(work, VoicePackFormat.REFERENCE_FILE),
                overwrite = true,
            )
            return install(context, work)
        } catch (error: Exception) {
            work.deleteRecursively()
            if (error is VoicePackException) throw error
            throw VoicePackException(VoicePackReject.BAD_JSON, error.message.orEmpty())
        }
    }

    /**
     * Installs a previously analysed pack. [work] is the directory returned by
     * [analyzeZip]; it is copied into place and then removed.
     */
    @Synchronized
    fun install(context: Context, work: File): VoicePack {
        val manifest = File(work, VoicePackFormat.MANIFEST_FILE)
            .takeIf { it.isFile }
            ?: throw VoicePackException(VoicePackReject.INCOMPLETE, "voice.json missing")
        val pack = VoicePackFormat.parse(manifest.readText(), rejectBuiltInIds = true)
        if (VoicePackFormat.isBuiltInId(pack.id)) {
            throw VoicePackException(VoicePackReject.BUILT_IN_ID, pack.id)
        }
        if (com.wwkhub.bluefin.voice.tts.CloudVoiceDeletion.isPending(context, pack.id)) {
            work.deleteRecursively()
            throw VoicePackException(VoicePackReject.DELETION_PENDING, pack.id)
        }
        val root = packRoot(context)
        root.mkdirs()
        val installed = root.listFiles().orEmpty().count { it.isDirectory && it.name != pack.id }
        if (installed >= MAX_IMPORTED) {
            throw VoicePackException(VoicePackReject.INCOMPLETE, "too many packs")
        }
        val staging = File(root, "$TEMP_PREFIX${pack.id}-${System.nanoTime()}")
        try {
            staging.mkdirs()
            File(work, VoicePackFormat.MANIFEST_FILE).copyTo(
                File(staging, VoicePackFormat.MANIFEST_FILE),
                overwrite = true,
            )
            File(work, pack.reference).copyTo(
                File(staging, pack.reference),
                overwrite = true,
            )
            val target = File(root, pack.id)
            if (target.exists()) target.deleteRecursively()
            if (!staging.renameTo(target)) throw IOException("rename failed")
            prefs(context).edit()
                .putLong("voice_pack_revision_${pack.id}", revision(context, pack.id) + 1)
                .apply()
        } finally {
            staging.deleteRecursively()
            work.deleteRecursively()
        }
        return pack
    }

    fun deleteAll(context: Context) {
        packRoot(context).deleteRecursively()
        BluefinStore.writeVoicePackId(prefs(context), null)
    }

    private fun extract(zipFile: File, target: File) {
        ZipFile(zipFile).use { zip ->
            var count = 0
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                if (++count > ZIP_MAX_ENTRIES) {
                    throw VoicePackException(VoicePackReject.BAD_JSON, "too many entries")
                }
                val path = PackPaths.normalizeArchivePath(entry.name)
                    ?: throw VoicePackException(VoicePackReject.BAD_JSON, "unsafe path")
                // Only the two files matter; nested paths are flattened to their
                // file name so a zip built on any OS still imports.
                val name = path.substringAfterLast('/')
                if (name != VoicePackFormat.MANIFEST_FILE && name != VoicePackFormat.REFERENCE_FILE) {
                    continue
                }
                val out = File(target, name)
                zip.getInputStream(entry).use { input ->
                    out.outputStream().use { sink -> input.copyTo(sink, 64 * 1024) }
                }
                if (out.length() > MAX_BYTES) {
                    throw VoicePackException(VoicePackReject.BAD_JSON, "entry too large")
                }
            }
        }
    }

    private fun copyUri(context: Context, uri: Uri, target: File, limit: Long) {
        val input = context.applicationContext.contentResolver.openInputStream(uri)
            ?: throw VoicePackException(VoicePackReject.BAD_JSON, "open")
        input.use { source ->
            target.outputStream().use { sink ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > limit) {
                        throw VoicePackException(VoicePackReject.BAD_JSON, "too large")
                    }
                    sink.write(buffer, 0, read)
                }
            }
        }
    }
}

/** Minimal RIFF/WAVE header read; enough to reject an over-long reference. */
internal object WavHeader {
    fun durationSeconds(file: File): Double? = runCatching {
        file.inputStream().buffered().use { stream ->
            val header = ByteArray(12)
            if (stream.read(header) != 12) return@use null
            if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF") return@use null
            if (String(header, 8, 4, Charsets.US_ASCII) != "WAVE") return@use null
            var byteRate = 0
            val chunk = ByteArray(8)
            while (stream.read(chunk) == 8) {
                val id = String(chunk, 0, 4, Charsets.US_ASCII)
                val size = leInt(chunk, 4)
                if (size < 0) return@use null
                if (id == "fmt ") {
                    val fmt = ByteArray(minOf(size, 16))
                    if (stream.read(fmt) != fmt.size) return@use null
                    if (fmt.size < 12) return@use null
                    byteRate = leInt(fmt, 8)
                    if (size > fmt.size) stream.skip((size - fmt.size).toLong())
                } else if (id == "data") {
                    if (byteRate <= 0) return@use null
                    return@use size.toDouble() / byteRate
                } else {
                    stream.skip(size.toLong())
                }
            }
            null
        }
    }.getOrNull()

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
