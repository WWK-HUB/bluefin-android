package com.wwkhub.bluefin

/**
 * `voice.json` validation for the朗读 timbre packs.
 *
 * A voice pack is deliberately a different format from a character pack: the
 * character pack describes how the pet looks and moves (`figure.json`), while a
 * voice pack only carries a short reference clip and its transcript, which is
 * what ZipVoice clones from. Keeping them separate means importing a voice pack
 * can never touch the active character.
 *
 * Nothing here touches Android, so the whole matrix runs as a JVM unit test.
 */
internal enum class VoicePackReject {
    DELETION_PENDING,
    BAD_JSON,
    INCOMPLETE,
    INCOMPATIBLE,
    INVALID_ID,
    RESERVED_ID,
    BUILT_IN_ID,
    INVALID_REFERENCE,
    INVALID_TEXT,
    INVALID_TUNING,
}

internal class VoicePackException(
    val reason: VoicePackReject,
    val detail: String = "",
) : Exception(if (detail.isEmpty()) reason.name else "${reason.name}: $detail")

internal data class VoicePack(
    val id: String,
    val name: String,
    val about: String,
    val kind: String,
    val reference: String,
    val referenceText: String,
    val sampleRate: Int,
    val speed: Float,
    val numSteps: Int,
    val builtIn: Boolean,
    /**
     * Optional Fish Audio voice model id bound to this same character voice.
     * The local reference clip above stays the ZipVoice binding; one pack can
     * serve both engines without the user redesigning the voice.
     */
    val fishReferenceId: String? = null,
)

internal object VoicePackFormat {
    const val FORMAT_VERSION = 1
    const val MANIFEST_FILE = "voice.json"
    const val REFERENCE_FILE = "reference.wav"

    /** Legacy local-engine packs stay fully supported as the fallback binding. */
    const val KIND_ZIPVOICE = "zipvoice"

    /** Cloud-first packs; may still carry a local reference clip. */
    const val KIND_FISH = "fish"

    /** Fish Audio model ids are lowercase hex (Mongo ObjectId style). */
    val FISH_REFERENCE_ID = Regex("^[0-9a-f]{24,64}$")

    /** Bundled packs ship in `assets/voice-packs/` and can never be removed. */
    val BUILT_IN_IDS = listOf("timbre1", "timbre2")

    /**
     * Same reserved ids as the character packs: an imported voice pack must not
     * collide with the built-in ones or with the reserved `coo` id.
     */
    val RESERVED_IDS = setOf(PackFormat.RESERVED_ID)

    const val MIN_SAMPLE_RATE = 8_000
    const val MAX_SAMPLE_RATE = 48_000
    const val MIN_SPEED = 0.5f
    const val MAX_SPEED = 2.0f
    const val MIN_NUM_STEPS = 1
    const val MAX_NUM_STEPS = 16
    const val MAX_TEXT_CHARS = 200

    /** Reference clips longer than this make short replies fail to synthesise. */
    const val MAX_REFERENCE_SECONDS = 10.0

    private const val MAX_ABOUT_CHARS = 120
    private const val MAX_NAME_CHARS = 32

    /**
     * Parses and validates a `voice.json` body.
     *
     * @param rejectBuiltInIds true on the import path, where claiming a bundled
     *   id would shadow a timbre that ships with the app.
     */
    fun parse(body: String, rejectBuiltInIds: Boolean = false): VoicePack {
        val root = try {
            MiniJson.parse(body)
        } catch (error: JsonParseException) {
            throw VoicePackException(VoicePackReject.BAD_JSON, error.message.orEmpty())
        }
        if (root.asObject() == null) {
            throw VoicePackException(VoicePackReject.BAD_JSON, "root must be an object")
        }

        val format = root.field("format")?.intOrNull()
            ?: throw VoicePackException(VoicePackReject.INCOMPLETE, "format")
        if (format != FORMAT_VERSION) {
            throw VoicePackException(VoicePackReject.INCOMPATIBLE, "format=$format")
        }

        val id = root.field("id")?.asString()?.trim().orEmpty()
        if (!PackPaths.validId(id)) {
            throw VoicePackException(VoicePackReject.INVALID_ID, id)
        }
        if (id in RESERVED_IDS) {
            throw VoicePackException(VoicePackReject.RESERVED_ID, id)
        }
        if (rejectBuiltInIds && id in BUILT_IN_IDS) {
            throw VoicePackException(VoicePackReject.BUILT_IN_ID, id)
        }

        val name = root.field("name")?.asString()?.trim().orEmpty()
        if (name.isEmpty() || name.length > MAX_NAME_CHARS) {
            throw VoicePackException(VoicePackReject.INCOMPLETE, "name")
        }

        val kind = root.field("kind")?.asString()?.trim().orEmpty()
        if (kind != KIND_ZIPVOICE && kind != KIND_FISH) {
            throw VoicePackException(VoicePackReject.INCOMPATIBLE, "kind=$kind")
        }

        val fishReferenceId = root.field("fishReferenceId")?.asString()?.trim()?.lowercase()
        if (fishReferenceId != null && !FISH_REFERENCE_ID.matches(fishReferenceId)) {
            throw VoicePackException(VoicePackReject.INVALID_TUNING, "fishReferenceId")
        }

        val reference = root.field("reference")?.asString()?.trim().orEmpty()
        if (reference.isEmpty() || !PackPaths.inside(reference)) {
            throw VoicePackException(VoicePackReject.INVALID_REFERENCE, reference)
        }
        if (PackPaths.extension(reference) != "wav") {
            throw VoicePackException(VoicePackReject.INVALID_REFERENCE, reference)
        }

        val text = root.field("referenceText")?.asString()?.trim().orEmpty()
        if (text.isEmpty() || text.length > MAX_TEXT_CHARS) {
            throw VoicePackException(VoicePackReject.INVALID_TEXT, "length=${text.length}")
        }

        val sampleRate = root.field("sampleRate")?.intOrNull()
            ?: throw VoicePackException(VoicePackReject.INCOMPLETE, "sampleRate")
        if (sampleRate !in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) {
            throw VoicePackException(VoicePackReject.INVALID_TUNING, "sampleRate=$sampleRate")
        }

        val speed = (root.field("speed")?.asDouble() ?: 1.0).toFloat()
        if (!speed.isFinite() || speed < MIN_SPEED || speed > MAX_SPEED) {
            throw VoicePackException(VoicePackReject.INVALID_TUNING, "speed=$speed")
        }

        val steps = root.field("numSteps")?.intOrNull() ?: 4
        if (steps !in MIN_NUM_STEPS..MAX_NUM_STEPS) {
            throw VoicePackException(VoicePackReject.INVALID_TUNING, "numSteps=$steps")
        }

        val about = root.field("about")?.asString()?.trim().orEmpty().take(MAX_ABOUT_CHARS)

        return VoicePack(
            id = id,
            name = name,
            about = about,
            kind = kind,
            reference = reference,
            referenceText = text,
            sampleRate = sampleRate,
            speed = speed,
            numSteps = steps,
            builtIn = id in BUILT_IN_IDS,
            fishReferenceId = fishReferenceId,
        )
    }

    fun isBuiltInId(id: String?): Boolean = id != null && id in BUILT_IN_IDS
}
