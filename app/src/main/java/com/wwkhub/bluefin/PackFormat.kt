package com.wwkhub.bluefin

/**
 * Coopanion 0.2.19 compatible `figure.json` validation.
 *
 * The rules mirror the pack-format document: the importer accepts every pack
 * Coopanion accepts, keeps optional problems in `skipped`, and only rejects a
 * candidate when its manifest itself is unusable. Nothing here touches Android
 * so the full matrix can run as a JVM unit test.
 */
internal object PackPaths {
    private val idPattern = Regex("^[a-z0-9][a-z0-9-]{0,31}$")
    private val optionPattern = Regex("^[a-z0-9]{1,24}$")
    private val drivePattern = Regex("^[a-z]:", RegexOption.IGNORE_CASE)
    private val schemePattern = Regex("^[a-z]+:", RegexOption.IGNORE_CASE)
    private const val FORBIDDEN = "<>:\"|?*"

    fun validId(value: String?): Boolean = value != null && idPattern.matches(value)

    fun validOptionId(value: String?): Boolean = value != null && optionPattern.matches(value)

    /**
     * Normalizes an archive/folder path. Returns null when the path is
     * absolute, escapes with `..`, or uses a forbidden character. Empty and
     * `.` segments are dropped, matching the reference importer's segments().
     */
    fun normalizeArchivePath(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        val path = raw.replace('\\', '/')
        if (path.startsWith("/")) return null
        if (drivePattern.containsMatchIn(path)) return null
        val segments = ArrayList<String>()
        for (segment in path.split('/')) {
            if (segment.isEmpty() || segment == ".") continue
            if (segment == "..") return null
            if (segment.any { it in FORBIDDEN || it.code < 0x20 }) return null
            segments.add(segment)
        }
        return if (segments.isEmpty()) null else segments.joinToString("/")
    }

    /** `inside()` for paths written in the manifest. */
    fun inside(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        if (raw.startsWith("/") || raw.startsWith("\\")) return false
        if (schemePattern.containsMatchIn(raw)) return false
        if (raw.any { it == '<' || it == '>' || it == '"' || it.code < 0x20 }) return false
        return raw.replace('\\', '/').split('/').none { it == ".." }
    }

    fun extension(path: String): String {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        return name.substringAfterLast('.', "").lowercase()
    }

    fun isSupportedSound(path: String): Boolean = extension(path) in setOf("ogg", "mp3", "wav")

    fun isSupportedThumb(path: String): Boolean = extension(path) in setOf("png", "jpg", "jpeg", "webp")
}

internal data class PackNames(val values: Map<String, String>) {
    fun preferred(): String =
        values["zh"] ?: values["en"] ?: values.entries.firstOrNull()?.value ?: ""

    fun forLanguage(language: String?): String {
        if (!language.isNullOrBlank()) values[language]?.let { return it }
        return preferred()
    }
}

internal data class PackVocabItem(
    val id: String,
    val kind: String,
    val names: Map<String, List<String>>,
    val about: PackNames,
    val seconds: Double,
    val lasting: Boolean,
) {
    fun label(language: String = "zh"): String =
        names[language]?.firstOrNull()
            ?: names["zh"]?.firstOrNull()
            ?: names["en"]?.firstOrNull()
            ?: names.values.firstOrNull()?.firstOrNull()
            ?: id
}

internal data class PackAxisOption(
    val id: String,
    val name: PackNames,
    val thumb: String?,
    val accent: String?,
)

internal data class PackAxis(
    val id: String,
    val name: PackNames,
    val options: List<PackAxisOption>,
) {
    fun option(id: String?): PackAxisOption? = options.firstOrNull { it.id == id }
}

internal data class PackPreset(
    val id: String,
    val name: PackNames?,
    val pick: Map<String, String>,
    val accent: String?,
)

internal data class PackBubbleColors(
    val lightFill: String,
    val lightText: String,
    val lightLine: String,
    val darkFill: String,
    val darkText: String,
    val darkLine: String,
)

