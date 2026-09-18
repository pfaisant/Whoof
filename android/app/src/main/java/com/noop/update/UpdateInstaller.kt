package com.noop.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

/**
 * Whoof: download the published APK with DownloadManager, verify its SHA-256 against the catalogue,
 * then hand it to the package installer through the app's FileProvider. One download at a time;
 * [phase] is Compose state so Today / Settings can render progress in place.
 */
object UpdateInstaller {

    sealed interface Phase {
        object Idle : Phase
        data class Downloading(val version: String) : Phase
        data class Verifying(val version: String) : Phase
        data class Ready(val version: String, val file: File) : Phase
        data class Failed(val message: String) : Phase
    }

    val phase = mutableStateOf<Phase>(Phase.Idle)
    private var downloadId: Long = -1L
    private var receiver: BroadcastReceiver? = null

    /** Start (or resume the UI of) an update. Opens the unknown-sources screen first when needed. */
    fun start(context: Context, update: UpdateCheck.Result.Available) {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !app.packageManager.canRequestPackageInstalls()) {
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            phase.value = Phase.Failed("Allow installs from Whoof, then tap Install again.")
            return
        }
        val current = phase.value
        if (current is Phase.Ready && current.version == update.version && current.file.exists()) {
            install(app, current.file); return
        }
        if (current is Phase.Downloading || current is Phase.Verifying) return

        val dir = File(app.getExternalFilesDir(null), "updates").apply { mkdirs() }
        val target = File(dir, "whoof-${update.version}.apk")
        if (target.exists()) {
            if (sha256(target) == update.sha256) { phase.value = Phase.Ready(update.version, target); install(app, target); return }
            target.delete()
        }
        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(update.url))
            .setTitle("Whoof ${update.version}")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(app, null, "updates/whoof-${update.version}.apk")
        registerReceiver(app, update, target)
        downloadId = dm.enqueue(request)
        phase.value = Phase.Downloading(update.version)
    }

    private fun registerReceiver(app: Context, update: UpdateCheck.Result.Available, target: File) {
        receiver?.let { runCatching { app.unregisterReceiver(it) } }
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != downloadId) return
                runCatching { app.unregisterReceiver(this) }
                receiver = null
                phase.value = Phase.Verifying(update.version)
                Thread {
                    val ok = target.exists() && (update.bytes <= 0L || target.length() == update.bytes) &&
                        sha256(target) == update.sha256
                    if (ok) {
                        phase.value = Phase.Ready(update.version, target)
                        install(app, target)
                    } else {
                        target.delete()
                        phase.value = Phase.Failed("Download did not match the published checksum.")
                    }
                }.start()
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(r, filter)
        }
        receiver = r
    }

    fun install(app: Context, file: File) {
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(intent) }
            .onFailure { phase.value = Phase.Failed("Could not open the installer.") }
    }

    fun reset() { phase.value = Phase.Idle }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("unused")
    private fun externalRoot(app: Context): File? = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
}
