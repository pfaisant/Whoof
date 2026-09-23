package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.noop.analytics.Baselines
import com.noop.analytics.SleepCalibration
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * CalibrationScreen.kt — Whoof: every calibration in one place, with its progress and its own grade.
 *
 * Sleep detection is the one that matters and is the one that needs back-and-forth: the wearer enters
 * WHOOP's bed and wake time for a night, Whoof replays its detector over that night, and the grade says
 * how close it now gets. The HRV baseline and the steps estimate have their own progress rows below.
 * No explanatory captions: every line on this screen is either a number or an action.
 */

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

private fun hm(ts: Long, zone: ZoneId): String = Instant.ofEpochSecond(ts).atZone(zone).format(timeFmt)

@Composable
fun CalibrationScreen(vm: AppViewModel, onOpenStepsCalibration: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val report by vm.sleepCalibration.collectAsState()
    val running by vm.sleepCalibrationRunning.collectAsState()
    var rev by remember { mutableIntStateOf(0) }
    val nights = remember(rev, report) { SleepCalibration.nights(context) }
    val zone = ZoneId.systemDefault()

    ScreenScaffold(title = "Calibration") {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            SleepGradeHero(report = report, running = running, onRun = { vm.calibrateSleepAsync() })

            DeviceShelf("Reference nights", onSky = false) {
                if (nights.isEmpty()) {
                    DeviceRow(title = "No nights yet")
                }
                val byId = report?.nights?.associateBy { it.ref.id }.orEmpty()
                nights.forEachIndexed { i, n ->
                    if (i > 0) DeviceDivider()
                    ReferenceNightRow(
                        night = n,
                        result = byId[n.id],
                        zone = zone,
                        onDelete = { SleepCalibration.removeNight(context, n.id); rev++ },
                    )
                }
                DeviceDivider()
                AddReferenceNight(onAdd = { night ->
                    SleepCalibration.addNight(context, night); rev++
                    vm.calibrateSleepAsync()
                })
            }

            OtherCalibrations(vm = vm, onOpenStepsCalibration = onOpenStepsCalibration)

            DailyReportShelf(vm)

            DeviceShelf("Logs", onSky = false) {
                DeviceRow(
                    title = "Share strap log",
                    onClick = { scope.launch { LogExport.shareStrapLog(context, vm.ble.exportLogText()) } },
                    control = { Icon(Icons.Filled.Upload, null, tint = Palette.accent, modifier = Modifier.size(18.dp)) },
                )
                DeviceDivider()
                DeviceRow(
                    title = "Save to Google Drive",
                    onClick = { scope.launch { DriveExport.saveStrapLog(context, vm.ble.exportLogText()) } },
                    control = { Text("Drive", style = NoopType.captionNumber, color = Palette.accent) },
                )
            }
        }
    }
}

/** The self-assessment: grade, mean edge error, how many nights it rests on, and what it applied. */
@Composable
private fun SleepGradeHero(report: SleepCalibration.Report?, running: Boolean, onRun: () -> Unit) {
    val grade = report?.grade ?: SleepCalibration.Grade.NOT_ENOUGH
    val tone = when (grade) {
        SleepCalibration.Grade.GOOD -> StrandTone.Positive
        SleepCalibration.Grade.FAIR -> StrandTone.Warning
        SleepCalibration.Grade.POOR -> StrandTone.Critical
        SleepCalibration.Grade.NOT_ENOUGH -> StrandTone.Neutral
    }
    NoopCard(padding = 20.dp, tint = Palette.accent) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Sleep detection", style = NoopType.headline, color = Palette.textPrimary, modifier = Modifier.weight(1f))
                StatePill(title = if (running) "Calibrating…" else grade.label, tone = tone, pulsing = running)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Metric(
                    value = report?.bestMaeMin?.let { "${it.toInt()} min" } ?: "—",
                    label = "Avg error",
                )
                Metric(value = "${report?.usableNights ?: 0}", label = "Nights")
                Metric(
                    value = report?.previousMaeMin?.let { prev ->
                        report.bestMaeMin?.let { best -> "${(prev - best).toInt()} min" }
                    } ?: "—",
                    label = "Gained",
                )
            }
            QualityBar(maeMin = report?.bestMaeMin)
            report?.let {
                Text(
                    buildString {
                        append(if (it.bestBandAnchor) "Strap band on" else "Strap band off")
                        append(" · ")
                        append(if (it.bestMarginBpm > 0) "HR edge +${it.bestMarginBpm} bpm" else "HR edge off")
                    },
                    style = NoopType.captionNumber, color = Palette.textSecondary,
                )
            }
            NoopButton(
                text = if (running) "Calibrating…" else "Recalibrate",
                leadingIcon = Icons.Filled.Refresh,
                kind = NoopButtonKind.Primary,
                enabled = !running,
                onClick = onRun,
            )
        }
    }
}