internal data class PackManifest(
    val manifestVersion: Int,
    val api: Int,
    val id: String,
    val version: String,
    val name: PackNames,
    val about: PackNames,
    val entry: String,
    val export: String,
    val author: String?,
    val thumb: String?,
    val vocab: List<PackVocabItem>,
    val axes: List<PackAxis>,
    val presets: List<PackPreset>,
    val canWalk: Boolean,
    val bubble: PackBubbleColors?,
    val skipped: List<String>,
) {
    fun displayName(language: String = "zh"): String = name.forLanguage(language).ifBlank { id }

    fun picksFor(scheme: String?): Map<String, String> {
        val preset = presets.firstOrNull { it.id == scheme }
        val parts = scheme.orEmpty().split('-')
        return axes.mapIndexed { index, axis ->
            val wanted = preset?.pick?.get(axis.id)
                ?: parts.getOrNull(index)
            axis.id to (axis.option(wanted)?.id ?: axis.options.first().id)
        }.toMap()
    }

    fun schemeFor(saved: String?): String {
        if (presets.any { it.id == saved }) return saved.orEmpty()
        val picks = picksFor(saved)
        val matching = presets.firstOrNull { preset ->
            axes.all { axis -> preset.pick[axis.id] == picks[axis.id] }
        }
        return matching?.id ?: axes.map { picks[it.id] }.joinToString("-")
    }

    fun defaultScheme(): String =
        presets.firstOrNull()?.id ?: axes.map { it.options.first().id }.joinToString("-")

    fun hasWalkWord(): Boolean = vocab.any { it.id == "walk" }
}

internal enum class PackReject {
    BAD_JSON,
    INCOMPLETE,
    INCOMPATIBLE_NEW,
    INCOMPATIBLE_OLD,
    INVALID_ID,
    RESERVED_ID,
    BUILT_IN_ID,
    INVALID_AXES,
    INVALID_PRESETS,
    INVALID_VOCAB,
    INVALID_SOUNDS,
    INVALID_MOVES,
    INVALID_NEEDS,
}

internal sealed class PackValidation {
    data class Accepted(val manifest: PackManifest) : PackValidation()
    data class Rejected(val reason: PackReject, val detail: String = "") : PackValidation()
}

internal object PackFormat {
    const val BUILT_IN_ID = "bluefin-native"
    const val RESERVED_ID = "coo"
    const val INLINE_TAG_MAX = 32
    const val MOVES_MAX = 8
    const val MOVE_PARAMS_MAX = 2
    private const val THUMB_MAX_BYTES = 1L * 1024L * 1024L

    private val idPattern = Regex("^[a-z0-9][a-z0-9-]{0,31}$")
    private val optionPattern = Regex("^[a-z0-9]{1,24}$")
    private val nameBreak = Regex("[,，、\\s【】<>＜＞]")
    private val colorPattern = Regex("^#(?:[0-9a-f]{3,4}|[0-9a-f]{6}|[0-9a-f]{8})$", RegexOption.IGNORE_CASE)
    private val soundNamePattern = Regex("^[a-z][a-zA-Z0-9-]{0,31}$")
    private val paramIdPattern = Regex("^[a-z][a-zA-Z0-9]{0,23}$")
    private val numericWord = Regex("^-?\\d+(\\.\\d+)?$")
    private val moveReserved = setOf("left", "center", "right", "cursor")
    private val soundKinds = setOf("move", "touch", "face", "snore", "talk")
    private val repeatModes = setOf("overlap", "restart", "finish")

