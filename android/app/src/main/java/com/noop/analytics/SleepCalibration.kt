package com.noop.analytics

import android.content.Context
import com.noop.data.WhoopRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * SleepCalibration.kt — Whoof: fit sleep detection to the wearer's own ground truth.
 *
 * WHY. Whoof's detector places a night's edges from wrist stillness plus heart rate against a day
 * median. For a fit wearer lying still before sleep that reads as sleep: on 21→22 Sept the onset was
 * 80 min early, on 22→23 Sept 52 min early. The strap's own sleep band fixes it where it is present, and
 * 1.5.8 made it usable on a 5/MG — but whether the band is dense enough on THIS strap, and how far a
 * heart-rate rule should trim when it is not, are questions only the wearer's nights can answer. WHOOP's
 * app shows the same strap's bed and wake times; those are the reference.
 *
 * WHAT. A reference night is WHOOP's bed and wake time. [run] replays [SleepStager.detectSleep] over the
 * night's stored strap streams for every candidate [SleepStager.EdgeParams] (band anchor on/off × a grid
 * of HR edge margins), measures each candidate's onset and wake error against the reference, and picks
 * the candidate with the lowest mean error. Nothing is guessed: the chosen setting is the one that
 * reproduced the wearer's own nights best, and the [Report] says how well — and how much evidence
 * that rests on — so the app can grade its own calibration instead of asserting it.
 *
 * Replays pass the candidate explicitly (EdgeParams), never by mutating the stager's globals, so a live
 * scoring pass on another thread is never scored under a half-applied candidate.
 */
object SleepCalibration {
    private const val PREFS = "whoof_sleep_calibration"
    private const val KEY_NIGHTS = "nights"
    private const val KEY_REPORT = "report"
    private const val KEY_SEEDED = "seeded_v1"

    /** HR edge margins tried (bpm above the run's own sleep level). 0 = the rule off. */
    val MARGINS = listOf(0, 2, 3, 4, 5, 6, 8, 10, 12)

    /** A night the detector found nothing for costs this much, so "no night" never wins by default. */
    const val MISSED_NIGHT_PENALTY_MIN = 180.0

    /** A new setting must beat the current one by at least this many minutes of mean error to be applied. */
    const val APPLY_MIN_GAIN_MIN = 3.0

    data class ReferenceNight(
        val id: String,
        /** WHOOP's bed time and wake time (unix seconds). */
        val bedTs: Long,
        val wakeTs: Long,
        /** WHOOP's "hours of sleep" in minutes, when known — reported, not fitted. */
        val asleepMin: Int? = null,
        val source: String = "manual",
    )

    data class NightResult(
        val ref: ReferenceNight,
        val detectedStart: Long?,
        val detectedEnd: Long?,
        val onsetErrMin: Double?,
        val wakeErrMin: Double?,
        /** Why a night could not be scored (no strap data on the phone for that window). */
        val skipped: String? = null,
    ) {
        val meanErrMin: Double? get() =
            if (onsetErrMin == null || wakeErrMin == null) null else (abs(onsetErrMin) + abs(wakeErrMin)) / 2.0
    }

    enum class Grade(val label: String) {
        NOT_ENOUGH("Needs more nights"), GOOD("Good"), FAIR("Fair"), POOR("Poor")
    }

    data class Report(
        val ranAtMs: Long,
        val usableNights: Int,
        val bestBandAnchor: Boolean,
        val bestMarginBpm: Int,
        val bestMaeMin: Double?,
        /** The same nights scored with the settings that were live before this run. */
        val previousMaeMin: Double?,
        val applied: Boolean,
        val nights: List<NightResult>,
    ) {
        val grade: Grade get() = gradeFor(bestMaeMin, usableNights)
    }

    /** Self-assessment. Grades need evidence: one night proves nothing, so < 2 usable nights is never graded,
     *  and "Good" needs three. Thresholds are mean absolute edge error in minutes. */
    fun gradeFor(maeMin: Double?, usableNights: Int): Grade = when {
        maeMin == null || usableNights < 2 -> Grade.NOT_ENOUGH
        maeMin <= 10.0 && usableNights >= 3 -> Grade.GOOD
        maeMin <= 20.0 -> Grade.FAIR
        else -> Grade.POOR
    }

