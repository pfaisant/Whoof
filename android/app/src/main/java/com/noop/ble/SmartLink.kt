package com.noop.ble

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.noop.NoopApplication
import com.noop.location.GpsSession
import com.noop.ui.NoopPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Whoof: how the strap link behaves when the app is not on screen. */
enum class BackgroundMode(val raw: String, val label: String) {
    ALWAYS("always", "Always"),
    SMART("smart", "Smart"),
    OFF("off", "Off");

    companion object {
        fun fromRaw(raw: String?): BackgroundMode = entries.firstOrNull { it.raw == raw } ?: ALWAYS
    }
}

/**
 * Whoof: the hybrid link policy behind [BackgroundMode.SMART].
 *
 * Demand = something needs the live link: app on screen, a live-HR screen (Live / Heart / HRV /
 * Breathe), a running workout or GPS route, an offload or re-score in flight, or NIGHT on a strap whose
 * history offload is known empty (5/MG experimental firmware: the night can only be captured live).
 * With no demand for [NoopPrefs.smartIdleMinutes] the foreground service and the link are dropped, so the
 * persistent notification goes away. [StrapSyncWorker] then reconnects every 30 minutes for a short
 * offload + re-score, and the link comes back the moment demand returns.
 */
object SmartLink {
    val appForeground = MutableStateFlow(false)
    val liveWanters = MutableStateFlow(0)
    val workoutActive = MutableStateFlow(false)

    /** True after an idle disconnect, so the next demand reconnects instead of waiting for a tap. */
    @Volatile var idleDisconnected: Boolean = false
        private set
    @Volatile private var idleSinceMs: Long = 0L
    @Volatile private var started = false

    fun nightKeep(ctx: Context, now: LocalTime = LocalTime.now()): Boolean {
        if (!NoopPrefs.strapHistoryEmpty(ctx)) return false
        val h = now.hour
        return h >= 21 || h < 10
    }

    fun demand(ctx: Context, state: LiveState): Boolean =
        appForeground.value || liveWanters.value > 0 || workoutActive.value ||
            GpsSession.state.value.active || state.backfilling || state.analyzingHistory || nightKeep(ctx)

    /** Start the policy loop once per process (the ViewModel calls this from its init). */
    fun start(ctx: Context, ble: WhoopBleClient, scope: CoroutineScope) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        scope.launch {
            while (true) {
                delay(30_000L)
                runCatching { tick(app, ble) }
            }
        }
        // Demand returning after an idle disconnect reconnects straight away.
        scope.launch {
            appForeground.collect { fg -> if (fg) runCatching { reconnectIfIdle(app, ble) } }
        }
        scope.launch {
            liveWanters.collect { n -> if (n > 0) runCatching { reconnectIfIdle(app, ble) } }
        }
        scope.launch {
            // Mirror the strap's "history offload empty" verdict so nightKeep works while disconnected.
            ble.state.collect { st ->
                if (st.connected && st.bonded) NoopPrefs.setStrapHistoryEmpty(app, st.historySyncExperimental)
            }
        }
    }

    private fun tick(app: Context, ble: WhoopBleClient) {
        val mode = NoopPrefs.backgroundMode(app)
        if (mode != BackgroundMode.SMART) { idleSinceMs = 0L; return }
        val st = ble.state.value
        if (!st.connected) { idleSinceMs = 0L; return }
        if (demand(app, st)) { idleSinceMs = 0L; return }
        val now = System.currentTimeMillis()
        if (idleSinceMs == 0L) { idleSinceMs = now; return }
        if (now - idleSinceMs >= NoopPrefs.smartIdleMinutes(app) * 60_000L) {
            ble.externalLog("SmartLink: idle for ${NoopPrefs.smartIdleMinutes(app)} min, dropping the link (periodic sync takes over)")
            WhoopConnectionService.stop(app)
            ble.disconnect()
            idleDisconnected = true
            idleSinceMs = 0L
            ensureScheduled(app)
        }
    }

    fun reconnectIfIdle(app: Context, ble: WhoopBleClient) {
        if (!idleDisconnected) return
        if (NoopPrefs.backgroundMode(app) != BackgroundMode.SMART) return
        val saved = NoopPrefs.lastDevice(app) ?: return
        idleDisconnected = false
        ble.externalLog("SmartLink: demand is back, reconnecting")
        ble.resetReconnectBackoff()
        ble.reconnectToAddress(saved.first, saved.second)
        WhoopConnectionService.start(app)
    }

    /** A manual connect/disconnect from the user cancels the idle bookkeeping. */
    fun userTookOver() { idleDisconnected = false; idleSinceMs = 0L }

    const val WORK_NAME = "whoof.strapSync"

    fun ensureScheduled(ctx: Context) {
        runCatching {
            val request = PeriodicWorkRequestBuilder<StrapSyncWorker>(30, TimeUnit.MINUTES)
                .setInitialDelay(30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/**
 * Whoof: the short sync that replaces the permanent link in Smart mode. Reconnects to the saved strap,
 * asks for a history offload, waits for it and the re-score to finish, then drops the link again unless
 * something started to need it meanwhile. Bounded to a few minutes so WorkManager never has to kill it.
 */
class StrapSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        if (NoopPrefs.backgroundMode(app) != BackgroundMode.SMART) return Result.success()
        val ble = (app as NoopApplication).ble
        if (ble.state.value.connected) return Result.success()   // the live link is up; it syncs itself
        val saved = NoopPrefs.lastDevice(app) ?: return Result.success()
        ble.externalLog("SmartLink: periodic sync — connecting")
        ble.resetReconnectBackoff()
        ble.reconnectToAddress(saved.first, saved.second)
        val bonded = withTimeoutOrNull(75_000L) { ble.state.first { it.connected && it.bonded }; true } ?: false
        if (!bonded) {
            ble.externalLog("SmartLink: periodic sync — strap not reachable, will retry later")
            if (!SmartLink.demand(app, ble.state.value)) ble.disconnect()
            return Result.success()
        }
        ble.syncNow()
        delay(5_000L)
        withTimeoutOrNull(4 * 60_000L) { ble.state.first { !it.backfilling && !it.analyzingHistory } }
        if (SmartLink.demand(app, ble.state.value)) {
            // Something (screen, night keep, workout) wants the link: promote it instead of dropping it.
            WhoopConnectionService.start(app)
        } else {
            ble.externalLog("SmartLink: periodic sync done, dropping the link")
            ble.disconnect()
        }
        return Result.success()
    }
}
