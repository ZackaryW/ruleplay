package com.zackaryw.ruleplay

import kotlin.random.Random

/**
 * Manages rule-based playback logic.
 *
 * ## Rule 1 – Skip Threshold
 * A random threshold T in [[thresholdMin], [thresholdMax]] is chosen at the start of each "turn"
 * (i.e., after the rule resets).  If the user skips T songs consecutively, skipping is **locked**
 * until one full song plays to completion.  Once the song completes, a new random T is drawn and
 * skipping is unlocked.
 *
 * ## Rule 2 – Skip Frequency
 * Each song tracks how many times it has been skipped across all loops.  If a song's skip count
 * exceeds [skipFrequencyThreshold], the engine signals that the song **must** be played in full
 * (force-play).  This activation is counted; if [forcePlaySuspensionLimit] force-play activations
 * occur in the same loop, the effect is **suspended** for the rest of that loop (to avoid
 * constantly interrupting normal flow when many songs accumulate high skip counts).
 *
 * @param random Random source; injectable for deterministic testing.
 */
class RuleEngine(private val random: Random = Random.Default) {

    // ── Configurable parameters ───────────────────────────────────────────────

    /**
     * Minimum consecutive skips before the skip-lock threshold can be drawn.
     * Changing this redraws the current threshold if it would fall below the new minimum.
     */
    var thresholdMin: Int = THRESHOLD_MIN
        set(value) {
            field = value.coerceAtMost(thresholdMax)
            if (skipThreshold < field) skipThreshold = drawThreshold()
        }

    /**
     * Maximum consecutive skips before skip-lock triggers.
     * Changing this redraws the current threshold if it would exceed the new maximum.
     */
    var thresholdMax: Int = THRESHOLD_MAX
        set(value) {
            field = value.coerceAtLeast(thresholdMin)
            if (skipThreshold > field) skipThreshold = drawThreshold()
        }

    /** Songs skipped more than this many times will be force-played (Rule 2). */
    var skipFrequencyThreshold: Int = SKIP_FREQUENCY_THRESHOLD

    /** After this many force-play activations in one loop, Rule 2 is suspended. */
    var forcePlaySuspensionLimit: Int = FORCE_PLAY_SUSPENSION_LIMIT

    // ── Rule 1 state ─────────────────────────────────────────────────────────

    /** Number of consecutive user-initiated skips in the current "turn". */
    var consecutiveSkips: Int = 0
        private set

    /** Threshold drawn at the start of the current turn; redrawn after each reset. */
    var skipThreshold: Int = drawThreshold()
        private set

    /** True when skipping is blocked because [consecutiveSkips] reached [skipThreshold]. */
    var skipLocked: Boolean = false
        private set

    // ── Rule 2 state ─────────────────────────────────────────────────────────

    /** Cumulative skip count per song ID across all loops. */
    private val skipCounts: MutableMap<String, Int> = mutableMapOf()

    /** How many times force-play has been activated in the current loop. */
    var forcePlayActivationsThisLoop: Int = 0
        private set

    /**
     * True when Rule 2 is suspended for the remainder of the current loop because
     * [forcePlayActivationsThisLoop] has reached [forcePlaySuspensionLimit].
     */
    val isRule2Suspended: Boolean
        get() = forcePlayActivationsThisLoop >= forcePlaySuspensionLimit

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Called when the user presses the skip button.
     *
     * @param songId ID of the song currently playing.
     * @return `true` if the skip is allowed; `false` if it is blocked by Rule 1.
     */
    fun onSkipAttempt(songId: String): Boolean {
        if (skipLocked) return false
        recordSkip(songId)
        return true
    }

    /**
     * Called when a song finishes playing in full (natural end or force-play completion).
     * Unlocks skipping (Rule 1) and redraws the threshold for the next turn.
     *
     * @param songId ID of the song that just completed.
     */
    fun onSongCompleted(songId: String) {
        skipLocked = false
        consecutiveSkips = 0
        skipThreshold = drawThreshold()
    }

    /**
     * Determines whether [songId] must be played in full due to Rule 2.
     *
     * @return `true` if the song's skip count exceeds [skipFrequencyThreshold] AND Rule 2 is
     *         not currently suspended.
     */
    fun shouldForcePlay(songId: String): Boolean {
        if (isRule2Suspended) return false
        return (skipCounts[songId] ?: 0) > skipFrequencyThreshold
    }

    /**
     * Records that the force-play effect was activated for [songId].
     * Increments [forcePlayActivationsThisLoop]; once it reaches [forcePlaySuspensionLimit]
     * the effect is suspended via [isRule2Suspended].
     */
    fun onForcePlayActivated(songId: String) {
        forcePlayActivationsThisLoop++
    }

    /**
     * Call when the playlist wraps around (loop boundary).
     * Resets the per-loop force-play activation counter so Rule 2 can trigger again next loop.
     */
    fun onLoopCompleted() {
        forcePlayActivationsThisLoop = 0
    }

    /**
     * Full state reset; call when a new folder / playlist is loaded.
     */
    fun reset() {
        consecutiveSkips = 0
        skipThreshold = drawThreshold()
        skipLocked = false
        skipCounts.clear()
        forcePlayActivationsThisLoop = 0
    }

    /**
     * Returns a lightweight snapshot of current engine state for display.
     */
    fun getState(): State = State(
        consecutiveSkips = consecutiveSkips,
        skipThreshold = skipThreshold,
        skipLocked = skipLocked,
        forcePlayActivationsThisLoop = forcePlayActivationsThisLoop,
        isRule2Suspended = isRule2Suspended,
        forcePlaySuspensionLimit = forcePlaySuspensionLimit
    )

    // ── Internal helpers ─────────────────────────────────────────────────────

    private fun recordSkip(songId: String) {
        skipCounts[songId] = (skipCounts[songId] ?: 0) + 1
        consecutiveSkips++
        if (consecutiveSkips >= skipThreshold) {
            skipLocked = true
        }
    }

    private fun drawThreshold(): Int =
        random.nextInt(thresholdMin, thresholdMax + 1)

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        /** Songs skipped more than this many times will be force-played (Rule 2). */
        const val SKIP_FREQUENCY_THRESHOLD = 5

        /** After this many force-play activations in one loop, Rule 2 is suspended. */
        const val FORCE_PLAY_SUSPENSION_LIMIT = 3

        /** Minimum number of consecutive skips before skip lock triggers (Rule 1). */
        const val THRESHOLD_MIN = 3

        /** Maximum number of consecutive skips before skip lock triggers (Rule 1). */
        const val THRESHOLD_MAX = 10
    }

    /**
     * Immutable snapshot of the engine's current state.
     */
    data class State(
        val consecutiveSkips: Int,
        val skipThreshold: Int,
        val skipLocked: Boolean,
        val forcePlayActivationsThisLoop: Int,
        val isRule2Suspended: Boolean,
        val forcePlaySuspensionLimit: Int
    )
}
