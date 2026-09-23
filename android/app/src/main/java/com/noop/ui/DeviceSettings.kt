package com.noop.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.noop.ble.BackgroundMode
import com.noop.ble.LiveState
import com.noop.R
import kotlin.math.roundToInt

/*
 * DeviceSettings.kt — Whoof: ONE place for everything about the link to the strap.
 *
 * WHY THIS FILE EXISTS. Connectivity was spread across five surfaces: the "Strap" card in Settings
 * (status, re-scan, rename, background mode, the two link experiments, the battery-exemption prompt,
 * continuous capture, the HRV window, the strap-log export), a separate "Power saving" page, a
 * separate "Devices" page, the 5/MG experimental probes, and the Test Centre. Answering "why is my
 * strap not syncing" meant visiting three of them and knowing which. They are now one tab.
 *
 * THE DESIGN IS DELIBERATELY NOT THE REST OF SETTINGS. Every other tab is a stack of NoopCards, each
 * with an icon, a title and a blurb, and rows spaced 16dp apart inside. That shape is right for a list
 * of unrelated preferences and wrong here, where the page has ONE subject and the reader arrives with
 * one question: is the link healthy, and what can I change about it. So this tab is:
 *
 *   - a STATUS HERO that answers the question before any control does — state, the honest detail line,
 *     a real battery meter, and the two actions that fix most problems (re-scan, disconnect);
 *   - the LINK MODE as the one prominent decision, with a consequence line that changes with it,
 *     because it is the setting that decides whether anything else on this page matters;
 *   - then flat SHELVES ([DeviceShelf]) — an overline, a hairline rule, and rows sitting directly on
 *     the page surface with dividers between them. No nested cards: a card inside a card is what made
 *     the old Strap card read as a wall.
 *
 * Every row carries its own one-line consequence rather than a shared blurb at the top, so a reader
 * scanning for the thing that is wrong does not have to hold a paragraph in their head.
 *
 * NOTHING IS DROPPED. Every control the old Strap card carried is here, wired to the same prefs and
 * the same view-model calls; this is a re-presentation, not a re-implementation. The only behaviour
 * change is [DeviceRow] gating — a row that cannot do anything in the current state says so instead
 * of being tappable and silently refused.
 */

// ── Building blocks ──────────────────────────────────────────────────────────────────────────────

/** A titled group of rows sitting on the page surface: overline, accent rule, then the rows. */
@Composable
internal fun DeviceShelf(title: String, onSky: Boolean = true, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 2.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // White over the Settings day-cycle sky; ink on a plain screen, where white would vanish.
            val ink = if (onSky) Color.White.copy(alpha = 0.85f) else Palette.textSecondary
            Text(title.uppercase(), style = NoopType.overline, color = ink)
            Box(modifier = Modifier.weight(1f).height(1.dp).background(if (onSky) Color.White.copy(alpha = 0.25f) else Palette.hairline))
        }
        // The same frosted surface every NoopCard uses, so a shelf is legible over the day-cycle sky.
        // A translucent inset (the first cut) let the sky through and washed the detail lines out.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Metrics.cardRadius))
                .frostedCardSurface(tint = null, cornerRadius = Metrics.cardRadius),
        ) { content() }
    }
}

/** A hairline between two rows in a shelf. Never drawn after the last row — the shelf border closes it. */
@Composable
internal fun DeviceDivider() {
    Box(modifier = Modifier.fillMaxWidth().padding(start = 14.dp).height(1.dp).background(Palette.hairline))
}

/**
 * One row: title, a one-line consequence, and the control on the right.
 *
 * [enabled] dims the row and tells the control it is inert. A row is disabled when the state it needs
 * is absent (no link, background mode OFF), and [detail] is expected to say WHY in that case — the old
 * card left such controls fully lit and had them refused deeper down, which reads as a broken setting.
 */
@Composable
internal fun DeviceRow(
    title: String,
    detail: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    control: @Composable (() -> Unit)? = null,
) {
    val alpha = if (enabled) 1f else 0.45f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else Modifier,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = NoopType.body,
                color = Palette.textPrimary.copy(alpha = alpha),
            )
            // Whoof: no row subtitles (owner, 23 Sep 2026). [detail] is kept as the row's accessibility text.
        }
        control?.invoke()
        if (control == null && onClick != null) {
            Text("›", style = NoopType.title2, color = Palette.accent.copy(alpha = alpha))
        }
    }
}