    /**
     * @param builtInIds ids that must not be overwritten by an imported pack.
     * @param files when non-null, relative file paths in the pack with byte sizes.
     *        Used only for optional thumb/sound checks; a missing entry is not a
     *        manifest rejection.
     */
    fun validate(
        raw: String,
        builtInIds: Set<String> = setOf(BUILT_IN_ID),
        files: Map<String, Long>? = null,
        allowBuiltInId: Boolean = false,
    ): PackValidation {
        val root = try {
            MiniJson.parse(raw)
        } catch (_: JsonParseException) {
            return PackValidation.Rejected(PackReject.BAD_JSON)
        }
        val obj = root.asObject() ?: return PackValidation.Rejected(PackReject.INCOMPLETE)

        val manifest = obj.field("manifest")?.intOrNull()
            ?: return PackValidation.Rejected(PackReject.INCOMPLETE, "manifest")
        if (manifest > 2) return PackValidation.Rejected(PackReject.INCOMPATIBLE_NEW, "manifest")
        if (manifest < 2) return PackValidation.Rejected(PackReject.INCOMPATIBLE_OLD, "manifest")
        val api = obj.field("api")?.intOrNull()
            ?: return PackValidation.Rejected(PackReject.INCOMPLETE, "api")
        if (api > 2) return PackValidation.Rejected(PackReject.INCOMPATIBLE_NEW, "api")
        if (api < 2) return PackValidation.Rejected(PackReject.INCOMPATIBLE_OLD, "api")

        val id = obj.field("id")?.asString()?.trim().orEmpty()
        if (!idPattern.matches(id)) return PackValidation.Rejected(PackReject.INVALID_ID, id)
        if (!allowBuiltInId && id == RESERVED_ID) {
            return PackValidation.Rejected(PackReject.RESERVED_ID, id)
        }
        if (!allowBuiltInId && id in builtInIds) {
            return PackValidation.Rejected(PackReject.BUILT_IN_ID, id)
        }

        val name = names(obj.field("name"))
            ?: return PackValidation.Rejected(PackReject.INCOMPLETE, "name")
        val about = names(obj.field("about"))
            ?: return PackValidation.Rejected(PackReject.INCOMPLETE, "about")
        val entry = obj.field("entry")?.asString()?.trim().orEmpty()
        if (!PackPaths.inside(entry)) return PackValidation.Rejected(PackReject.INCOMPLETE, "entry")
        val export = obj.field("export")?.asString()?.trim().orEmpty()
        if (export.isEmpty()) return PackValidation.Rejected(PackReject.INCOMPLETE, "export")
        obj.field("model")?.takeIf { it !is JsonValue.Null }?.let { model ->
            val path = model.asString()
            if (path == null || path.isBlank() || !PackPaths.inside(path)) {
                return PackValidation.Rejected(PackReject.INCOMPLETE, "model")
            }
        }
        val vocabValue = obj.field("vocab")
            ?: return PackValidation.Rejected(PackReject.INCOMPLETE, "vocab")
        val vocabArray = vocabValue.asArray()
            ?: return PackValidation.Rejected(PackReject.INVALID_VOCAB, "vocab")

        val skipped = ArrayList<String>()
        val vocabulary = ArrayList<PackVocabItem>()
        val seenIds = HashSet<String>()
        val seenNames = HashSet<String>()
        for ((index, itemValue) in vocabArray.items.withIndex()) {
            val item = itemValue.asObject()
                ?: return PackValidation.Rejected(PackReject.INVALID_VOCAB, "vocab[$index]")
            val wordId = item.field("id")?.asString()?.trim().orEmpty()
            if (!idPattern.matches(wordId)) {
                return PackValidation.Rejected(PackReject.INVALID_VOCAB, "vocab[$index].id")
            }
            val kind = item.field("kind")?.asString()?.trim().orEmpty()
            if (kind != "expression" && kind != "motion") {
                skipped.add("vocab:$wordId:kind=$kind")
                continue
            }
            val wordNames = nameLists(item.field("names"))
                ?: return PackValidation.Rejected(PackReject.INVALID_VOCAB, "vocab[$index].names")
            val wordAbout = names(item.field("about"))
                ?: return PackValidation.Rejected(PackReject.INVALID_VOCAB, "vocab[$index].about")
            val seconds = item.field("seconds")?.asDouble()
            if (seconds == null || !seconds.isFinite() || seconds <= 0.0) {
                return PackValidation.Rejected(PackReject.INVALID_VOCAB, "vocab[$index].seconds")
            }
            if (!seenIds.add(wordId)) {
                return PackValidation.Rejected(PackReject.INVALID_VOCAB, "duplicate id $wordId")
            }
            wordNames.values.flatten().forEach { word ->
                if (word.isBlank() || word.length > INLINE_TAG_MAX || nameBreak.containsMatchIn(word)) {
                    return PackValidation.Rejected(PackReject.INVALID_VOCAB, "vocab name $word")
                }
                if (!seenNames.add(word)) {
                    return PackValidation.Rejected(PackReject.INVALID_VOCAB, "duplicate name $word")
                }
            }
            vocabulary.add(
                PackVocabItem(
                    id = wordId,
                    kind = kind,
                    names = wordNames,
                    about = wordAbout,
                    seconds = seconds,
                    lasting = item.field("lasting")?.asBoolean() == true,
                ),
            )
        }

        val axes = parseAxes(obj.field("axes"))
            ?: return PackValidation.Rejected(PackReject.INVALID_AXES)
        val presets = parsePresets(obj.field("presets"), axes)
            ?: return PackValidation.Rejected(PackReject.INVALID_PRESETS)

        obj.field("needs")?.let { needs ->
            val array = needs.asArray()
                ?: return PackValidation.Rejected(PackReject.INVALID_NEEDS)
            array.items.forEach { need ->
                val value = need.asString().orEmpty()
                if (value != "windows") skipped.add("needs:$value")
            }
        }

        obj.field("sounds")?.let { sounds ->
            val soundObject = sounds.asObject()
                ?: return PackValidation.Rejected(PackReject.INVALID_SOUNDS)
            for ((key, soundValue) in soundObject.fields) {
                if (!soundNamePattern.matches(key)) {
                    return PackValidation.Rejected(PackReject.INVALID_SOUNDS, key)
                }
                val sound = soundValue.asObject()
                    ?: return PackValidation.Rejected(PackReject.INVALID_SOUNDS, key)
                val file = sound.field("file")?.asString()?.trim().orEmpty()
                if (!PackPaths.inside(file)) {
                    return PackValidation.Rejected(PackReject.INVALID_SOUNDS, "$key.file")
                }
                sound.field("volume")?.let { volume ->
                    val v = volume.asDouble()
                    if (v == null || !v.isFinite() || v < 0.0 || v > 1.0) {
                        return PackValidation.Rejected(PackReject.INVALID_SOUNDS, "$key.volume")
                    }
                }
                if (!PackPaths.isSupportedSound(file)) {
                    skipped.add("sound:$key:type")
                    continue
                }
                val kind = sound.field("kind")?.asString()?.trim().orEmpty()
                if (kind !in soundKinds) {
                    skipped.add("sound:$key:kind=$kind")
                    continue
                }
                if (files != null) {
                    val normalized = PackPaths.normalizeArchivePath(file)
                    if (normalized == null || !files.containsKey(normalized)) {
                        skipped.add("sound:$key:missing")
                        continue
                    }
                }
                val repeat = sound.field("repeat")?.asString()?.trim()
                if (repeat != null && repeat !in repeatModes) {
                    skipped.add("sound:$key:repeat=$repeat")
                }
            }
        }

        obj.field("moves")?.let { moves ->
            val array = moves.asArray()
                ?: return PackValidation.Rejected(PackReject.INVALID_MOVES)
            if (array.items.size > MOVES_MAX) {
                return PackValidation.Rejected(PackReject.INVALID_MOVES, "too many")
            }
            for ((index, moveValue) in array.items.withIndex()) {
                val move = moveValue.asObject()
                    ?: return PackValidation.Rejected(PackReject.INVALID_MOVES, "moves[$index]")
                val moveId = move.field("id")?.asString()?.trim().orEmpty()
                if (!idPattern.matches(moveId) || moveId in moveReserved || numericWord.matches(moveId)) {
                    return PackValidation.Rejected(PackReject.INVALID_MOVES, "moves[$index].id")
                }
                if (names(move.field("name")) == null || names(move.field("about")) == null) {
                    return PackValidation.Rejected(PackReject.INVALID_MOVES, "moves[$index].name")
                }
                move.field("params")?.let { params ->
                    val arrayParams = params.asArray()
                        ?: return PackValidation.Rejected(PackReject.INVALID_MOVES, "params")
                    if (arrayParams.items.size > MOVE_PARAMS_MAX) {
                        return PackValidation.Rejected(PackReject.INVALID_MOVES, "params")
                    }
                    for ((paramIndex, paramValue) in arrayParams.items.withIndex()) {
                        val param = paramValue.asObject()
                            ?: return PackValidation.Rejected(PackReject.INVALID_MOVES, "param")
                        val paramId = param.field("id")?.asString().orEmpty()
                        if (!paramIdPattern.matches(paramId) || names(param.field("about")) == null) {
                            return PackValidation.Rejected(PackReject.INVALID_MOVES, "param")
                        }
                        val min = param.field("min")?.asDouble()
                        val max = param.field("max")?.asDouble()
                        if (min == null || max == null || !min.isFinite() || !max.isFinite() || min >= max) {
                            return PackValidation.Rejected(PackReject.INVALID_MOVES, "param[$paramIndex]")
                        }
                    }
                }
            }
        }

        var canWalk = true
        obj.field("can")?.let { can ->
            val canObject = can.asObject() ?: return@let
            val walk = canObject.field("walk")
            when (val value = walk?.asBoolean()) {
                null -> if (walk != null) skipped.add("can.walk:invalid")
                true -> canWalk = true
                false -> canWalk = false
            }
        }

        obj.field("voice")?.asObject()?.field("pitch")?.let { pitch ->
            val value = pitch.asDouble()
            if (value == null || !value.isFinite() || value < 0.5 || value > 2.0) {
                skipped.add("voice.pitch")
            }
        }
        obj.field("effects")?.asObject()?.field("reach")?.let { reach ->
            val value = reach.asDouble()
            if (value == null || !value.isFinite() || value < 0.0 || value > 2.0) {
                skipped.add("effects.reach")
            }
        }

        val version = obj.field("version")?.asString()?.takeIf { it.isNotBlank() } ?: "0.0.0"
        val author = obj.field("author")?.asString()?.trim()?.takeIf { it.isNotEmpty() }
        var thumb: String? = null
        obj.field("thumb")?.let { thumbValue ->
            val rawThumb = thumbValue.asString()?.trim().orEmpty()
            if (rawThumb.isNotEmpty() && !PackPaths.inside(rawThumb)) {
                return PackValidation.Rejected(PackReject.INCOMPLETE, "thumb")
            }
        }
        obj.field("thumb")?.asString()?.trim()?.takeIf { it.isNotEmpty() }?.let { rawThumb ->
            if (PackPaths.inside(rawThumb) && PackPaths.isSupportedThumb(rawThumb)) {
                val normalized = PackPaths.normalizeArchivePath(rawThumb)
                val allowed = files == null || (
                    normalized != null &&
                        files.containsKey(normalized) &&
                        (files[normalized] ?: 0L) <= THUMB_MAX_BYTES
                    )
                if (allowed) thumb = rawThumb
                else skipped.add("thumb")
            } else {
                skipped.add("thumb")
            }
        }

        val bubble = parseBubble(obj.field("bubble"))
        if (obj.field("bubble") != null && bubble == null) skipped.add("bubble")

        return PackValidation.Accepted(
            PackManifest(
                manifestVersion = manifest,
                api = api,
                id = id,
                version = version,
                name = name,
                about = about,
                entry = entry,
                export = export,
                author = author,
                thumb = thumb,
                vocab = vocabulary,
                axes = axes,
                presets = presets,
                canWalk = canWalk,
                bubble = bubble,
                skipped = skipped,
            ),
        )
    }

