package com.noop.ui

import android.content.Context
import com.noop.BuildConfig
import com.noop.analytics.SleepCalibration
import com.noop.analytics.SleepTuning
import com.noop.data.WhoopRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * DailyReport.kt — Whoof: one file a day that says how detection is doing, for whoever is tuning it.
 *
 * The owner sends it from Calibration › Daily report (one tap, then the Drive app's own Save sheet). It is a
 * single text file, `whoof-report-YYYY-MM-DD.txt`, so it can be read without unpacking anything:
 *   1. the owner's note for the day (what WHOOP said, what looked wrong);
 *   2. the calibration self-assessment and every reference night, WHOOP vs Whoof, with edge errors;
 *   3. the nights Whoof detected over the last ten days;
 *   4. the detection diagnostics (bandState / sleep-gate / sleepCalibration / NO-NIGHT lines) pulled out of
 *      the strap log, so the answer to "why was this night wrong" is on the first screen;
 *   5. the complete strap log, unchanged, after a separator.
 */
object DailyReport {
    private const val PREFS = "whoof_daily_report"
    private const val KEY_NOTE = "note"
    private const val KEY_LAST_SENT = "last_sent_day"

    /** Log lines the tuning work reads first. */
    private val DIAG = Regex("bandState|sleep-gate|sleepCalibration|NO-NIGHT|sleepDetect|HISTORY_COMPLETE|previous app session", RegexOption.IGNORE_CASE)

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun note(c: Context): String = p(c).getString(KEY_NOTE, "").orEmpty()
    fun setNote(c: Context, v: String) = p(c).edit().putString(KEY_NOTE, v).apply()
    fun lastSentDay(c: Context): String? = p(c).getString(KEY_LAST_SENT, null)

    fun fileName(day: LocalDate): String = "whoof-report-$day.txt"

    suspend fun build(
        ctx: Context,
        repo: WhoopRepository,
        activeStrapId: String,
        logText: String,
        note: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = withContext(Dispatchers.Default) {
        val hm = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.UK)
        fun t(ts: Long?) = ts?.let { Instant.ofEpochSecond(it).atZone(zone).format(hm) } ?: "—"
        fun dur(s: Long) = String.format(Locale.US, "%dh%02d", s / 3600, (s % 3600) / 60)
        val now = System.currentTimeMillis()
        buildString {
            appendLine("WHOOF DAILY REPORT")
            appendLine("Generated: ${Instant.ofEpochMilli(now).atZone(zone)}")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})  zone=$zone")
            appendLine("Detection: ${SleepTuning.signature()}")
            appendLine()
            appendLine("== Owner note ==")
            appendLine(note.ifBlank { "(none)" })
            appendLine()

            appendLine("== Calibration ==")
            val report = runCatching { SleepCalibration.run(ctx, repo, activeStrapId, apply = false) }.getOrNull()
            if (report == null) {
                appendLine("calibration replay failed")
            } else {
                appendLine("grade=${report.grade.label} nights=${report.usableNights} best: band=${report.bestBandAnchor} " +
                    "hrMargin=${report.bestMarginBpm} mae=${report.bestMaeMin?.let { "%.1f".format(Locale.US, it) }} " +
                    "live mae=${report.previousMaeMin?.let { "%.1f".format(Locale.US, it) }}")
                for (n in report.nights) {
                    append("  WHOOP ${t(n.ref.bedTs)} → ${t(n.ref.wakeTs)}   Whoof ${t(n.detectedStart)} → ${t(n.detectedEnd)}")
                    if (n.skipped != null) appendLine("   skipped: ${n.skipped}")
                    else appendLine("   onset ${fmtErr(n.onsetErrMin)}  wake ${fmtErr(n.wakeErrMin)}")
                }
            }
            appendLine()

            appendLine("== Nights detected (last 10 days) ==")
            val to = now / 1000
            val sessions = runCatching { repo.sleepSessionsUnion(activeStrapId, to - 10 * 86_400L, to) }.getOrDefault(emptyList())
            if (sessions.isEmpty()) appendLine("(none)")
            for (s in sessions.sortedBy { it.startTs }) {
                appendLine("  ${t(s.startTs)} → ${t(s.endTs)}  ${dur(s.endTs - s.startTs)}" +
                    (s.efficiency?.let { "  eff=%.2f".format(Locale.US, it) } ?: "") +
                    "  src=${s.deviceId}")
            }
            appendLine()

            val lines = logText.lineSequence().toList()
            val diag = lines.filter { DIAG.containsMatchIn(it) }.takeLast(400)
            appendLine("== Detection diagnostics (${diag.size} lines from the strap log) ==")
            diag.forEach { appendLine(it) }
            appendLine()
            appendLine("== Full strap log (${lines.size} lines) ==")
            append(logText)
        }
    }

    private fun fmtErr(m: Double?) = m?.let { String.format(Locale.US, "%+.0f min", it) } ?: "—"

    /** Build today's report and hand it to Drive (the registered folder, else the Drive app's Save sheet). */
    suspend fun send(ctx: Context, repo: WhoopRepository, activeStrapId: String, logText: String) {
        val day = LocalDate.now()
        val text = build(ctx, repo, activeStrapId, logText, note(ctx))
        val file = withContext(Dispatchers.IO) {
            File(File(ctx.cacheDir, "logs").apply { mkdirs() }, fileName(day)).apply { writeText(text) }
        }
        DriveExport.saveFile(ctx, file, "text/plain")
        p(ctx).edit().putString(KEY_LAST_SENT, day.toString()).apply()
    }
}