/** The house switch, in one place so every row on this page is the same switch. */
@Composable
internal fun DeviceSwitch(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        enabled = enabled,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Palette.surfaceBase,
            checkedTrackColor = Palette.accent,
            uncheckedThumbColor = Palette.textSecondary,
            uncheckedTrackColor = Palette.surfaceInset,
            uncheckedBorderColor = Palette.hairline,
        ),
    )
}

/**
 * A battery capsule: the strap's charge as a drawn meter rather than a number in a pill.
 *
 * The old header put the percentage in a [StatePill] beside the connection state, where the two read
 * as the same KIND of fact. They are not: one is a state, the other is a quantity with a threshold you
 * want to see approaching. Drawn, it is legible at a glance and its colour carries the same
 * [batteryTone] thresholds the pill used.
 */
@Composable
internal fun DeviceBatteryMeter(pct: Double, charging: Boolean) {
    val tone = batteryTone(pct)
    val shape = RoundedCornerShape(50)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .width(64.dp)
                .height(10.dp)
                .clip(shape)
                .background(Palette.surfaceInset)
                .border(1.dp, Palette.hairline, shape),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = (pct / 100.0).coerceIn(0.02, 1.0).toFloat())
                    .height(10.dp)
                    .clip(shape)
                    .background(Brush.horizontalGradient(listOf(tone.color.copy(alpha = 0.7f), tone.color))),
            )
        }
        Text(
            "${pct.roundToInt()}%" + if (charging) " · charging" else "",
            style = NoopType.captionNumber,
            color = tone.color,
        )
    }
}

// ── The page ─────────────────────────────────────────────────────────────────────────────────────

/** What the link mode actually costs and buys — shown under the selector so the choice is informed. */
internal fun backgroundModeConsequence(mode: BackgroundMode, idleMinutes: Int): String = when (mode) {
    BackgroundMode.ALWAYS ->
        "The link is held open. History arrives on its own and the night is captured whatever happens. " +
            "Uses the most strap battery."
    BackgroundMode.SMART ->
        "The link is dropped after $idleMinutes min with nothing to do, then re-made every 30 min for a " +
            "short sync. Saves battery; a night can be missed if the strap never reconnects."
    BackgroundMode.OFF ->
        "Nothing connects unless Whoof is open. Scores only update while you are looking at them."
}

/**
 * The Device tab: everything about the link to the strap.
 *
 * Takes the same handles the old Strap card took, so this is a re-presentation of wiring that already
 * worked. [onOpen] routes the rows that lead to a whole screen of their own (paired devices, the Test
 * Centre) rather than duplicating those screens here.
 */
@Composable
internal fun DeviceSettingsSection(
    vm: AppViewModel,
    live: LiveState,
    requestScan: () -> Unit,
    onShareStrapLog: () -> Unit,
    strapLogBusy: Boolean,
    onOpenModelComparison: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        DeviceStatusHero(vm = vm, live = live, requestScan = requestScan)
        DeviceLinkShelf(vm = vm, context = context)
        DeviceSyncShelf(vm = vm, live = live, context = context)
        DeviceCaptureShelf(vm = vm, context = context)
        DevicePowerShelf(vm = vm, context = context)
        DeviceHardwareShelf(
            vm = vm,
            live = live,
            onShareStrapLog = onShareStrapLog,
            strapLogBusy = strapLogBusy,
            onOpenModelComparison = onOpenModelComparison,
            onOpen = onOpen,
        )
    }
}