@Composable
private fun Metric(value: String, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = NoopType.number(22f, weight = FontWeight.Bold), color = Palette.textPrimary)
        Text(label.uppercase(), style = NoopType.overline, color = Palette.textTertiary)
    }
}

/** 0–60 min of mean error drawn as a bar: full and green at 0, empty and red at an hour. */
@Composable
private fun QualityBar(maeMin: Double?) {
    val quality = maeMin?.let { (1.0 - (it / 60.0)).coerceIn(0.0, 1.0) } ?: 0.0
    val color = when {
        maeMin == null -> Palette.textTertiary
        maeMin <= 10 -> StrandTone.Positive.color
        maeMin <= 20 -> StrandTone.Warning.color
        else -> StrandTone.Critical.color
    }
    val shape = RoundedCornerShape(50)
    Box(Modifier.fillMaxWidth().height(8.dp).clip(shape).background(Palette.surfaceInset)) {
        Box(Modifier.fillMaxWidth(quality.toFloat().coerceAtLeast(0.02f)).height(8.dp).clip(shape).background(color))
    }
}

/** One night: WHOOP's times beside Whoof's, and the two edge errors. */
@Composable
private fun ReferenceNightRow(
    night: SleepCalibration.ReferenceNight,
    result: SleepCalibration.NightResult?,
    zone: ZoneId,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                Instant.ofEpochSecond(night.wakeTs).atZone(zone).toLocalDate().format(dayFmt),
                style = NoopType.body, color = Palette.textPrimary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TimesPair("WHOOP", "${hm(night.bedTs, zone)}–${hm(night.wakeTs, zone)}", Palette.textSecondary)
                val whoof = if (result?.detectedStart != null && result.detectedEnd != null)
                    "${hm(result.detectedStart, zone)}–${hm(result.detectedEnd, zone)}"
                else if (result?.skipped != null) "no data" else "—"
                TimesPair("WHOOF", whoof, Palette.textPrimary)
            }
        }
        result?.meanErrMin?.let { err ->
            val c = when {
                err <= 10 -> StrandTone.Positive.color
                err <= 20 -> StrandTone.Warning.color
                else -> StrandTone.Critical.color
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("±${err.toInt()}", style = NoopType.number(17f, weight = FontWeight.Bold), color = c)
                Text("MIN", style = NoopType.overline, color = Palette.textTertiary)
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Close, contentDescription = "Remove night", tint = Palette.textTertiary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun TimesPair(label: String, value: String, color: Color) {
    Column {
        Text(label, style = NoopType.overline, color = Palette.textTertiary)
        Text(value, style = NoopType.number(16f), color = color)
    }
}

/** Enter one night from WHOOP's Sleep screen: which morning, bed time, wake time. */
@Composable
private fun AddReferenceNight(onAdd: (SleepCalibration.ReferenceNight) -> Unit) {
    var open by remember { mutableStateOf(false) }
    if (!open) {
        DeviceRow(
            title = "Add a night from WHOOP",
            onClick = { open = true },
            control = { Icon(Icons.Filled.Add, null, tint = Palette.accent, modifier = Modifier.size(18.dp)) },
        )
        return
    }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    var daysBack by remember { mutableIntStateOf(0) }
    var bed by remember { mutableStateOf("") }
    var wake by remember { mutableStateOf("") }
    val bedT = parseTime(bed)
    val wakeT = parseTime(wake)
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SegmentedPillControl(
            items = listOf(0, 1, 2, 3),
            selection = daysBack,
            label = { when (it) { 0 -> "Today"; 1 -> "Yesterday"; else -> today.minusDays(it.toLong()).format(DateTimeFormatter.ofPattern("EEE")) } },
            onSelect = { daysBack = it },
            adaptsToAvailableWidth = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeField("Bed", bed, { bed = it }, Modifier.weight(1f))
            TimeField("Wake", wake, { wake = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NoopButton(
                text = "Save",
                kind = NoopButtonKind.Primary,
                enabled = bedT != null && wakeT != null,
                onClick = {
                    val wakeDay = today.minusDays(daysBack.toLong())
                    // A bed time later in the clock than the wake time belongs to the evening before.
                    val bedDay = if (bedT!! > wakeT!!) wakeDay.minusDays(1) else wakeDay
                    onAdd(
                        SleepCalibration.ReferenceNight(
                            id = "whoop-$wakeDay",
                            bedTs = bedDay.atTime(bedT).atZone(zone).toEpochSecond(),
                            wakeTs = wakeDay.atTime(wakeT).atZone(zone).toEpochSecond(),
                            source = "WHOOP app, entered in Whoof",
                        ),
                    )
                    open = false; bed = ""; wake = ""
                },
            )
            NoopButton(text = "Cancel", kind = NoopButtonKind.Secondary, onClick = { open = false })
        }
    }
}

@Composable
private fun TimeField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() || c == ':' }.take(5)) },
        label = { Text(label) },
        placeholder = { Text("23:58") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Palette.textPrimary, unfocusedTextColor = Palette.textPrimary,
            focusedBorderColor = Palette.accent, unfocusedBorderColor = Palette.hairline,
            cursorColor = Palette.accent,
        ),
    )
}

