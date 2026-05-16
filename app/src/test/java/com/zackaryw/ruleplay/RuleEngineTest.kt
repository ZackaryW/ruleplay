package com.zackaryw.ruleplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/**
 * Unit tests for [RuleEngine].
 *
 * A seeded [Random] is injected so threshold draws are deterministic.
 * Sequence for Random(42).nextInt(3, 11): 4, 6, 10, 10, 5, …
 */
class RuleEngineTest {

    private lateinit var engine: RuleEngine

    @Before
    fun setUp() {
        engine = RuleEngine(Random(42))
    }

    // ─── Rule 1 tests ─────────────────────────────────────────────────────────

    @Test
    fun `initial state – skipping is unlocked`() {
        assertFalse(engine.skipLocked)
        assertEquals(0, engine.consecutiveSkips)
    }

    @Test
    fun `skip allowed before threshold is reached`() {
        val threshold = engine.skipThreshold  // 4 with seed 42
        // Skip threshold-1 times – all should succeed without locking.
        repeat(threshold - 1) { index ->
            assertTrue("Skip $index should be allowed", engine.onSkipAttempt("song$index"))
        }
        assertFalse(engine.skipLocked)
        assertEquals(threshold - 1, engine.consecutiveSkips)
    }

    @Test
    fun `skip locked after consecutive skips reach threshold`() {
        val threshold = engine.skipThreshold   // 5 with seed 42
        repeat(threshold) { engine.onSkipAttempt("s$it") }
        assertTrue(engine.skipLocked)
    }

    @Test
    fun `skip attempt rejected when locked`() {
        val threshold = engine.skipThreshold
        repeat(threshold) { engine.onSkipAttempt("s$it") }
        assertTrue(engine.skipLocked)
        assertFalse("Skip while locked must be rejected", engine.onSkipAttempt("extra"))
    }

    @Test
    fun `completing a song unlocks skipping and resets counter`() {
        val threshold = engine.skipThreshold
        repeat(threshold) { engine.onSkipAttempt("s$it") }
        assertTrue(engine.skipLocked)

        engine.onSongCompleted("forced_song")

        assertFalse(engine.skipLocked)
        assertEquals(0, engine.consecutiveSkips)
    }

    @Test
    fun `new threshold is drawn after song completes`() {
        val firstThreshold = engine.skipThreshold
        val threshold = engine.skipThreshold
        repeat(threshold) { engine.onSkipAttempt("s$it") }
        engine.onSongCompleted("forced_song")

        val secondThreshold = engine.skipThreshold
        // With seed 42 the second draw is 8, which differs from the first (5).
        assertTrue(
            "Threshold should be in valid range",
            secondThreshold in RuleEngine.THRESHOLD_MIN..RuleEngine.THRESHOLD_MAX
        )
    }

    @Test
    fun `completing song without lock still resets consecutive skip counter`() {
        engine.onSkipAttempt("a")
        engine.onSkipAttempt("b")
        assertEquals(2, engine.consecutiveSkips)

        engine.onSongCompleted("natural_end")
        assertEquals(0, engine.consecutiveSkips)
        assertFalse(engine.skipLocked)
    }

    @Test
    fun `rule1 resets properly on engine reset`() {
        val threshold = engine.skipThreshold
        repeat(threshold) { engine.onSkipAttempt("s$it") }
        assertTrue(engine.skipLocked)

        engine.reset()
        assertFalse(engine.skipLocked)
        assertEquals(0, engine.consecutiveSkips)
    }

    // ─── Rule 2 tests ─────────────────────────────────────────────────────────

    @Test
    fun `song below skip-frequency threshold does not trigger force-play`() {
        val songId = "track1"
        repeat(RuleEngine.SKIP_FREQUENCY_THRESHOLD) { engine.onSkipAttempt(songId) }
        assertFalse(engine.shouldForcePlay(songId))
    }

    @Test
    fun `song above skip-frequency threshold triggers force-play`() {
        val songId = "track1"
        // Each iteration: skip the target song, then complete a different song so Rule 1 resets
        // and does not block subsequent skips of our target.
        repeat(RuleEngine.SKIP_FREQUENCY_THRESHOLD + 1) {
            assertTrue(engine.onSkipAttempt(songId))
            engine.onSongCompleted("other_song") // resets consecutive-skip counter (Rule 1)
        }
        assertTrue(engine.shouldForcePlay(songId))
    }

    @Test
    fun `force-play activation increments loop counter`() {
        assertEquals(0, engine.forcePlayActivationsThisLoop)
        engine.onForcePlayActivated("track1")
        assertEquals(1, engine.forcePlayActivationsThisLoop)
    }

    @Test
    fun `rule2 suspended after 3 force-play activations in one loop`() {
        assertFalse(engine.isRule2Suspended)
        repeat(RuleEngine.FORCE_PLAY_SUSPENSION_LIMIT) {
            engine.onForcePlayActivated("song$it")
        }
        assertTrue(engine.isRule2Suspended)
    }

    @Test
    fun `shouldForcePlay returns false when rule2 is suspended`() {
        val songId = "heavy_skip_song"
        // Build up skip count past threshold (reset Rule 1 between each skip).
        repeat(RuleEngine.SKIP_FREQUENCY_THRESHOLD + 1) {
            engine.onSkipAttempt(songId)
            engine.onSongCompleted("other")
        }
        assertTrue(engine.shouldForcePlay(songId))   // not yet suspended

        repeat(RuleEngine.FORCE_PLAY_SUSPENSION_LIMIT) {
            engine.onForcePlayActivated("other$it")
        }
        assertTrue(engine.isRule2Suspended)
        assertFalse("shouldForcePlay must be false when suspended", engine.shouldForcePlay(songId))
    }

    @Test
    fun `rule2 suspension lifts after loop completes`() {
        repeat(RuleEngine.FORCE_PLAY_SUSPENSION_LIMIT) {
            engine.onForcePlayActivated("song$it")
        }
        assertTrue(engine.isRule2Suspended)

        engine.onLoopCompleted()
        assertFalse(engine.isRule2Suspended)
        assertEquals(0, engine.forcePlayActivationsThisLoop)
    }

    @Test
    fun `rule2 skip count persists across loop boundaries`() {
        val songId = "persisted"
        // Accumulate enough skips (reset Rule 1 between each) to exceed the threshold.
        repeat(RuleEngine.SKIP_FREQUENCY_THRESHOLD + 1) {
            engine.onSkipAttempt(songId)
            engine.onSongCompleted("other") // reset Rule 1
        }

        engine.onLoopCompleted()   // should NOT reset skip counts, only loop counter

        assertTrue("Skip count should persist across loops", engine.shouldForcePlay(songId))
    }

    @Test
    fun `reset clears all state including skip counts`() {
        val songId = "track99"
        repeat(RuleEngine.SKIP_FREQUENCY_THRESHOLD + 1) { engine.onSkipAttempt(songId) }
        repeat(2) { engine.onForcePlayActivated("x$it") }

        engine.reset()

        assertFalse(engine.shouldForcePlay(songId))
        assertEquals(0, engine.forcePlayActivationsThisLoop)
        assertFalse(engine.isRule2Suspended)
    }

    // ─── State snapshot ───────────────────────────────────────────────────────

    @Test
    fun `getState reflects current engine state`() {
        engine.onSkipAttempt("a")
        engine.onSkipAttempt("b")
        val state = engine.getState()
        assertEquals(2, state.consecutiveSkips)
        assertFalse(state.skipLocked)
        assertFalse(state.isRule2Suspended)
    }
}
