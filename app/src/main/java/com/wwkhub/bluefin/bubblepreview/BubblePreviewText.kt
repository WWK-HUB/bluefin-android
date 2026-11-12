package com.wwkhub.bluefin.bubblepreview

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min

/**
 * Supplies candidate extended-grapheme boundaries to [BubblePreviewText].
 *
 * Implementations receive the cumulative snapshot only for the duration of the
 * call; they must not retain it. [nextBoundary] returns the exclusive end of
 * the cluster starting at [start], or -1 when [start] is at/after [limit].
 *
 * A returned boundary is treated as a candidate: [BubblePreviewText] applies
 * its safety merger so surrogate pairs, combining marks, variation selectors,
 * skin tones, ZWJ sequences, regional-indicator pairs and tag sequences are
 * never split even when a platform iterator reports a boundary inside them.
 */
fun interface SegmentationBoundaryPolicy {
    fun nextBoundary(text: CharSequence, start: Int, limit: Int): Int
}

/**
 * Pure-JVM safe fallback scanner. It is deliberately conservative rather than
 * a complete UAX #29 implementation, and is also used automatically when a
 * platform [BreakIterator] is unavailable or returns an invalid boundary.
 */
object SafeSegmentationBoundaryPolicy : SegmentationBoundaryPolicy {
    override fun nextBoundary(text: CharSequence, start: Int, limit: Int): Int {
        if (start >= limit) return -1
        val boundedEnd = min(limit, start + SAFE_POLICY_WINDOW_CODE_UNITS)
        return safeClusterEnd(text, start, boundedEnd)
    }

    private const val SAFE_POLICY_WINDOW_CODE_UNITS = 1_024
}

/**
 * Baseline policy backed by [java.text.BreakIterator.getCharacterInstance].
 *
 * The JDK iterator is a character iterator, not a full ICU grapheme iterator:
 * it can split emoji modification/ZWJ sequences and some marks. The preview's
 * safety merger repairs those candidates. A bounded window is used so this
 * policy never copies or retains the whole reply. Device ICU results still need
 * real-device validation; this is a portable JVM baseline, not a claim of full
 * UAX #29 conformance for every script.
 */
class BreakIteratorSegmentationBoundaryPolicy(
    private val locale: Locale = Locale.ROOT,
    private val windowCodeUnits: Int = DEFAULT_WINDOW_CODE_UNITS,
) : SegmentationBoundaryPolicy {
    init {
        require(windowCodeUnits >= 2) { "windowCodeUnits must be at least 2" }
    }

    // One character iterator per policy instance. The preview is single-threaded
    // (UI thread), so reusing it avoids allocating a new BreakIterator for every
    // cluster while keeping the policy self-contained.
    private val iterator = BreakIterator.getCharacterInstance(locale)

    override fun nextBoundary(text: CharSequence, start: Int, limit: Int): Int {
        if (start >= limit) return -1
        return try {
            var windowEnd = min(limit, start + windowCodeUnits)
            if (windowEnd < limit && windowEnd > start &&
                Character.isHighSurrogate(text[windowEnd - 1]) &&
                Character.isLowSurrogate(text[windowEnd])
            ) {
                windowEnd -= 1
            }
            if (windowEnd <= start) return SafeSegmentationBoundaryPolicy.nextBoundary(text, start, limit)

            val window = text.subSequence(start, windowEnd).toString()
            iterator.setText(window)
            val localEnd = iterator.next()
            if (localEnd == BreakIterator.DONE || localEnd <= 0) {
                SafeSegmentationBoundaryPolicy.nextBoundary(text, start, limit)
            } else {
                start + localEnd
            }
        } catch (_: RuntimeException) {
            // Keep the preview usable if a platform iterator misbehaves.
            SafeSegmentationBoundaryPolicy.nextBoundary(text, start, limit)
        }
    }

    companion object {
        const val DEFAULT_WINDOW_CODE_UNITS: Int = 256
    }
}

/**
 * Immutable view of the bounded, grapheme-safe preview.
 *
 * [visibleText] is always a prefix of whole grapheme clusters. [hasMore] is true
 * when the source has more content than the preview can show (overflow) or when
 * the live stream is still holding its final, possibly-incomplete cluster.
 */
