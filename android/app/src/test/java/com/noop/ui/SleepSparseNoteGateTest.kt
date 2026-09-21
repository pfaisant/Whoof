package com.noop.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whoof port of upstream #2324's gate. The fork owns its Sleep screen, so the rule lives in
 * [stageSparseNoteApplies] and is wired into `SleepCaveatIcon` rather than cherry-picked.
 */
class SleepSparseNoteGateTest {

    @Test
    fun notSparse_neverWarns() {
        assertFalse(stageSparseNoteApplies(stagingSparse = false, asleepMin = 10.0))
        assertFalse(stageSparseNoteApplies(stagingSparse = false, asleepMin = 0.0))
    }

    @Test
    fun sparseButFullNight_doesNotWarn() {
        // The regression this port exists for: one long motion dropout on a complete night.
        assertFalse(stageSparseNoteApplies(stagingSparse = true, asleepMin = 8.5 * 60))
        assertFalse(stageSparseNoteApplies(stagingSparse = true, asleepMin = 8.0 * 60))
    }

    @Test
    fun sparseAndShortNight_warns() {
        assertTrue(stageSparseNoteApplies(stagingSparse = true, asleepMin = 4.0 * 60))
    }

    @Test
    fun sparseAndNothingStaged_warns() {
        // Zero asleep is the strongest form of the collapse the note explains, not an exemption.
        assertTrue(stageSparseNoteApplies(stagingSparse = true, asleepMin = 0.0))
    }

    @Test
    fun needIsAParameter_soAPersonalisedNeedCanBeThreadedLater() {
        assertFalse(stageSparseNoteApplies(true, asleepMin = 6.5 * 60, needHours = 6.0))
        assertTrue(stageSparseNoteApplies(true, asleepMin = 6.5 * 60, needHours = 7.0))
    }
}