    private fun names(value: JsonValue?): PackNames? {
        val map = value?.stringMap() ?: return null
        return PackNames(map)
    }

    private fun nameLists(value: JsonValue?): Map<String, List<String>>? {
        val obj = value?.asObject() ?: return null
        val result = LinkedHashMap<String, List<String>>()
        obj.fields.forEach { (language, listValue) ->
            val list = listValue.asArray() ?: return null
            val words = list.items.mapNotNull { it.asString()?.trim()?.takeIf(String::isNotEmpty) }
            if (words.size != list.items.size || words.isEmpty()) return null
            result[language] = words
        }
        return result.ifEmpty { null }
    }

    private fun parseAxes(value: JsonValue?): List<PackAxis>? {
        if (value == null) return emptyList()
        val array = value.asArray() ?: return emptyList()
        val axes = ArrayList<PackAxis>()
        for (axisValue in array.items) {
            val axis = axisValue.asObject() ?: return null
            val id = axis.field("id")?.asString().orEmpty()
            if (!idPattern.matches(id)) return null
            val name = names(axis.field("name")) ?: return null
            val optionsValue = axis.field("options")?.asArray() ?: return null
            if (optionsValue.items.isEmpty()) return null
            val options = ArrayList<PackAxisOption>()
            for (optionValue in optionsValue.items) {
                val option = optionValue.asObject() ?: return null
                val optionId = option.field("id")?.asString().orEmpty()
                if (!optionPattern.matches(optionId)) return null
                val optionName = names(option.field("name")) ?: return null
                val thumb = option.field("thumb")?.let { value ->
                    val path = value.asString()?.trim().orEmpty()
                    if (path.isEmpty() || !PackPaths.inside(path)) return null
                    path
                }
                options.add(
                    PackAxisOption(
                        id = optionId,
                        name = optionName,
                        thumb = thumb,
                        accent = option.field("accent")?.asString(),
                    ),
                )
            }
            axes.add(PackAxis(id = id, name = name, options = options))
        }
        return axes
    }

