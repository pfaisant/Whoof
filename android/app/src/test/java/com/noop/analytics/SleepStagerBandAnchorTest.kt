package com.noop.analytics

import com.noop.data.GravitySample
import com.noop.data.HrSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Whoof band-anchored onset/offset ([SleepStager.bandAnchoredSpan]) and its wiring into
 * [SleepStager.detectSleep].
 *
 * The night this exists for: 2026-09-21, a wearer who went to bed after 02:00 and whose strap (the same
 * signal the official app shows) placed sleep at 02:18→06:54. The HR-against-day-median gates scored the
 * evening on the sofa as sleep from 00:58 and the lie-in to 07:33 as sleep too — 6 h 32 at 99 % efficiency
 * for 4 h 14 of sleep. The strap's own band said 1 (still) before 02:18 and after 06:54, and 2 (asleep)
 * between. These tests pin that the band, when dense, places the edges; that it only ever shrinks; that a
 * sparse or absent band changes nothing; and that a still hour the strap watched and never called asleep
 * is not a night.
 */
class SleepStagerBandAnchorTest {
    private val dev = "test"
    /** 2025-06-10 00:00:00 UTC — an arbitrary fixed midnight (ref % 86400 == 0). */
    private val refMidnight = 1_749_513_600L
    private fun at(hour: Int, minute: Int = 0): Long = refMidnight + hour * 3_600L + minute * 60L
    private fun period(start: Long, end: Long) = SleepStager.Period("sleep", start, end)
    /** One band sample per 30 s epoch across [start, end), state chosen per epoch by [stateAt]. */
    private fun band(start: Long, end: Long, stateAt: (Long) -> Int): List<Pair<Long, Int>> =
        generateSequence(start) { it + 30L }.takeWhile { it < end }.map { it to stateAt(it) }.toList()
    private fun stillGravity(start: Long, end: Long): List<GravitySample> =
        (start until end).map { GravitySample(deviceId = dev, ts = it, x = 0.0, y = 0.0, z = 1.0) }
    private fun hr(start: Long, end: Long, bpm: Int): List<HrSample> =
        (start until end).map { HrSample(deviceId = dev, ts = it, bpm = bpm) }

    private val still = 1
    private val asleep = SleepStager.bandStateAsleep

    // ── the pure function ───────────────────────────────────────────────────────────────────────────

    @Test fun onsetMovesToTheFirstSustainedAsleepStretchAndWakeToTheLast() {
        // Still run 00:58 → 07:33; strap asleep 02:18 → 06:54.
        val p = period(at(0, 58), at(7, 33))
        val b = band(p.start, p.end) { t -> if (t >= at(2, 18) && t < at(6, 54)) asleep else still }
        val a = SleepStager.bandAnchoredSpan(p, b)
        assertTrue(a.consulted)
        assertEquals(1.0, a.coverage, 1e-9)
        assertEquals(at(2, 18), a.span!!.start)
        assertEquals(at(6, 54), a.span!!.end)
        assertEquals(80 * 60L, a.onsetShiftS)
        assertEquals(39 * 60L, a.wakeShiftS)
    }

    @Test fun neverGrowsARunAndLeavesInteriorWakeToTheStager() {
        // Asleep from the first epoch to the last, with a 22-minute still stretch in the middle.
        val p = period(at(2, 18), at(6, 54))
        val b = band(p.start, p.end) { t -> if (t >= at(4, 0) && t < at(4, 22)) still else asleep }
        val a = SleepStager.bandAnchoredSpan(p, b)
        assertEquals(p, a.span)
        assertEquals(0L, a.onsetShiftS)
        assertEquals(0L, a.wakeShiftS)
    }

    @Test fun aBriefAsleepBlipBeforeTheRealOnsetDoesNotAnchorIt() {
        val p = period(at(0, 58), at(7, 33))
        // Two asleep epochs (1 min) at 01:30, then still until the real onset.
        val b = band(p.start, p.end) { t ->
            when {
                t >= at(1, 30) && t < at(1, 31) -> asleep
                t >= at(2, 18) && t < at(6, 54) -> asleep
                else -> still
            }
        }
        assertEquals(at(2, 18), SleepStager.bandAnchoredSpan(p, b).span!!.start)
    }

    @Test fun exactlyPersistEpochsOfAsleepIsEnoughOneFewerIsNot() {
        val p = period(at(1, 0), at(3, 0))
        val k = SleepStager.bandAnchorPersistEpochs
        val enough = band(p.start, p.end) { t -> if (t >= at(2, 0) && t < at(2, 0) + k * 30L) asleep else still }
        assertEquals(at(2, 0), SleepStager.bandAnchoredSpan(p, enough).span!!.start)
        val short = band(p.start, p.end) { t -> if (t >= at(2, 0) && t < at(2, 0) + (k - 1) * 30L) asleep else still }
        assertNull(SleepStager.bandAnchoredSpan(p, short).span)
    }

    @Test fun aRunTheStrapNeverScoredAsleepIsDropped() {
        val p = period(at(0, 58), at(2, 15))
        val a = SleepStager.bandAnchoredSpan(p, band(p.start, p.end) { still })
        assertTrue(a.consulted)
        assertNull(a.span)
    }