/** "23:58", "2358" or "6:48" → a time; anything else → null. */
internal fun parseTime(s: String): LocalTime? {
    val digits = s.filter { it.isDigit() }
    val (h, m) = when {
        ':' in s -> s.split(':').let { (it.getOrNull(0)?.toIntOrNull() ?: return null) to (it.getOrNull(1)?.toIntOrNull() ?: return null) }
        digits.length == 4 -> digits.substring(0, 2).toInt() to digits.substring(2).toInt()
        digits.length == 3 -> digits.substring(0, 1).toInt() to digits.substring(1).toInt()
        else -> return null
    }
    return if (h in 0..23 && m in 0..59) LocalTime.of(h, m) else null
}

/** HRV baseline and steps estimate: progress, no prose. */
@Composable
private fun OtherCalibrations(vm: AppViewModel, onOpenStepsCalibration: () -> Unit) {
    val context = LocalContext.current
    val days by vm.recentDays.collectAsState()
    val epoch = NoopPrefs.of(context).getLong(Baselines.hrvBaselineEpochKey, 0L).toDouble()
    val nightsIn = recoveryCalibrationNights(days, hasRecovery = false, hrvBaselineEpoch = epoch)
    val seed = Baselines.minNightsSeed
    val profile = remember { ProfileStore.from(context) }
    DeviceShelf("Baselines", onSky = false) {
        DeviceRow(
            title = "HRV baseline",
            control = {
                Text(
                    if (nightsIn == null) "Ready" else "$nightsIn / $seed nights",
                    style = NoopType.captionNumber,
                    color = if (nightsIn == null) StrandTone.Positive.color else Palette.accent,
                )
            },
        )
        DeviceDivider()
        DeviceRow(
            title = "Steps estimate",
            onClick = onOpenStepsCalibration,
            control = {
                Text(
                    when {
                        profile.stepsCalibrationManual -> "Manual"
                        profile.stepsCalibrationSampleDays > 0 ->
                            "${profile.stepsCalibrationSampleDays} days · ${(profile.stepsCalibrationConfidence * 100).toInt()}%"
                        else -> "Not calibrated"
                    },
                    style = NoopType.captionNumber, color = Palette.accent,
                )
            },
        )
    }
}

/** Whoof: the daily feedback loop — a note for the day and one tap that sends the report to Drive. */
@Composable
private fun DailyReportShelf(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf(DailyReport.note(context)) }
    var sending by remember { mutableStateOf(false) }
    var lastSent by remember { mutableStateOf(DailyReport.lastSentDay(context)) }
    DeviceShelf("Daily report", onSky = false) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it; DailyReport.setNote(context, it) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = NoopType.body.copy(color = Palette.textPrimary),
                placeholder = { Text("What was wrong last night?", style = NoopType.body, color = Palette.textTertiary) },
                minLines = 2,
                maxLines = 5,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Palette.accent,
                    unfocusedBorderColor = Palette.textTertiary.copy(alpha = 0.4f),
                    cursorColor = Palette.accent,
                ),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (lastSent == LocalDate.now().toString()) "Sent today" else lastSent?.let { "Last sent $it" } ?: "Not sent yet",
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (sending) Palette.accent.copy(alpha = 0.4f) else Palette.accent)
                        .clickable(enabled = !sending) {
                            sending = true
                            scope.launch {
                                runCatching { DailyReport.send(context, vm.repo, vm.activeStrapId, vm.ble.exportLogText()) }
                                lastSent = DailyReport.lastSentDay(context)
                                sending = false
                            }
                        }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    Text(if (sending) "Preparing…" else "Send to Drive", style = NoopType.headline.copy(fontSize = NoopType.body.fontSize), color = Color.White)
                }
            }
        }
    }
}

