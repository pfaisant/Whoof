package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

class DayCycleResolverTest {
    @Test fun sleepOnsetIsTheDefaultPersistedMode() {
        assertEquals(DayCycleMode.SLEEP_ONSET, DayCycleMode.fromPersisted(null))
        assertEquals(DayCycleMode.SLEEP_ONSET, DayCycleMode.fromPersisted("unknown"))
    }

    @Test fun fallbackRollsWhenTheNextMidnightIsTooSoon() {
        // 23:00 + 18 h overshoots the next midnight, so the one after it wins.
        val monday2300 = 23 * 3_600L
        assertEquals(2 * 86_400L, DayCycleResolver.fallbackMidnightAfter(monday2300, 0))
    }

    /**
     * The other branch, which neither platform pinned. The candidate midnight is
     * `floorDiv(minimum, day) * day`, so it is at or BELOW `minimum` always — the direct branch is
     * reachable only on exact equality, when onset sits precisely 18 h before a midnight. Every other
     * case here rolls, so a `>=` quietly weakened to `>` would move this boundary a full day unseen.
     */
    @Test fun fallbackTakesTheMidnightExactlyEighteenHoursAfterOnset() {
        assertEquals(86_400L, DayCycleResolver.fallbackMidnightAfter(6 * 3_600L, 0))
    }

    /**
     * The boundary is LOCAL midnight, not UTC midnight — and until now nothing said so. Every day-cycle
     * case on both platforms passed a zero offset, so the offset arithmetic, which decides which local
     * day a boundary lands on, was unpinned. This repo has already had days re-bucket on travel once.
     *
     * 06:00 local at UTC-5 is 11:00 UTC. Plus 18 h is 05:00 UTC the next day, which IS local midnight
     * there, so the direct branch takes it: 104_400 = 29 h UTC = 00:00 local. A resolver that floored to
     * UTC midnight would answer 86_400 and be a day out for a third of the planet.
     */
    @Test fun fallbackLandsOnLocalMidnightNotUtc() {
        val offset = -5 * 3_600L
        val onsetLocal0600 = 6 * 3_600L - offset
        assertEquals(104_400L, DayCycleResolver.fallbackMidnightAfter(onsetLocal0600, offset))
    }

    @Test fun allNighterStaysOpenUntilTheAbsoluteCap() {
        val sleep = DayCycleWindow("night", 0, 0, "1970-01-01", DayCycleWindow.Source.DETECTED_SLEEP)
        assertEquals(
            DayCycleWindow.Source.DETECTED_SLEEP,
            DayCycleResolver.activeWindow(DayCycleMode.SLEEP_ONSET, sleep, 39 * 3_600L, 0).source,
        )
        assertEquals(
            DayCycleWindow.Source.SYNTHETIC_MIDNIGHT,
            DayCycleResolver.activeWindow(DayCycleMode.SLEEP_ONSET, sleep, 40 * 3_600L, 0).source,
        )
    }

    /**
     * The regression this test exists for: a cycle left open for DAYS.
     *
     * [DayCycleResolver.ABSOLUTE_MAX_OPEN_SECONDS] exists to stop a stale sleep boundary staying active
     * forever, and the boundary it installed instead was itself permanently stale — derived from the
     * ONSET alone, so it never moved however long sleep went undetected. With the last detected night on
     * the 19th, the 21st's cycle was still the open one on the 22nd: its window swallowed the following
     * day's steps and heart rate, and no day after the first ever got a cycle of its own.
     */
    @Test fun anUndetectedNightDoesNotLeaveYesterdayOwningToday() {
        val onset = 23 * 3_600L                       // day 0, 23:00
        val now = 4 * 86_400L + 7 * 3_600L            // day 4, 07:00 — four nights with nothing detected
        val sleep = DayCycleWindow("night", onset, 0, "1970-01-02", DayCycleWindow.Source.DETECTED_SLEEP)
        val window = DayCycleResolver.activeWindow(DayCycleMode.SLEEP_ONSET, sleep, now, 0)
        assertEquals(DayCycleWindow.Source.SYNTHETIC_MIDNIGHT, window.source)
        // Opens at TODAY's midnight, so the open cycle is at most a day long...
        assertEquals(4 * 86_400L, window.startInclusive)
        assertEquals("1970-01-05", window.displayDay)
        // ...and every day in between is owed its own boundary, not merged into the first.
        assertEquals(
            listOf(2 * 86_400L, 3 * 86_400L, 4 * 86_400L),
            DayCycleResolver.syntheticMidnightsAfter(onset, now, 0),
        )
    }

    /** An over-long cycle still inside the day it began in is owed nothing: it is not a gap yet. */
    @Test fun anAllNighterIsNotYetOwedASyntheticBoundary() {
        assertEquals(emptyList<Long>(), DayCycleResolver.syntheticMidnightsAfter(3_600L, 20 * 3_600L, 0))
        assertEquals(listOf(86_400L), DayCycleResolver.syntheticMidnightsAfter(3_600L, 30 * 3_600L, 0))
    }

    @Test fun sleepOnsetCycleStaysOpenAcrossMidnight() {
        val sleep = DayCycleWindow("night", 23 * 3_600L, 0, "1970-01-02", DayCycleWindow.Source.DETECTED_SLEEP)
        assertEquals(
            DayCycleWindow.Source.DETECTED_SLEEP,
            DayCycleResolver.activeWindow(DayCycleMode.SLEEP_ONSET, sleep, 2 * 86_400L + 60, 0).source,
        )
    }

    @Test fun midnightModeStillResetsAtCalendarMidnight() {
        val sleep = DayCycleWindow("night", 23 * 3_600L, 0, "1970-01-02", DayCycleWindow.Source.DETECTED_SLEEP)
        assertEquals(
            DayCycleWindow.Source.CALENDAR,
            DayCycleResolver.activeWindow(DayCycleMode.MIDNIGHT, sleep, 2 * 86_400L + 60, 0).source,
        )
    }
}