/** State, the honest detail, the battery meter, and the two actions that fix most problems. */
@Composable
private fun DeviceStatusHero(vm: AppViewModel, live: LiveState, requestScan: () -> Unit) {
    NoopCard(padding = 20.dp, tint = Palette.accent) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatePill(
                    title = strapStatusTitle(live.encryptedBond, live.bonded, live.connected),
                    tone = strapTone(live.encryptedBond, live.bonded, live.connected),
                    pulsing = live.connected,
                )
                live.strapFirmware?.let {
                    Text("fw $it", style = NoopType.footnote, color = Palette.textTertiary)
                }
            }
            Text(
                strapStatusDetail(live.encryptedBond, live.bonded, live.connected, live.scanning),
                style = NoopType.subhead,
                color = Palette.textSecondary,
            )
            live.batteryPct?.let { DeviceBatteryMeter(pct = it, charging = live.charging == true) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NoopButton(
                    text = if (live.scanning) "Searching…" else "Re-scan",
                    leadingIcon = Icons.Filled.Refresh,
                    kind = NoopButtonKind.Primary,
                    enabled = !live.scanning,
                    onClick = requestScan,
                )
                NoopButton(
                    text = uiString(R.string.l10n_settings_screen_disconnect_ed28e068),
                    leadingIcon = Icons.Filled.Cancel,
                    kind = NoopButtonKind.Secondary,
                    enabled = live.connected || live.bonded,
                    onClick = { vm.disconnect() },
                )
            }
        }
    }
}

/** The one decision that governs the rest of the page, plus the Android permission it depends on. */
@Composable
private fun DeviceLinkShelf(vm: AppViewModel, context: Context) {
    var mode by remember { mutableStateOf(NoopPrefs.backgroundMode(context)) }
    var idleMinutes by remember { mutableStateOf(NoopPrefs.smartIdleMinutes(context)) }
    DeviceShelf("Link") {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SegmentedPillControl(
                items = BackgroundMode.entries,
                selection = mode,
                label = { it.label },
                onSelect = {
                    mode = it
                    vm.setBackgroundMode(it)
                },
                adaptsToAvailableWidth = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (mode == BackgroundMode.SMART) {
            DeviceDivider()
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Idle before dropping the link", style = NoopType.body, color = Palette.textPrimary)
                    Text("$idleMinutes min", style = NoopType.captionNumber, color = Palette.accent)
                }
                Slider(
                    value = idleMinutes.toFloat(),
                    onValueChange = { idleMinutes = it.roundToInt() },
                    onValueChangeFinished = { NoopPrefs.setSmartIdleMinutes(context, idleMinutes) },
                    valueRange = 5f..60f,
                    steps = 10,
                    colors = SliderDefaults.colors(
                        thumbColor = Palette.accent,
                        activeTrackColor = Palette.accent,
                        inactiveTrackColor = Palette.surfaceInset,
                    ),
                )
            }
        }
        DeviceBatteryExemptionRows(mode = mode, context = context)
    }
}

/**
 * The Android battery-optimisation exemption, kept as a one-way PROMPT rather than a toggle.
 *
 * Android lets an app ASK for this exemption and never hand it back, so a switch would advertise an
 * off direction it cannot honour. Shown only where it can change the outcome — background link on, a
 * ROM known to kill background work, exemption not yet granted — and it DISAPPEARS once granted. The
 * live state is re-read on every ON_RESUME so returning from the system dialog removes the row rather
 * than leaving it to invite a second, duplicate popup.
 */
