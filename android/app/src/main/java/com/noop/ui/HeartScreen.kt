package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.background
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
        title = null,   // Whoof: the bottom bar already says Heart
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

private val LIQUID_HERO_RADIUS = 26.dp

/** Whoof: the live number floats on a liquid vessel filled to bpm / max HR, then the daily numbers. */
@Composable
private fun LiveBpmHero(bpm: Int?, rhr: Int?, hrv: Double?, maxHr: Double?, connected: Boolean) {
    val fraction = if (bpm != null && maxHr != null && maxHr > 0) (bpm / maxHr).coerceIn(0.0, 1.0) else null
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
        fraction == null -> Palette.accent
        fraction < 0.60 -> Palette.accent
        fraction < 0.80 -> Palette.effortColor
        else -> Palette.statusCritical
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LIQUID_HERO_RADIUS))
            .background(Palette.heroFill.copy(alpha = Palette.heroFill.alpha * CardAppearance.opacity))
            .border(1.dp, Palette.heroBorder.copy(alpha = Palette.heroBorder.alpha * CardAppearance.opacity), RoundedCornerShape(LIQUID_HERO_RADIUS)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Metrics.space16),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Metrics.space12),
        ) {
            Box(modifier = Modifier.size(168.dp), contentAlignment = Alignment.Center) {
                LiquidVessel(
                    value = fraction ?: 0.0,
                    tint = tint,
                    animated = bpm != null,
                    modifier = Modifier.fillMaxSize(),
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (bpm != null) {
                        CountUpText(
                            value = bpm.toDouble(),
                            format = { it.roundToInt().toString() },
                            style = NoopType.number(56f, weight = FontWeight.Bold)
                                .copy(shadow = Shadow(color = Color.Black.copy(alpha = 0.5f), offset = Offset(0f, 1f), blurRadius = 6f)),
                            color = Color.White,
                            modifier = Modifier.clearAndSetSemantics {},
                        )
                    } else {
                        Text("—", style = NoopType.number(56f, weight = FontWeight.Bold), color = Palette.textSecondary)
                    }
                    Text("bpm", style = NoopType.caption, color = Color.White.copy(alpha = 0.75f))
                }
            }
            Text(zoneWord, style = NoopType.subhead, color = if (bpm != null) tint else Palette.textTertiary)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                HeroStat(Modifier.weight(1f), "Resting", rhr?.toString() ?: "—", "bpm", Palette.metricRose)
                HeroStat(Modifier.weight(1f), "HRV", hrv?.roundToInt()?.toString() ?: "—", "ms", Palette.metricCyan)
                HeroStat(Modifier.weight(1f), "Max", maxHr?.roundToInt()?.toString() ?: "—", "bpm", Palette.effortColor)
            }
        }
    }
}

@Composable
private fun HeroStat(modifier: Modifier, label: String, value: String, unit: String, accent: Color) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label.uppercase(), style = NoopType.caption, color = Palette.textTertiary)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(value, style = NoopType.number(22f, weight = FontWeight.Bold), color = accent)
            Text(unit, style = NoopType.caption, color = Palette.textTertiary, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}
