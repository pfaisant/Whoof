package com.noop.analytics

import android.content.Context

/**
 * Whoof: user-tunable sleep-detection knobs, persisted in prefs and pushed into the stager's runtime
 * vars at app start and on every change. Defaults are upstream's constants.
 */
object SleepTuning {
    private const val PREFS = "whoof_sleep_tuning"
    const val KEY_ONSET_MULT = "onsetMult"          // hrSleepBaselineMult
    const val KEY_ONSET_EPOCHS = "onsetEpochs"      // onsetPersistEpochs
    const val KEY_WAKE_BRIDGE_MIN = "wakeBridgeMin" // GAP_BRIDGE_MAX_MIN
    const val KEY_SPARSE_BRIDGE_MIN = "sparseBridgeMin" // sparseBridgeGapMin
    const val KEY_STRAIN_SCALE = "strainScale"          // StrainScorer.userDenominatorScale
    const val KEY_HR_EDGE_MARGIN = "hrEdgeMargin"       // SleepStager.hrEdgeMarginBpm (calibrated)
    const val KEY_BAND_ANCHOR = "bandAnchor"            // SleepStager.bandAnchorEnabled (calibrated)

    const val DEFAULT_ONSET_MULT = 1.05
    const val DEFAULT_ONSET_EPOCHS = 3
    const val DEFAULT_WAKE_BRIDGE_MIN = 60
    const val DEFAULT_SPARSE_BRIDGE_MIN = 90
    const val DEFAULT_STRAIN_SCALE = 1.0
    const val DEFAULT_HR_EDGE_MARGIN = 0
    const val DEFAULT_BAND_ANCHOR = true

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun onsetMult(ctx: Context): Double = prefs(ctx).getFloat(KEY_ONSET_MULT, DEFAULT_ONSET_MULT.toFloat()).toDouble()
    fun onsetEpochs(ctx: Context): Int = prefs(ctx).getInt(KEY_ONSET_EPOCHS, DEFAULT_ONSET_EPOCHS)
    fun wakeBridgeMin(ctx: Context): Int = prefs(ctx).getInt(KEY_WAKE_BRIDGE_MIN, DEFAULT_WAKE_BRIDGE_MIN)
    fun sparseBridgeMin(ctx: Context): Int = prefs(ctx).getInt(KEY_SPARSE_BRIDGE_MIN, DEFAULT_SPARSE_BRIDGE_MIN)
    fun hrEdgeMargin(ctx: Context): Int = prefs(ctx).getInt(KEY_HR_EDGE_MARGIN, DEFAULT_HR_EDGE_MARGIN)
    fun bandAnchor(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_BAND_ANCHOR, DEFAULT_BAND_ANCHOR)
    fun setEdges(ctx: Context, bandAnchor: Boolean, hrEdgeMargin: Int) {
        prefs(ctx).edit().putBoolean(KEY_BAND_ANCHOR, bandAnchor).putInt(KEY_HR_EDGE_MARGIN, hrEdgeMargin).apply()
        apply(ctx)
    }

    /** Every knob the stager reads, as one string: the day-scan cache folds it into its config key so a
     *  tuning change re-scores the window instead of replaying nights scored under the old values. */
    fun signature(): String = listOf(
        SleepStager.hrSleepBaselineMult, SleepStager.onsetPersistEpochs, SleepStager.sparseBridgeGapMin,
        SleepStageTotals.GAP_BRIDGE_MAX_MIN, SleepStager.bandAnchorEnabled, SleepStager.hrEdgeMarginBpm,
    ).joinToString(",")

    fun strainScale(ctx: Context): Double = prefs(ctx).getFloat(KEY_STRAIN_SCALE, DEFAULT_STRAIN_SCALE.toFloat()).toDouble()

    fun set(ctx: Context, onsetMult: Double? = null, onsetEpochs: Int? = null, wakeBridgeMin: Int? = null, sparseBridgeMin: Int? = null, strainScale: Double? = null) {
        prefs(ctx).edit().apply {
            onsetMult?.let { putFloat(KEY_ONSET_MULT, it.toFloat()) }
            onsetEpochs?.let { putInt(KEY_ONSET_EPOCHS, it) }
            wakeBridgeMin?.let { putInt(KEY_WAKE_BRIDGE_MIN, it) }
            sparseBridgeMin?.let { putInt(KEY_SPARSE_BRIDGE_MIN, it) }
            strainScale?.let { putFloat(KEY_STRAIN_SCALE, it.toFloat()) }
        }.apply()
        apply(ctx)
    }

    fun reset(ctx: Context) { prefs(ctx).edit().clear().apply(); apply(ctx) }

    /** Push the persisted values into the stager. Call at app start and after [set]. */
    fun apply(ctx: Context) {
        SleepStager.hrSleepBaselineMult = onsetMult(ctx).coerceIn(1.0, 1.3)
        SleepStager.onsetPersistEpochs = onsetEpochs(ctx).coerceIn(1, 8)
        SleepStager.sparseBridgeGapMin = sparseBridgeMin(ctx).coerceIn(15, 180)
        SleepStageTotals.GAP_BRIDGE_MAX_MIN = wakeBridgeMin(ctx).coerceIn(15, 120)
        StrainScorer.userDenominatorScale = strainScale(ctx).coerceIn(0.3, 4.0)
        SleepStager.hrEdgeMarginBpm = hrEdgeMargin(ctx).coerceIn(0, 20)
        SleepStager.bandAnchorEnabled = bandAnchor(ctx)
    }

    fun isDefault(ctx: Context): Boolean =
        onsetMult(ctx) == DEFAULT_ONSET_MULT && onsetEpochs(ctx) == DEFAULT_ONSET_EPOCHS &&
            wakeBridgeMin(ctx) == DEFAULT_WAKE_BRIDGE_MIN && sparseBridgeMin(ctx) == DEFAULT_SPARSE_BRIDGE_MIN
}