    @Test fun aSparseBandIsNotConsulted() {
        val p = period(at(0, 58), at(7, 33))
        // One sample every ten minutes — 5 % of epochs — says "still" throughout. Below coverage: untouched.
        val sparse = generateSequence(p.start) { it + 600L }.takeWhile { it < p.end }.map { it to still }.toList()
        val a = SleepStager.bandAnchoredSpan(p, sparse)
        assertFalse(a.consulted)
        assertEquals(p, a.span)
        assertTrue(a.coverage < SleepStager.bandAnchorMinCoverage)
    }

    @Test fun noBandAndTheFlagOffAreBothNoOps() {
        val p = period(at(0, 58), at(7, 33))
        assertEquals(p, SleepStager.bandAnchoredSpan(p, emptyList()).span)
        val b = band(p.start, p.end) { still }
        assertEquals(p, SleepStager.bandAnchoredSpan(p, b, enabled = false).span)
    }

    @Test fun edgesLandOnEpochBoundariesAndInsideTheRun() {
        val p = period(at(0, 58), at(7, 33))
        val b = band(p.start, p.end) { t -> if (t >= at(2, 18) && t < at(6, 54)) asleep else still }
        val span = SleepStager.bandAnchoredSpan(p, b).span!!
        assertEquals(0L, (span.start - p.start) % 30L)
        assertEquals(0L, (span.end - p.start) % 30L)
        assertTrue(span.start >= p.start && span.end <= p.end)
    }

    // ── end to end through detectSleep ─────────────────────────────────────────────────────────────

    @Test fun theNightOf21SeptScoresAsTheStrapSawItEndToEnd() {
        // One motionless, low-HR still run 00:58 → 07:33 — the shape that used to score 6 h 32.
        val start = at(0, 58)
        val end = at(7, 33)
        val grav = stillGravity(start, end)
        val hrS = hr(start, end, 50)
        val b = band(start, end) { t -> if (t >= at(2, 18) && t < at(6, 54)) asleep else still }
        val sessions = SleepStager.detectSleep(hr = hrS, gravity = grav, bandSleepState = b)
        assertEquals(1, sessions.size)
        // Run boundaries come from the gravity spine, so allow one epoch either side of the strap's edges.
        assertTrue("onset ${sessions[0].start} vs 02:18", kotlin.math.abs(sessions[0].start - at(2, 18)) <= 60L)
        assertTrue("wake ${sessions[0].end} vs 06:54", kotlin.math.abs(sessions[0].end - at(6, 54)) <= 60L)
        // Without the band the same run is the old 6 h 32 — the control that shows what the band changed.
        val unbanded = SleepStager.detectSleep(hr = hrS, gravity = grav)
        assertEquals(1, unbanded.size)
        assertTrue(unbanded[0].start <= at(1, 0))
        assertTrue(unbanded[0].end >= at(7, 30))
    }

    @Test fun anEveningOnTheSofaTheStrapCalledAwakeIsNoNightEndToEnd() {
        // A 77-minute still run the strap scored "still" throughout: previously a 77-minute "sleep".
        val start = at(0, 58)
        val end = at(2, 15)
        val sessions = SleepStager.detectSleep(
            hr = hr(start, end, 50), gravity = stillGravity(start, end),
            bandSleepState = band(start, end) { still },
        )
        assertTrue(sessions.isEmpty())
    }

    /** A 5/MG band as it really arrives: v18 records in bursts — one minute of per-second samples out of
     *  every four (~25 % of seconds), nothing in between. */
    private fun clumpedBand(start: Long, end: Long, stateAt: (Long) -> Int): List<Pair<Long, Int>> =
        (start until end).filter { ((it - start) / 60L) % 4L == 0L }.map { it to stateAt(it) }

    @Test fun aClumpedFiveMgBandIsStillConsulted() {
        // 22→23 Sept: Whoof's still run 23:06→06:56, strap asleep 23:58→06:48. 1.5.6 judged coverage per
        // 30 s epoch, saw ~25 %, and declined — the onset stayed at 23:06.
        val p = period(at(23, 6), at(30, 56))
        val b = clumpedBand(p.start, p.end) { t -> if (t >= at(23, 58) && t < at(30, 48)) asleep else still }
        assertTrue("5-min bucket coverage", SleepStager.bandBucketCoverage(p.start, p.end, b) >= SleepStager.bandAnchorMinCoverage)
        val a = SleepStager.bandAnchoredSpan(p, b)
        assertTrue(a.consulted)
        // Carry-forward between bursts means the edge lands within one burst period (4 min) of the strap's.
        assertTrue("onset ${a.span!!.start} vs 23:58", kotlin.math.abs(a.span!!.start - at(23, 58)) <= 4 * 60L)
        assertTrue("wake ${a.span!!.end} vs 06:48", kotlin.math.abs(a.span!!.end - at(30, 48)) <= 4 * 60L)
    }

    @Test fun aBandWithHalfHourHolesIsStillNotConsulted() {
        val p = period(at(23, 6), at(30, 56))
        val holey = (p.start until p.end).filter { ((it - p.start) / 60L) % 30L == 0L }.map { it to still }
        assertFalse(SleepStager.bandAnchoredSpan(p, holey).consulted)
    }

    @Test fun theDefaultIsOn() {
        assertTrue(SleepStager.bandAnchorEnabled)
        assertEquals(10, SleepStager.bandAnchorPersistEpochs)
        assertEquals(0.80, SleepStager.bandAnchorMinCoverage, 1e-9)
    }
}
