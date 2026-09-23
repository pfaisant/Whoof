package com.noop.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/*
 * DriveExport.kt — Whoof: put logs and data on Google Drive with one tap.
 *
 * Two paths, because Android offers two and neither needs a Google API key or an account sign-in inside
 * Whoof:
 *  1. A REGISTERED folder. The owner picks a folder once through the system folder picker (the same
 *     picker and the same stored grant the daily backup already uses, [BackupSyncPrefs.treeUri]). When
 *     the Drive app exposes a folder there, files are written straight into Drive; when it does not, a
 *     local folder the Drive app syncs works the same way.
 *  2. The Drive app itself. With no folder registered, the file goes to Google Drive's own "Save to
 *     Drive" sheet (package com.google.android.apps.docs) — one confirm, no chooser to hunt through.
 *     Without the Drive app installed it falls back to the ordinary share sheet.
 */
object DriveExport {
    const val DRIVE_PACKAGE = "com.google.android.apps.docs"

    fun hasFolder(context: Context): Boolean = BackupSyncPrefs.treeUri(context) != null

    /** Human name of the registered folder ("Whoof" / "Drive › Whoof"), or null. */
    fun folderLabel(context: Context): String? = BackupSyncPrefs.treeUri(context)?.let { uri ->
        runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?.substringAfterLast(':')?.ifBlank { null } ?: uri.lastPathSegment
    }

    fun isDriveInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(DRIVE_PACKAGE, 0); true }.getOrDefault(false)

    /** Save the strap log: into the registered folder, else hand it to the Drive app. */
    suspend fun saveStrapLog(context: Context, logText: String) {
        val file = withContext(Dispatchers.IO) { LogExport.writeStrapLogFile(context, logText) }
        saveFile(context, file, "text/plain")
    }

    /** Save a full data backup now (the daily backup's own snapshot) into the registered folder. */
    suspend fun saveBackupNow(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!hasFolder(context)) return@withContext false
        BackupSync.backupNow(context)
    }

    internal suspend fun saveFile(context: Context, file: File, mime: String) {
        val tree = BackupSyncPrefs.treeUri(context)
        if (tree != null) {
            val ok = withContext(Dispatchers.IO) { copyInto(context, tree, file, mime) }
            if (ok) {
                Toast.makeText(context, "Saved to ${folderLabel(context) ?: "your folder"}", Toast.LENGTH_SHORT).show()
                return
            }
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, LogExport.fileUri(context, file))
            putExtra(Intent.EXTRA_TITLE, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(Intent(send).setPackage(DRIVE_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent.createChooser(send, "Save log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun copyInto(context: Context, tree: Uri, file: File, mime: String): Boolean = runCatching {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(context.contentResolver, parent, mime, file.name) ?: return false
        context.contentResolver.openOutputStream(doc)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: return false
        true
    }.getOrDefault(false)
}