    // ── persistence ─────────────────────────────────────────────────────────────────────────────────

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun nights(ctx: Context): List<ReferenceNight> {
        seedIfNeeded(ctx)
        val arr = runCatching { JSONArray(prefs(ctx).getString(KEY_NIGHTS, "[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            ReferenceNight(
                id = o.optString("id"), bedTs = o.optLong("bed"), wakeTs = o.optLong("wake"),
                asleepMin = if (o.has("asleep")) o.optInt("asleep") else null,
                source = o.optString("source", "manual"),
            )
        }.filter { it.wakeTs > it.bedTs }.sortedByDescending { it.wakeTs }
    }

    private fun saveNights(ctx: Context, nights: List<ReferenceNight>) {
        val arr = JSONArray()
        for (n in nights) arr.put(JSONObject().apply {
            put("id", n.id); put("bed", n.bedTs); put("wake", n.wakeTs); put("source", n.source)
            n.asleepMin?.let { put("asleep", it) }
        })
        prefs(ctx).edit().putString(KEY_NIGHTS, arr.toString()).apply()
    }

    fun addNight(ctx: Context, night: ReferenceNight) {
        // One reference per wake day: a re-entry replaces the earlier one rather than double-weighting it.
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochSecond(night.wakeTs).atZone(zone).toLocalDate()
        val kept = nights(ctx).filter { Instant.ofEpochSecond(it.wakeTs).atZone(zone).toLocalDate() != day }
        saveNights(ctx, kept + night)
    }

    fun removeNight(ctx: Context, id: String) = saveNights(ctx, nights(ctx).filter { it.id != id })

    /**
     * The two nights the owner reported from WHOOP's app (screenshots, 22 and 23 Sept 2026), seeded once
     * so the first calibration has evidence without anyone typing. Europe/London; deletable like any other.
     */
    private fun seedIfNeeded(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean(KEY_SEEDED, false)) return
        val z = ZoneId.of("Europe/London")
        fun ts(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
            java.time.LocalDateTime.of(y, mo, d, h, mi).atZone(z).toEpochSecond()
        val seed = listOf(
            ReferenceNight("whoop-2026-09-22", ts(2026, 9, 22, 2, 18), ts(2026, 9, 22, 6, 54), 4 * 60 + 14,
                "WHOOP app, owner screenshot 22 Sep 2026"),
            ReferenceNight("whoop-2026-09-23", ts(2026, 9, 22, 23, 58), ts(2026, 9, 23, 6, 48), 6 * 60 + 24,
                "WHOOP app, owner screenshot 23 Sep 2026"),
        )
        val arr = JSONArray(p.getString(KEY_NIGHTS, "[]"))
        for (n in seed) arr.put(JSONObject().apply {
            put("id", n.id); put("bed", n.bedTs); put("wake", n.wakeTs); put("source", n.source)
            n.asleepMin?.let { put("asleep", it) }
        })
        p.edit().putString(KEY_NIGHTS, arr.toString()).putBoolean(KEY_SEEDED, true).apply()
    }

    fun lastReport(ctx: Context): Report? = runCatching {
        val o = JSONObject(prefs(ctx).getString(KEY_REPORT, null) ?: return null)
        val refs = nights(ctx).associateBy { it.id }
        val arr = o.getJSONArray("nights")
        val list = (0 until arr.length()).mapNotNull { i ->
            val n = arr.getJSONObject(i)
            val ref = refs[n.getString("id")] ?: return@mapNotNull null
            NightResult(
                ref,
                n.optLong("start").takeIf { n.has("start") },
                n.optLong("end").takeIf { n.has("end") },
                n.optDouble("onset").takeIf { n.has("onset") },
                n.optDouble("wake").takeIf { n.has("wake") },
                n.optString("skipped").takeIf { n.has("skipped") },
            )
        }
        Report(
            o.getLong("ranAt"), o.getInt("usable"), o.getBoolean("band"), o.getInt("margin"),
            o.optDouble("mae").takeIf { o.has("mae") }, o.optDouble("prev").takeIf { o.has("prev") },
            o.getBoolean("applied"), list,
        )
    }.getOrNull()

    private fun saveReport(ctx: Context, r: Report) {
        val arr = JSONArray()
        for (n in r.nights) arr.put(JSONObject().apply {
            put("id", n.ref.id)
            n.detectedStart?.let { put("start", it) }; n.detectedEnd?.let { put("end", it) }
            n.onsetErrMin?.let { put("onset", it) }; n.wakeErrMin?.let { put("wake", it) }
            n.skipped?.let { put("skipped", it) }
        })
        val o = JSONObject().apply {
            put("ranAt", r.ranAtMs); put("usable", r.usableNights); put("band", r.bestBandAnchor)
            put("margin", r.bestMarginBpm); r.bestMaeMin?.let { put("mae", it) }
            r.previousMaeMin?.let { put("prev", it) }; put("applied", r.applied); put("nights", arr)
        }
        prefs(ctx).edit().putString(KEY_REPORT, o.toString()).apply()
    }

    // ── scoring (pure) ──────────────────────────────────────────────────────────────────────────────

    /** Pick the detected session that overlaps the reference most, and measure its edge errors in minutes
     *  (positive = Whoof later than WHOOP). Null when nothing overlaps: that night was missed. */
    fun scoreNight(ref: ReferenceNight, sessions: List<DetectedSleep>): NightResult {
        val best = sessions.maxByOrNull { overlap(it.start, it.end, ref.bedTs, ref.wakeTs) }
            ?.takeIf { overlap(it.start, it.end, ref.bedTs, ref.wakeTs) > 0 }
            ?: return NightResult(ref, null, null, null, null)
        return NightResult(
            ref, best.start, best.end,
            onsetErrMin = (best.start - ref.bedTs) / 60.0,
            wakeErrMin = (best.end - ref.wakeTs) / 60.0,
        )
    }

    private fun overlap(a0: Long, a1: Long, b0: Long, b1: Long): Long = maxOf(0L, minOf(a1, b1) - maxOf(a0, b0))

    /** Mean edge error over nights with data; a missed night costs [MISSED_NIGHT_PENALTY_MIN]. */
    fun meanError(results: List<NightResult>): Double? {
        val usable = results.filter { it.skipped == null }
        if (usable.isEmpty()) return null
        return usable.map { it.meanErrMin ?: MISSED_NIGHT_PENALTY_MIN }.average()
    }

    // ── the run ─────────────────────────────────────────────────────────────────────────────────────

    /** The streams one reference night is replayed over. */
    class NightStreams(
        val hr: List<com.noop.data.HrSample>,
        val gravity: List<com.noop.data.GravitySample>,
        val band: List<Pair<Long, Int>>,
        val wristOff: List<Pair<Long, Long>> = emptyList(),
    )

    /**
     * Read each reference night's strap streams, replay every candidate, pick the best, and — when it beats
     * the live setting by [APPLY_MIN_GAIN_MIN] on at least two nights — write it through [SleepTuning].
     * Returns the report; the caller re-scores the window so the new setting reaches every screen.
     */
    suspend fun run(ctx: Context, repo: WhoopRepository, activeStrapId: String, apply: Boolean = true): Report =
        withContext(Dispatchers.Default) {
            val refs = nights(ctx)
            val streams = refs.associate { it.id to readStreams(repo, activeStrapId, it) }
            val live = SleepStager.currentEdgeParams()
            fun evaluate(edges: SleepStager.EdgeParams): List<NightResult> = refs.map { ref ->
                val s = streams[ref.id]
                if (s == null || s.hr.size < 200 || s.gravity.size < 2) {
                    NightResult(ref, null, null, null, null, skipped = "No strap data on this phone for that night")
                } else {
                    val tz = ZoneId.systemDefault().rules.getOffset(Instant.ofEpochSecond(ref.bedTs)).totalSeconds.toLong()
                    scoreNight(ref, SleepStager.detectSleep(
                        hr = s.hr, gravity = s.gravity, tzOffsetSeconds = tz,
                        bandSleepState = s.band, wristOff = s.wristOff, edges = edges,
                    ))
                }
            }
            val previous = evaluate(live)
            var best = live
            var bestResults = previous
            var bestMae = meanError(previous)
            for (band in listOf(true, false)) for (m in MARGINS) {
                val cand = SleepStager.EdgeParams(band, m)
                if (cand == live) continue
                val res = evaluate(cand)
                val mae = meanError(res) ?: continue
                // Strictly better, or equal and simpler (band on, smaller margin): never churn on a tie.
                val cur = bestMae
                if (cur == null || mae < cur - 0.01) { best = cand; bestResults = res; bestMae = mae }
            }
            val usable = bestResults.count { it.skipped == null }
            val prevMae = meanError(previous)
            val gain = if (prevMae != null && bestMae != null) prevMae - bestMae else 0.0
            val doApply = apply && best != live && usable >= 2 && gain >= APPLY_MIN_GAIN_MIN
            if (doApply) SleepTuning.setEdges(ctx, best.bandAnchor, best.hrMarginBpm)
            val chosen = if (doApply) best else live
            val chosenResults = if (doApply) bestResults else previous
            val report = Report(
                ranAtMs = System.currentTimeMillis(), usableNights = usable,
                bestBandAnchor = chosen.bandAnchor, bestMarginBpm = chosen.hrMarginBpm,
                bestMaeMin = meanError(chosenResults), previousMaeMin = prevMae,
                applied = doApply, nights = chosenResults,
            )
            saveReport(ctx, report)
            report
        }

    private suspend fun readStreams(repo: WhoopRepository, activeStrapId: String, ref: ReferenceNight): NightStreams? {
        // EXACTLY the window the scoring pass reads for the wake day: local midnight − 30 h through the next
        // local midnight. Not a convenience window: detectSleep's HR band is the median of the WHOLE
        // window, so a narrower read around the night has a lower median, a stricter band and different
        // runs. 1.5.9 read bed − 6 h … wake + 4 h and replayed a 64-min error for nights the app itself
        // scored at ~45 min — the replay was not the detector, so nothing it chose could be trusted.
        val zone = ZoneId.systemDefault()
        val wakeDay = Instant.ofEpochSecond(ref.wakeTs).atZone(zone).toLocalDate()
        val dayStart = wakeDay.atStartOfDay(zone).toEpochSecond()
        val from = dayStart - StreamReadCap.LOOKBACK_SECONDS
        val to = minOf(dayStart + 86_400L, System.currentTimeMillis() / 1000)
        val hr = runCatching { repo.hrSamplesUnion(activeStrapId, from, to, StreamReadCap.HR) }.getOrDefault(emptyList())
        if (hr.isEmpty()) return null
        val ids = runCatching { repo.whoopSourceIds(activeStrapId) }.getOrDefault(listOf(activeStrapId))
            .ifEmpty { listOf(activeStrapId) }
        // Motion and band come from whichever strap id actually banked them for this window.
        var gravity = emptyList<com.noop.data.GravitySample>()
        var band = emptyList<Pair<Long, Int>>()
        for (id in ids) {
            val g = runCatching { repo.gravitySamplesForDevice(id, from, to, StreamReadCap.GRAVITY) }.getOrDefault(emptyList())
            if (g.size > gravity.size) gravity = g
            val b = runCatching { repo.sleepStateSamples(id, from, to).map { it.ts to it.state } }.getOrDefault(emptyList())
            if (b.size > band.size) band = b
        }
        // The off-wrist gate reads these; the engine passes them, so the replay must too.
        var wristOff = emptyList<Pair<Long, Long>>()
        for (id in ids) {
            val w = runCatching { AnalyticsEngine.offWristIntervals(repo.events(id, from, to, 100_000), to) }
                .getOrDefault(emptyList())
            if (w.size > wristOff.size) wristOff = w
        }
        return NightStreams(hr, gravity, band, wristOff)
    }

    /** Minutes, signed, as "+12 min" / "−4 min" / "0 min". */
    fun signedMinutes(v: Double): String {
        val r = v.roundToInt()
        return when {
            r > 0 -> "+$r min"
            r < 0 -> "−${-r} min"
            else -> "0 min"
        }
    }
}
