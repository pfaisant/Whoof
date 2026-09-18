package com.noop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.StrainScorer
import java.time.ZoneId

/**
 * Whoof: the Today heart-rate thread as its own bottom-bar tab. Renders the SAME [HeartRateTrendCard]
 * the Today screen hosts, resolved for the logical today, so the two can never disagree.
 */
@Composable
fun HeartScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    val today by viewModel.today.collectAsStateWithLifecycle()
    val days by viewModel.recentDays.collectAsStateWithLifecycle()
    val activeDayCycle by viewModel.activeDayCycle.collectAsStateWithLifecycle()
    val todayDate = logicalDayNow()
    val dayCycleMode = NoopPrefs.dayCycleMode(context)
    val effortScale = UnitPrefs.effortScale(context)
    val profileStore = remember { ProfileStore.from(context) }
    val displayMetric = today ?: days.lastOrNull { it.day == todayDate.toString() }
    val showDayCycleBackground = remember { NoopPrefs.showDayCycleBackground(context) }
    val skyBehindCards = remember { NoopPrefs.skyBehindCards(context) }

    // Live in-progress strain for the chart's edge badge, the same rule Today uses (#1001).
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
        val effMaxHR = profileStore.hrMaxOverride.takeIf { it > 0 }?.toDouble()
            ?: if (profileStore.age > 0) StrainScorer.tanakaHRmax(profileStore.age.toDouble()) else null
        val restingHr = displayMetric?.restingHr?.toDouble() ?: StrainScorer.defaultRestingHR
        liveTodayStrain = StrainScorer.strain(
            hr = todayHr,
            maxHR = effMaxHR,
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