data class BubblePreviewFrame(
    val visibleText: String,
    val visibleGraphemes: Int,
    val targetGraphemes: Int,
    val hasMore: Boolean,
    val overflow: Boolean,
    val pendingGrapheme: Boolean,
    val isTyping: Boolean,
    val isTerminal: Boolean,
    val isComplete: Boolean,
    val textChanged: Boolean,
    val staleInput: Boolean = false,
)

/**
 * Pure state for a bounded typing preview.
 *
 * The caller supplies the cumulative reply text (never a delta), an
 * [isTerminal] flag and a monotonic timestamp. This class never starts a
 * thread, handler or timer; the host owns the 33 ms scheduling and calls
 * [onTick].
 *
 * Retention is bounded: at most [maxGraphemes] cluster strings are stored in
 * total across the visible target and the single pending final cluster. The
 * cumulative source string is inspected only for the duration of a call and is
 * not retained.
 */
class BubblePreviewText(
    private val maxGraphemes: Int = MAX_VISIBLE_GRAPHEMES,
    private val tickMillis: Long = TICK_MILLIS,
    private val graphemesPerTick: Int = GRAPHEMES_PER_TICK,
    private val backlogJumpThreshold: Int = BACKLOG_JUMP_THRESHOLD,
    private val terminalCatchupMillis: Long = TERMINAL_CATCHUP_MILLIS,
    private val maxScanCodeUnits: Int = DEFAULT_MAX_SCAN_CODE_UNITS,
    private val maxClusterCodeUnits: Int = DEFAULT_MAX_CLUSTER_CODE_UNITS,
    private val segmentationPolicy: SegmentationBoundaryPolicy = BreakIteratorSegmentationBoundaryPolicy(),
) {
    init {
        require(maxGraphemes in 1..MAX_VISIBLE_GRAPHEMES) {
            "maxGraphemes must be in 1..$MAX_VISIBLE_GRAPHEMES"
        }
        require(tickMillis > 0L) { "tickMillis must be positive" }
        require(graphemesPerTick > 0) { "graphemesPerTick must be positive" }
        require(backlogJumpThreshold >= 0) { "backlogJumpThreshold must not be negative" }
        require(terminalCatchupMillis >= 0L) { "terminalCatchupMillis must not be negative" }
        require(maxScanCodeUnits > 0) { "maxScanCodeUnits must be positive" }
        require(maxClusterCodeUnits > 0) { "maxClusterCodeUnits must be positive" }
    }

    private val targetClusters = ArrayList<String>(maxGraphemes)
    private var revealedGraphemes = 0

    private var sourceLength = -1

    /**
     * Code units at the start of the previous snapshot that actually influenced
     * the retained target. This is the preview-relevant prefix for stale-input
     * checks, so a long reply does not re-hash an arbitrary multi-kilobyte
     * prefix on every token.
     */
    private var sourcePrefixLength = 0
    private var sourcePrefixHash = 0L

    private var terminal = false
    private var overflow = false
    private var pendingGrapheme = false
    private var pendingCluster: String? = null

    /**
     * Once the finite 240-cluster target is complete or overflow is known, the
     * retained prefix can never change for a longer valid snapshot. Growing
     * snapshots then only refresh length/overflow/terminal state instead of
     * re-segmenting and rebuilding every cluster.
     */
    private var targetFrozen = false

    private var visibleCacheCount = -1
    private var visibleCacheText = ""

    private var terminalAtMillis = -1L
    private var terminalBaseRevealed = 0
    private var terminalTotal = 0
    private var lastTickMillis = -1L

    /**
     * Accepts one cumulative snapshot. Repeated or older cumulative snapshots
     * are ignored: text is never appended twice, a shorter snapshot cannot roll
     * the preview back, and a non-terminal snapshot cannot reopen a terminal
     * preview.
     *
     * [flushImmediately] is intended for the terminal snapshot only. It is also
     * safe to pass for a normal terminal completion when the host needs to show
     * the last cluster at once.
     */
    fun onSnapshot(
        cumulative: String,
        isTerminal: Boolean,
        nowMillis: Long,
        flushImmediately: Boolean = false,
    ): BubblePreviewFrame {
        require(nowMillis >= 0L) { "nowMillis must not be negative" }
        val effectiveTerminal = isTerminal || flushImmediately
        if (isStale(cumulative, effectiveTerminal)) {
            return buildFrame(textChanged = false, staleInput = true)
        }

        val before = buildVisibleText()
        val hadBacklog = targetClusters.size > revealedGraphemes
        sourceLength = cumulative.length

        // Time spent caught up (or with an empty source) is idle, not a bank of
        // logical ticks for text that arrives later.
        if (lastTickMillis < 0L || !hadBacklog) lastTickMillis = nowMillis

        if (effectiveTerminal && !terminal) {
            terminal = true
            terminalAtMillis = nowMillis
            terminalBaseRevealed = revealedGraphemes
        }

        if (!targetFrozen) {
            sourcePrefixLength = rebuildTarget(cumulative)
            sourcePrefixHash = hashPrefix(cumulative, sourcePrefixLength)
            // The retained prefix cannot change for longer valid snapshots once
            // the finite target is complete or overflow is known. Freeze here
            // so later growing snapshots skip segmentation and rebuilding.
            targetFrozen = overflow || targetClusters.size >= maxGraphemes
        }

        if (terminal) {
            terminalTotal = maxOf(terminalTotal, targetClusters.size)
            if (terminalAtMillis < 0L) terminalAtMillis = nowMillis
        }
        if (revealedGraphemes > targetClusters.size) {
            revealedGraphemes = targetClusters.size
        }

        if (flushImmediately) {
            if (!terminal) {
                terminal = true
                terminalAtMillis = nowMillis
                terminalBaseRevealed = revealedGraphemes
            }
            revealedGraphemes = targetClusters.size
            terminalBaseRevealed = targetClusters.size
            terminalTotal = targetClusters.size
        }

        return buildFrame(
            textChanged = buildVisibleText() != before,
            staleInput = false,
        )
    }

    /**
     * Advances the logical 33 ms clock and returns the new frame. A backlog
     * above [backlogJumpThreshold] is cut down to the threshold first; terminal
     * replies use the bounded two-second catch-up path instead.
     *
     * Only elapsed time while there is a backlog is converted to reveal ticks.
     * Idle time accumulated after the preview caught up (or while the source was
     * empty) is discarded instead of being spent on newly arriving text.
     */
    fun onTick(nowMillis: Long): BubblePreviewFrame {
        require(nowMillis >= 0L) { "nowMillis must not be negative" }
        if (sourceLength < 0) return buildFrame(textChanged = false, staleInput = false)
        if (lastTickMillis < 0L) lastTickMillis = nowMillis

        val before = buildVisibleText()

        if (!terminal) {
            val backlog = targetClusters.size - revealedGraphemes
            if (backlog <= 0) {
                // Nothing is waiting to be typed: drop this idle interval.
                lastTickMillis = nowMillis
            } else {
                if (backlog > backlogJumpThreshold) {
                    revealedGraphemes += backlog - backlogJumpThreshold
                }
                advanceByElapsedTicks(nowMillis)
            }
        } else {
            advanceByElapsedTicks(nowMillis)
        }

        if (terminal) {
            val span = terminalCatchupMillis.coerceAtLeast(1L)
            val elapsedTerminal = (nowMillis - terminalAtMillis).coerceAtLeast(0L)
            val total = maxOf(terminalTotal, targetClusters.size)
            if (elapsedTerminal + tickMillis >= span) {
                // Finish on the last logical tick that can still land at or
                // before the catch-up deadline.
                if (total > revealedGraphemes) revealedGraphemes = total
            } else {
                val remaining = (total - terminalBaseRevealed).coerceAtLeast(0)
                val ratio = elapsedTerminal.toDouble() / span.toDouble()
                val floor = terminalBaseRevealed + ceil(remaining * ratio).toInt()
                if (floor > revealedGraphemes) {
                    revealedGraphemes = min(total, floor)
                }
            }
        }

        if (revealedGraphemes > targetClusters.size) {
            revealedGraphemes = targetClusters.size
        }

        return buildFrame(
            textChanged = buildVisibleText() != before,
            staleInput = false,
        )
    }

    /**
     * Stop/error path: accept the last cumulative snapshot as terminal and
     * reveal all of it immediately, including the held final cluster.
     */
    fun onInterrupted(cumulative: String, nowMillis: Long): BubblePreviewFrame {
        require(nowMillis >= 0L) { "nowMillis must not be negative" }
        if (!isStale(cumulative, true)) {
            onSnapshot(cumulative, isTerminal = true, nowMillis = nowMillis)
        }
        return flushNow(nowMillis)
    }

    /**
     * Immediately reveals the currently known target. This is useful for a
     * stop/error callback that already supplied the latest snapshot.
     */
    fun flushNow(nowMillis: Long): BubblePreviewFrame {
        require(nowMillis >= 0L) { "nowMillis must not be negative" }
        val before = buildVisibleText()
        if (!terminal) {
            terminal = true
            terminalAtMillis = nowMillis
            terminalBaseRevealed = revealedGraphemes
        }
        val held = pendingCluster
        if (held != null && targetClusters.size < maxGraphemes) {
            targetClusters.add(held)
        }
        pendingCluster = null
        pendingGrapheme = false
        revealedGraphemes = targetClusters.size
        terminalBaseRevealed = targetClusters.size
        terminalTotal = targetClusters.size
        targetFrozen = overflow || targetClusters.size >= maxGraphemes
        if (lastTickMillis < 0L) lastTickMillis = nowMillis
        return buildFrame(
            textChanged = buildVisibleText() != before,
            staleInput = false,
        )
    }

    /** Clears every piece of preview state for a new accepted user turn. */
    fun resetForNewTurn() {
        targetClusters.clear()
        revealedGraphemes = 0
        sourceLength = -1
        sourcePrefixLength = 0
        sourcePrefixHash = 0L
        terminal = false
        overflow = false
        pendingGrapheme = false
        pendingCluster = null
        targetFrozen = false
        visibleCacheCount = -1
        visibleCacheText = ""
        terminalAtMillis = -1L
        terminalBaseRevealed = 0
        terminalTotal = 0
        lastTickMillis = -1L
    }

    /** Returns the cached frame without advancing time or replaying text. */
    fun frame(): BubblePreviewFrame = buildFrame(textChanged = false, staleInput = false)

    fun visibleText(): String = buildVisibleText()

    private fun isStale(cumulative: String, isTerminal: Boolean): Boolean {
        if (sourceLength < 0) return false
        if (cumulative.length < sourceLength) return true
        if (hashPrefix(cumulative, sourcePrefixLength) != sourcePrefixHash) return true
        if (terminal) {
            if (!isTerminal) return true
            if (cumulative.length != sourceLength) return true
        }
        return false
    }

    private fun advanceByElapsedTicks(nowMillis: Long) {
        val elapsed = (nowMillis - lastTickMillis).coerceAtLeast(0L)
        val ticks = elapsed / tickMillis
        if (ticks <= 0L) return

        val room = (targetClusters.size - revealedGraphemes).coerceAtLeast(0)
        val applicableTicks = min(ticks, room.toLong())
        val advance = (applicableTicks * graphemesPerTick.toLong())
            .coerceAtMost(room.toLong())
            .toInt()
        revealedGraphemes += advance
        // Preserve only the sub-tick remainder. Whole idle/full intervals are
        // not carried forward as a burst for later text.
        lastTickMillis = nowMillis - (elapsed % tickMillis)
    }

    /**
     * Rebuilds the bounded target and returns the number of source code units
     * that actually influenced it (the preview-relevant stale-check prefix).
     *
     * The cap covers the target AND the held final cluster. A stream with
     * exactly [maxGraphemes] clusters therefore stores max-1 target clusters
     * plus one pending cluster without false overflow; actual content beyond
     * the cap sets [overflow].
     */
    private fun rebuildTarget(cumulative: String): Int {
        targetClusters.clear()
        visibleCacheCount = -1
        visibleCacheText = ""
        overflow = false
        pendingGrapheme = false
        pendingCluster = null

        var index = 0
        var scanned = 0
        while (index < cumulative.length) {
            if (scanned >= maxScanCodeUnits) {
                overflow = true
                break
            }
            val end = nextClusterEnd(cumulative, index)
            if (end <= index) {
                overflow = true
                break
            }
            val clusterLength = end - index
            if (scanned + clusterLength > maxScanCodeUnits) {
                // The bound is hit. Do not emit a partial or unverified
                // cluster; the host only needs to know there is more content.
                overflow = true
                break
            }
            scanned += clusterLength

            val isFinal = end >= cumulative.length
            val cluster = sanitizeCluster(cumulative.substring(index, end))
            index = end

            if (isFinal && !terminal) {
                // A following snapshot could still add a combining mark,
                // variation selector, skin tone or ZWJ continuation. Keep the
                // final cluster out of the visible target until it is complete.
                // It counts against the same cap as the target.
                if (targetClusters.size < maxGraphemes) {
                    pendingCluster = cluster
                    pendingGrapheme = true
                } else {
                    overflow = true
                }
                break
            }

            if (targetClusters.size >= maxGraphemes) {
                overflow = true
                break
            }
            targetClusters.add(cluster)

            if (targetClusters.size >= maxGraphemes && index < cumulative.length) {
                // More clusters exist beyond the retained cap.
                overflow = true
                break
            }
        }
        return scanned
    }

    private fun buildFrame(textChanged: Boolean, staleInput: Boolean): BubblePreviewFrame {
        val text = buildVisibleText()
        return BubblePreviewFrame(
            visibleText = text,
            visibleGraphemes = revealedGraphemes,
            targetGraphemes = targetClusters.size,
            hasMore = overflow || pendingGrapheme,
            overflow = overflow,
            pendingGrapheme = pendingGrapheme,
            isTyping = revealedGraphemes < targetClusters.size,
            isTerminal = terminal,
            isComplete = terminal && revealedGraphemes >= targetClusters.size,
            textChanged = textChanged,
            staleInput = staleInput,
        )
    }

    private fun buildVisibleText(): String {
        val count = min(revealedGraphemes, targetClusters.size)
        if (count == visibleCacheCount) return visibleCacheText
        if (count <= 0) {
            visibleCacheCount = 0
            visibleCacheText = ""
            return ""
        }
        val result = StringBuilder()
        for (index in 0 until count) {
            result.append(targetClusters[index])
        }
        visibleCacheCount = count
        visibleCacheText = result.toString()
        return visibleCacheText
    }

    private fun hashPrefix(text: String, length: Int): Long {
        var hash = 0L
        for (index in 0 until length) {
            hash = hash * 31L + text[index].code.toLong()
        }
        return hash
    }

    /**
     * Finds one safe cluster end. A platform policy supplies a candidate
     * boundary, then explicit merging repairs the cases a character iterator
     * may split. The safe scanner is used as a bounded fallback when the
     * injected policy fails or returns an unusable boundary.
     *
     * Returns -1 when the cluster exceeds [maxClusterCodeUnits]. The caller
     * marks overflow and never emits a partial cluster.
     */
    private fun nextClusterEnd(text: String, start: Int): Int {
        val length = text.length
        if (start >= length) return -1

        val candidate = try {
            segmentationPolicy.nextBoundary(text, start, length)
        } catch (_: RuntimeException) {
            -1
        }
        val safeCandidate = if (candidate in (start + 1)..length) {
            candidate
        } else {
            safeClusterEnd(text, start, length)
        }
        if (safeCandidate <= start || safeCandidate > length) return -1

        val end = mergeClusterBoundary(text, start, safeCandidate)
        if (end <= start || end - start > maxClusterCodeUnits) return -1
        return end
    }

    private fun mergeClusterBoundary(text: String, start: Int, candidateEnd: Int): Int {
        val length = text.length
        if (start >= length) return -1

        var end = candidateEnd.coerceIn(start + 1, length)
        if (end < length &&
            Character.isHighSurrogate(text[end - 1]) &&
            Character.isLowSurrogate(text[end])
        ) {
            end += 1
        }
        if (end - start > maxClusterCodeUnits) return -1

        val first = Character.codePointAt(text, start)
        val firstEnd = start + Character.charCount(first)
        if (end < firstEnd) end = firstEnd
        if (end - start > maxClusterCodeUnits) return -1

        if (isControl(first)) {
            if (first == 0x0D && end == firstEnd && end < length && text[end] == '\n') {
                if (end + 1 - start <= maxClusterCodeUnits) end += 1
            }
            return end
        }

        if (isRegionalIndicator(first) && end == firstEnd && end < length) {
            val second = Character.codePointAt(text, end)
            if (isRegionalIndicator(second)) {
                val secondLength = Character.charCount(second)
                if (end + secondLength - start > maxClusterCodeUnits) return -1
                end += secondLength
            }
        }

        while (end < length) {
            if (end - start > maxClusterCodeUnits) return -1

            // A policy must never leave a surrogate pair split at the boundary.
            if (Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) {
                end += 1
                continue
            }

            // The candidate may have already consumed a ZWJ; pull in the joined
            // code point before looking at any following modifiers.
            if (text[end - 1] == '\u200D') {
                val joined = Character.codePointAt(text, end)
                val joinedLength = Character.charCount(joined)
                if (end + joinedLength - start > maxClusterCodeUnits) return -1
                end += joinedLength
                continue
            }

            val codePoint = Character.codePointAt(text, end)
            val codePointLength = Character.charCount(codePoint)

            if (codePoint == 0x200D) {
                if (end + codePointLength - start > maxClusterCodeUnits) return -1
                end += codePointLength
                if (end >= length) break
                val joined = Character.codePointAt(text, end)
                val joinedLength = Character.charCount(joined)
                if (end + joinedLength - start > maxClusterCodeUnits) return -1
                end += joinedLength
                continue
            }

            if (isExtend(codePoint)) {
                if (end + codePointLength - start > maxClusterCodeUnits) return -1
                end += codePointLength
                continue
            }

            break
        }

        return if (end - start <= maxClusterCodeUnits) end else -1
    }

    private fun sanitizeCluster(cluster: String): String {
        val builder = StringBuilder(cluster.length)
        var index = 0
        while (index < cluster.length) {
            val char = cluster[index]
            if (Character.isHighSurrogate(char)) {
                if (index + 1 < cluster.length && Character.isLowSurrogate(cluster[index + 1])) {
                    builder.append(char).append(cluster[index + 1])
                    index += 2
                } else {
                    builder.append('\uFFFD')
                    index += 1
                }
            } else if (Character.isLowSurrogate(char)) {
                builder.append('\uFFFD')
                index += 1
            } else {
                builder.append(char)
                index += 1
            }
        }

        var end = builder.length
        while (end > 0 && builder[end - 1] == '\u200D') {
            end -= 1
        }
        if (end == 0) return "\uFFFD"
        return if (end == builder.length) builder.toString() else builder.substring(0, end)
    }

    companion object {
        const val MAX_VISIBLE_GRAPHEMES: Int = 240
        const val BACKLOG_JUMP_THRESHOLD: Int = 60
        const val TICK_MILLIS: Long = 33L
        const val GRAPHEMES_PER_TICK: Int = 1
        const val TERMINAL_CATCHUP_MILLIS: Long = 2_000L
        const val DEFAULT_MAX_SCAN_CODE_UNITS: Int = 32_768
        const val DEFAULT_MAX_CLUSTER_CODE_UNITS: Int = 1_024
    }
}