@Composable
private fun DeviceBatteryExemptionRows(mode: BackgroundMode, context: Context) {
    val aggressiveVendor = remember { com.noop.ble.BackgroundHealth.isAggressiveVendor() }
    if (mode == BackgroundMode.OFF || !aggressiveVendor) return
    val lifecycleOwner = LocalLifecycleOwner.current
    var batteryExempt by remember { mutableStateOf(com.noop.ble.BackgroundHealth.isBatteryExempt(context)) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryExempt = com.noop.ble.BackgroundHealth.isBatteryExempt(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    if (!batteryExempt) {
        DeviceDivider()
        DeviceRow(
            title = "Keep Whoof alive overnight",
            detail = "This phone's maker stops background apps. Without the exemption the link dies " +
                "in the night and the morning has no scores.",
            onClick = {
                runCatching {
                    context.startActivity(com.noop.ble.BackgroundHealth.batteryExemptionIntent(context))
                }.onFailure {
                    runCatching {
                        context.startActivity(com.noop.ble.BackgroundHealth.appBatterySettingsIntent(context))
                    }
                }
            },
        )
    }
    val oemAutostart = remember { com.noop.ble.BackgroundHealth.oemAutostartIntent(context) }
    if (oemAutostart != null) {
        DeviceDivider()
        DeviceRow(
            title = "Auto-start (${android.os.Build.MANUFACTURER})",
            detail = "A separate maker screen. Whoof must be allowed to start on its own, or nothing " +
                "reconnects after a reboot.",
            onClick = { runCatching { context.startActivity(oemAutostart) } },
        )
    }
}

/** How fast history comes over, and the one control that asks for it now. */
@Composable
private fun DeviceSyncShelf(vm: AppViewModel, live: LiveState, context: Context) {
    var fastHistorySync by remember { mutableStateOf(NoopPrefs.fastHistorySync(context)) }
    var fastLinkPhy by remember { mutableStateOf(NoopPrefs.fastLinkPhy(context)) }
    DeviceShelf("Sync") {
        DeviceRow(
            title = "Sync now",
            detail = if (live.backfilling) "Offload running — ${live.syncChunksThisSession} chunks acked."
            else "Ask the strap for everything it has banked since the last offload.",
            enabled = live.historyReady && !live.backfilling,
            onClick = { vm.syncNow() },
        )
        DeviceDivider()
        DeviceRow(
            title = "Faster history sync",
            detail = "Asks Android for a shorter connection interval during the offload burst only. " +
                "Experimental: costs strap battery, and BLE behaviour cannot be tested here.",
            control = {
                DeviceSwitch(fastHistorySync) {
                    fastHistorySync = it
                    vm.setFastHistorySync(it)
                }
            },
        )
        DeviceDivider()
        DeviceRow(
            title = "Faster Bluetooth link",
            detail = "Prefers the LE 2M radio around the offload — the same bytes in half the airtime, " +
                "so it should cost LESS strap energy. The strap may decline it; 2M trades range for speed.",
            control = {
                DeviceSwitch(fastLinkPhy) {
                    fastLinkPhy = it
                    vm.setFastLinkPhy(it)
                }
            },
        )
    }
}

/** What the strap streams while nothing is on screen, and over which window HRV is measured. */
@Composable
private fun DeviceCaptureShelf(vm: AppViewModel, context: Context) {
    var continuousHrv by remember { mutableStateOf(NoopPrefs.continuousHrv(context)) }
    var continuousHrvOvernight by remember { mutableStateOf(NoopPrefs.continuousHrvOvernight(context)) }
    var hrvWindow by remember { mutableStateOf(UnitPrefs.hrvWindow(context)) }
    val backgroundOn = NoopPrefs.backgroundMode(context) != BackgroundMode.OFF
    DeviceShelf("Capture") {
        DeviceRow(
            title = uiString(R.string.l10n_settings_screen_continuous_hrv_capture_1f0805d8),
            detail = if (backgroundOn) {
                "Holds the beat-to-beat stream open with no screen up, so the strap banks far more " +
                    "overnight R-R. Uses more battery on both sides."
            } else {
                "Needs the background link on — there is no link to stream over while it is off."
            },
            enabled = backgroundOn,
            control = {
                DeviceSwitch(continuousHrv, enabled = backgroundOn) {
                    continuousHrv = it
                    vm.setContinuousHrv(it)
                }
            },
        )
        if (continuousHrv && backgroundOn) {
            DeviceDivider()
            DeviceRow(
                title = uiString(R.string.l10n_settings_screen_overnight_only_05747985),
                detail = "Streams only inside the nightly quiet-hours window. Daytime naps are not " +
                    "captured continuously; use the HRV reading button on Heart for one on demand.",
                control = {
                    DeviceSwitch(continuousHrvOvernight) {
                        continuousHrvOvernight = it
                        vm.setContinuousHrvOvernight(it)
                    }
                },
            )
        }
        DeviceDivider()
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    uiString(R.string.l10n_settings_screen_hrv_window_e74320b8),
                    style = NoopType.body,
                    color = Palette.textPrimary,
                )
            }
            SegmentedPillControl(
                items = listOf(HrvWindow.WHOLE_NIGHT, HrvWindow.DEEP_SLEEP),
                selection = hrvWindow,
                label = { if (it == HrvWindow.DEEP_SLEEP) "Deep sleep" else "Night" },
                onSelect = {
                    hrvWindow = it
                    UnitPrefs.setHrvWindow(context, it)
                    // The window changes every night's avgHrv, so the recent nights must be re-scored.
                    // Clearing the watermark makes the pass run even though the raw HR fingerprint is
                    // unchanged; the baseline re-folds from the re-scored tail in the same pass, which is
                    // why the baseline epoch is deliberately NOT re-anchored here (that would force a
                    // multi-night "calibrating" reset on someone who already has plenty of nights).
                    NoopPrefs.setAnalyzeWatermark(context, "")
                    vm.syncNow()
                },
                adaptsToAvailableWidth = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The levers that trade freshness for strap battery. Folded in from the separate Power saving page. */
@Composable
private fun DevicePowerShelf(vm: AppViewModel, context: Context) {
    var powerSaving by remember { mutableStateOf(NoopPrefs.powerSaving(context)) }
    var lowRefresh by remember { mutableStateOf(NoopPrefs.lowRefresh(context)) }
    var batteryPct by remember { mutableStateOf(NoopPrefs.powerSavingBatteryPct(context)) }
    DeviceShelf("Power") {
        DeviceRow(
            title = uiString(R.string.power_saving),
            detail = "Below the threshold, Whoof asks the strap for less: fewer offloads and no held-open " +
                "stream. Nothing is lost — the strap keeps banking to its own flash.",
            control = {
                DeviceSwitch(powerSaving) {
                    powerSaving = it
                    vm.setPowerSaving(it)
                }
            },
        )
        if (powerSaving) {
            DeviceDivider()
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Engages below", style = NoopType.body, color = Palette.textPrimary)
                    Text("$batteryPct%", style = NoopType.captionNumber, color = Palette.accent)
                }
                Slider(
                    value = batteryPct.toFloat(),
                    onValueChange = { batteryPct = it.roundToInt() },
                    onValueChangeFinished = { vm.setPowerSavingBatteryPct(batteryPct) },
                    valueRange = 5f..50f,
                    colors = SliderDefaults.colors(
                        thumbColor = Palette.accent,
                        activeTrackColor = Palette.accent,
                        inactiveTrackColor = Palette.surfaceInset,
                    ),
                )
            }
            DeviceDivider()
            var pauseHrv by remember { mutableStateOf(NoopPrefs.pauseHrvOnPowerSave(context)) }
            DeviceRow(
                title = "Pause HRV capture",
                control = {
                    DeviceSwitch(pauseHrv) {
                        pauseHrv = it
                        vm.setPauseHrvOnPowerSave(it)
                    }
                },
            )
            DeviceDivider()
            DeviceRow(
                title = "Low refresh",
                detail = "Moves the background offload to roughly hourly whatever the battery says.",
                control = {
                    DeviceSwitch(lowRefresh) {
                        lowRefresh = it
                        vm.setLowRefresh(it)
                    }
                },
            )
        }
    }
}

/** The strap itself, the other device surfaces, and the log a field report needs. */
@Composable
private fun DeviceHardwareShelf(
    vm: AppViewModel,
    live: LiveState,
    onShareStrapLog: () -> Unit,
    strapLogBusy: Boolean,
    onOpenModelComparison: () -> Unit,
    onOpen: (String) -> Unit,
) {
    DeviceShelf("Device") {
        DeviceRow(
            title = uiString(R.string.nav_devices),
            detail = "Every strap and source Whoof has paired with, and which one owns a day.",
            onClick = { onOpen(Destination.Devices.route) },
        )
        DeviceDivider()
        DeviceRow(
            title = uiString(R.string.l10n_settings_screen_whoop_4_0_vs_5_0_2babb05a),
            detail = uiString(R.string.l10n_settings_screen_what_each_strap_can_read_and_51e7d3fc),
            onClick = onOpenModelComparison,
        )
        DeviceDivider()
        DeviceRow(
            title = uiString(R.string.l10n_settings_screen_share_strap_log_for_bug_reports_b9802500),
            detail = if (strapLogBusy) "Building the log…"
            else "Every link, offload and refusal, on disk across restarts. This is the file a report needs.",
            enabled = !strapLogBusy,
            onClick = onShareStrapLog,
            control = {
                Icon(
                    Icons.Filled.Upload,
                    contentDescription = null,
                    tint = Palette.accent.copy(alpha = if (strapLogBusy) 0.45f else 1f),
                    modifier = Modifier.size(18.dp),
                )
            },
        )
        DeviceDivider()
        DeviceRow(
            title = uiString(R.string.nav_test_centre),
            detail = "Turn on a test for the thing that is wrong, wear the strap, then report.",
            onClick = { onOpen(Destination.TestCentre.route) },
        )
    }
}
