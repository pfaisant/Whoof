package com.noop.analytics

import com.noop.data.GravitySample
import com.noop.data.HrSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Whoof heart-rate edge trim ([SleepStager.hrEdgeTrimmedSpan]) and the calibration scoring
 * ([SleepCalibration]) against the night that motivated them.
 *
 * 22→23 Sept 2026: the wrist went still at 23:06, WHOOP (same strap) placed sleep at 23:58→06:48, and
 * Whoof reported 23:06→06:56. No band is used here — this is the path for nights the strap's own
 * sleep band cannot place. Lying awake reads ~55 bpm; asleep ~48.
 */
class SleepCalibrationTest {
    private val dev = "test"
    private val refMidnight = 1_749_513_600L
    private fun at(hour: Int, minute: Int = 0): Long = refMidnight + hour * 3_600L + minute * 60L
    private fun stillGravity(start: Long, end: Long) =
        (start until end).map { GravitySample(deviceId = dev, ts = it, x = 0.0, y = 0.0, z = 1.0) }

    /** Awake-in-bed 55 bpm, asleep 48 with a small ripple, awake again 56. */
    private fun nightHr(start: Long, sleepOn: Long, sleepOff: Long, end: Long): List<HrSample> =
        (start until end).map { t ->
            val bpm = when {
                t < sleepOn -> 55
                t < sleepOff -> 48 + ((t / 97) % 3).toInt()
                else -> 56
            }
            HrSample(deviceId = dev, ts = t, bpm = bpm)
        }

    private val start = at(23, 6)
    private val bed = at(23, 58)
    private val wake = at(30, 48)
    private val end = at(30, 56)

    @Test fun marginOffIsANoOp() {
        val p = SleepStager.Period("sleep", start, end)
        assertEquals(p, SleepStager.hrEdgeTrimmedSpan(p, nightHr(start, bed, wake, end), 0))
    }

    @Test fun theHrRuleFindsWhoopsEdgesWithoutABand() {
        val p = SleepStager.Period("sleep", start, end)
        val t = SleepStager.hrEdgeTrimmedSpan(p, nightHr(start, bed, wake, end), 4)
        assertTrue("onset ${(t.start - bed) / 60} min from 23:58", abs(t.start - bed) <= 3 * 60L)
        assertTrue("wake ${(t.end - wake) / 60} min from 06:48", abs(t.end - wake) <= 3 * 60L)
    }

    @Test fun neverGrowsARunAndNeverDeletesOne() {
        val p = SleepStager.Period("sleep", start, end)
        val flatAwake = (start until end).map { HrSample(deviceId = dev, ts = it, bpm = 62) }
        val t = SleepStager.hrEdgeTrimmedSpan(p, flatAwake, 4)
        // A flat trace settles everywhere, so nothing is trimmed; and nothing ever extends past the run.
        assertTrue(t.start >= p.start && t.end <= p.end)
        assertTrue(t.end - t.start > 0)
    }

    @Test fun endToEndTheCalibratedMarginReproducesWhoopAndTheDefaultDoesNot() {
        val hr = nightHr(start, bed, wake, end)
        val grav = stillGravity(start, end)
        val ref = SleepCalibration.ReferenceNight("t", bed, wake)
        val off = SleepCalibration.scoreNight(ref,
            SleepStager.detectSleep(hr = hr, gravity = grav, edges = SleepStager.EdgeParams(true, 0)))
        val on = SleepCalibration.scoreNight(ref,
            SleepStager.detectSleep(hr = hr, gravity = grav, edges = SleepStager.EdgeParams(true, 4)))
        assertTrue("default onset error ${off.onsetErrMin}", off.onsetErrMin!! <= -45.0)   // the 52-min-early bug
        assertTrue("calibrated mean error ${on.meanErrMin}", on.meanErrMin!! <= 5.0)
    }

    @Test fun theCalibratorsSearchPicksAWorkingMargin() {
        val hr = nightHr(start, bed, wake, end)
        val grav = stillGravity(start, end)
        val ref = SleepCalibration.ReferenceNight("t", bed, wake)
        val best = SleepCalibration.MARGINS.minByOrNull { m ->
            SleepCalibration.meanError(listOf(SleepCalibration.scoreNight(ref,
                SleepStager.detectSleep(hr = hr, gravity = grav, edges = SleepStager.EdgeParams(true, m)))))!!
        }!!
        assertTrue("picked $best", best in 2..6)
    }

    @Test fun aMissedNightIsPenalisedNotIgnored() {
        val ref = SleepCalibration.ReferenceNight("t", bed, wake)
        val missed = SleepCalibration.scoreNight(ref, emptyList())
        assertNull(missed.meanErrMin)
        assertEquals(SleepCalibration.MISSED_NIGHT_PENALTY_MIN, SleepCalibration.meanError(listOf(missed))!!, 0.0)
    }

    @Test fun theSelfAssessmentNeedsEvidence() {
        assertEquals(SleepCalibration.Grade.NOT_ENOUGH, SleepCalibration.gradeFor(2.0, 1))
        assertEquals(SleepCalibration.Grade.FAIR, SleepCalibration.gradeFor(4.0, 2))   // good error, thin evidence
        assertEquals(SleepCalibration.Grade.GOOD, SleepCalibration.gradeFor(8.0, 3))
        assertEquals(SleepCalibration.Grade.FAIR, SleepCalibration.gradeFor(18.0, 5))
        assertEquals(SleepCalibration.Grade.POOR, SleepCalibration.gradeFor(40.0, 5))
    }

    private fun abs(v: Long) = kotlin.math.abs(v)
}