/**
 * Platform-neutral safe cluster scanner used by [SafeSegmentationBoundaryPolicy]
 * and as the fallback when an injected policy fails. It handles surrogate
 * pairs, CRLF, controls, combining/spacing/enclosing marks, variation
 * selectors, skin tones, ZWJ sequences, regional-indicator pairs and tags.
 */
private fun safeClusterEnd(text: CharSequence, start: Int, limit: Int): Int {
    if (start >= limit) return -1

    val first = Character.codePointAt(text, start)
    var index = start + Character.charCount(first)

    if (first == 0x0D && index < limit && text[index] == '\n') {
        return index + 1
    }
    if (isControl(first)) return index

    if (isRegionalIndicator(first) && index < limit) {
        val second = Character.codePointAt(text, index)
        if (isRegionalIndicator(second)) {
            index += Character.charCount(second)
        }
    }

    while (index < limit) {
        val codePoint = Character.codePointAt(text, index)
        if (codePoint == 0x200D) {
            index += Character.charCount(codePoint)
            if (index >= limit) break
            val joined = Character.codePointAt(text, index)
            index += Character.charCount(joined)
            continue
        }
        if (isExtend(codePoint)) {
            index += Character.charCount(codePoint)
            continue
        }
        break
    }
    return index
}

private fun isRegionalIndicator(codePoint: Int): Boolean =
    codePoint in 0x1F1E6..0x1F1FF

private fun isControl(codePoint: Int): Boolean {
    if (codePoint == 0x0A || codePoint == 0x0D) return true
    return when (Character.getType(codePoint)) {
        Character.CONTROL.toInt(),
        Character.LINE_SEPARATOR.toInt(),
        Character.PARAGRAPH_SEPARATOR.toInt(),
        -> true
        else -> false
    }
}

private fun isExtend(codePoint: Int): Boolean {
    // ZWNJ and selector/modifier/tag attachments.
    if (codePoint == 0x200C) return true
    if (codePoint in 0xFE00..0xFE0F) return true
    if (codePoint in 0xE0100..0xE01EF) return true
    if (codePoint in 0x1F3FB..0x1F3FF) return true
    if (codePoint in 0xE0020..0xE007F) return true
    return when (Character.getType(codePoint)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        -> true
        else -> false
    }
}
