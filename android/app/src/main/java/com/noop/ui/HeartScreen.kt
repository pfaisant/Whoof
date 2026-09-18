package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.HrZones
import com.noop.analytics.StrainScorer
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Whoof: the Heart tab. A live-BPM hero (zone-tinted), the day's resting / HRV / max numbers, then the
 * SAME [HeartRateTrendCard] Today used to host, resolved for the logical today.
 */
@Composable
fun HeartScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    val today by viewModel.today.collectAsStateWithLifecycle()
    val days by viewModel.recentDays.collectAsStateWithLifecycle()
    val activeDayCycle by viewModel.activeDayCycle.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()
    val todayDate = logicalDayNow()
    val dayCycleMode = NoopPrefs.dayCycleMode(context)
    val effortScale = UnitPrefs.effortScale(context)
    val profileStore = remember { ProfileStore.from(context) }
    val displayMetric = today ?: days.lastOrNull { it.day == todayDate.toString() }
    val showDayCycleBackground = remember { NoopPrefs.showDayCycleBackground(context) }
    val skyBehindCards = remember { NoopPrefs.skyBehindCards(context) }

    // Max HR the same way the strain path resolves it: manual override first, else Tanaka from age.
    val maxHr = remember(profileStore.hrMaxOverride, profileStore.age) {
        profileStore.hrMaxOverride.takeIf { it > 0 }?.toDouble()
            ?: if (profileStore.age > 0) HrZones.tanakaMaxHR(profileStore.age.toDouble()) else null
    }
    val bpm = live.heartRate?.takeIf { live.connected }
    val rhr = displayMetric?.restingHr
    val hrv = displayMetric?.avgHrv

    var liveTodayStrain by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(days, today?.day, activeDayCycle, dayCycleMode) {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis() / 1000
        val start = activeDayCycleStart(
            mode = dayCycleMode,
            confirmedOrSyntheticOnset = activeDayCycle?.onsetTs,
            calendarStart = todayDate.atStartOfDay(zone).toEpochSecond(),
        )
        val todayHr = runCatching { viewModel.repo.hrSamplesUnion(viewModel.activeStrapId, start, now) }
            .getOrDefault(emptyList())
        val restingHr = rhr?.toDouble() ?: StrainScorer.defaultRestingHR
        liveTodayStrain = StrainScorer.strain(
            hr = todayHr,
            maxHR = maxHr,
            restingHR = restingHr,
            method = NoopPrefs.effortMethod(context),
            sex = profileStore.sex,
        )
    }
    val effortForDay = StrainScorer.effectiveEffort(live = liveTodayStrain, stored = displayMetric?.strain)

    LazyScreenScaffold(
        title = uiString(R.string.nav_heart),
        topBackground = screenBackdropSlot(showDayCycleBackground, skyBehindCards),
        fullBleedBackground = screenBackdropFullBleed(showDayCycleBackground, skyBehindCards),
    ) {
        item { LiveBpmHero(bpm = bpm, rhr = rhr, hrv = hrv, maxHr = maxHr, connected = live.connected) }
        item {
            HeartRateTrendCard(
                viewModel = viewModel,
                days = days,
                selectedDay = todayDate,
                today = todayDate,
                displayMetric = displayMetric,
                effortScale = effortScale,
                effortForDay = effortForDay,
                dayCycleMode = dayCycleMode,
            )
        }
    }
}

/** Big live number with a zone word, then the three daily numbers in one row. */
@Composable
private fun LiveBpmHero(bpm: Int?, rhr: Int?, hrv: Double?, maxHr: Double?, connected: Boolean) {
    val fraction = if (bpm != null && maxHr != null && maxHr > 0) bpm / maxHr else null
    val zoneWord = when {
        bpm == null -> if (connected) "Waiting for the strap" else "Strap not connected"
        fraction == null -> "Live"
        fraction < 0.50 -> "Resting"
        fraction < 0.60 -> "Light"
        fraction < 0.70 -> "Moderate"
        fraction < 0.80 -> "Hard"
        fraction < 0.90 -> "Very hard"
        else -> "Max"
    }
    val tint = when {
        fraction == null -> Palette.metricRose
        fraction < 0.60 -> Palette.metricCyan
        fraction < 0.80 -> Palette.effortColor
        else -> Palette.statusCritical
    }
    NoopCard(padding = Metrics.space16, tint = tint) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    bpm?.toString() ?: "—",
                    style = NoopType.number(64f, weight = FontWeight.Bold),
                    color = if (bpm != null) tint else Palette.textTertiary,
                )
                Text("bpm", style = NoopType.subhead, color = Palette.textTertiary, modifier = Modifier.padding(bottom = 12.dp))
            }
            Text(zoneWord, style = NoopType.subhead, color = Palette.textSecondary)
            Spacer(Modifier.height(Metrics.space12))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                StatTile(
                    modifier = Modifier.weight(1f), label = "Resting",
                    value = rhr?.toString() ?: "—", caption = "bpm", accent = Palette.metricRose,
                )
                StatTile(
                    modifier = Modifier.weight(1f), label = "HRV",
                    value = hrv?.roundToInt()?.toString() ?: "—", caption = "ms", accent = Palette.metricCyan,
                )
                StatTile(
                    modifier = Modifier.weight(1f), label = "Max",
                    value = maxHr?.roundToInt()?.toString() ?: "—", caption = "bpm", accent = Palette.effortColor,
                )
            }
        }
    }
}