    private fun parsePresets(value: JsonValue?, axes: List<PackAxis>): List<PackPreset>? {
        if (value == null) return emptyList()
        val array = value.asArray() ?: return emptyList()
        val presets = ArrayList<PackPreset>()
        for (presetValue in array.items) {
            val preset = presetValue.asObject() ?: return null
            val id = preset.field("id")?.asString().orEmpty()
            if (!idPattern.matches(id)) return null
            val pickValue = preset.field("pick")?.asObject() ?: return null
            val pick = LinkedHashMap<String, String>()
            for (axis in axes) {
                val optionId = pickValue.fields[axis.id]?.asString().orEmpty()
                if (axis.option(optionId) == null) return null
                pick[axis.id] = optionId
            }
            presets.add(
                PackPreset(
                    id = id,
                    name = names(preset.field("name")),
                    pick = pick,
                    accent = preset.field("accent")?.asString(),
                ),
            )
        }
        return presets
    }

    private fun parseBubble(value: JsonValue?): PackBubbleColors? {
        if (value == null) return null
        val light = value.field("light")?.asObject() ?: return null
        val dark = value.field("dark")?.asObject() ?: return null
        fun color(section: JsonValue.Obj, key: String): String? {
            val text = section.fields[key]?.asString() ?: return null
            return text.takeIf { colorPattern.matches(it) }
        }
        return PackBubbleColors(
            lightFill = color(light, "fill") ?: return null,
            lightText = color(light, "text") ?: return null,
            lightLine = color(light, "line") ?: return null,
            darkFill = color(dark, "fill") ?: return null,
            darkText = color(dark, "text") ?: return null,
            darkLine = color(dark, "line") ?: return null,
        )
    }
}
