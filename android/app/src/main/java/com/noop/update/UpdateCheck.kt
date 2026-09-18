package com.noop.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Whoof: the update source is the private download catalogue at whoof.pfa87.cc, not GitHub. The
 * catalogue row for slug `whoof` carries versionCode / versionedFile / sha256 / bytes, which is
 * everything [UpdateInstaller] needs to fetch, verify and hand the APK to the package installer.
 */
object UpdateCheck {

    const val HOST = "https://whoof.pfa87.cc"
    /** whoof.pfa87.cc first; the shared public catalogue host second, in case the former is gated. */
    private val HOSTS = listOf(HOST, "https://downloads.pfa87.cc")
    private const val SLUG = "whoof"

    sealed interface Result {
        data class UpToDate(val version: String) : Result
        data class Available(
            val version: String,
            val url: String,
            val notes: String,
            val versionCode: Int = 0,
            val sha256: String = "",
            val bytes: Long = 0L,
        ) : Result {
            fun toJson(): String = JSONObject()
                .put("version", version).put("url", url).put("notes", notes)
                .put("versionCode", versionCode).put("sha256", sha256).put("bytes", bytes).toString()

            companion object {
                fun fromJson(raw: String?): Available? = runCatching {
                    val j = JSONObject(raw ?: return null)
                    Available(
                        j.getString("version"), j.getString("url"), j.optString("notes", ""),
                        j.optInt("versionCode", 0), j.optString("sha256", ""), j.optLong("bytes", 0L),
                    )
                }.getOrNull()
            }
        }
        object Failed : Result
    }

    /** Fetch the catalogue and classify the whoof row against the installed build. Never throws. */
    suspend fun check(
        currentVersion: String,
        currentCode: Int = com.noop.BuildConfig.VERSION_CODE,
    ): Result = withContext(Dispatchers.IO) {
        for (host in HOSTS) {
            val r = checkHost(host, currentVersion, currentCode)
            if (r != Result.Failed) return@withContext r
        }
        Result.Failed
    }

    private fun checkHost(host: String, currentVersion: String, currentCode: Int): Result =
        runCatching {
            val conn = (URL("$host/releases.json").openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 12_000
                readTimeout = 12_000
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (conn.responseCode != 200) return@runCatching Result.Failed
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val apps = json.optJSONArray("apps") ?: return@runCatching Result.Failed
                var row: JSONObject? = null
                for (i in 0 until apps.length()) {
                    val a = apps.optJSONObject(i) ?: continue
                    if (a.optString("slug") == SLUG) { row = a; break }
                }
                val app = row ?: return@runCatching Result.Failed
                val latest = app.getString("version")
                val code = app.optInt("versionCode", 0)
                val file = app.optString("versionedFile").ifBlank { app.optString("file", "whoof.apk") }
                val sha = app.optString("sha256", "").lowercase()
                if (!Regex("^[a-z0-9][a-z0-9._-]*\\.apk$").matches(file) || !Regex("^[a-f0-9]{64}$").matches(sha)) {
                    return@runCatching Result.Failed
                }
                val notes = cleanNotes(
                    app.optJSONArray("features")?.let { f -> (0 until f.length()).joinToString("\n") { "• " + f.optString(it) } }
                        ?: app.optString("description", ""),
                )
                val newer = if (code > 0 && currentCode > 0) code > currentCode else isNewer(latest, currentVersion)
                if (newer) Result.Available(latest, "$host/$file", notes, code, sha, app.optLong("bytes", 0L))
                else Result.UpToDate(latest)
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(Result.Failed)

    fun isNewer(latest: String, current: String): Boolean {
        val a = segments(latest)
        val b = segments(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun segments(s: String): List<Int> =
        s.trim().removePrefix("v").removePrefix("V")
            .takeWhile { it.isDigit() || it == '.' }
            .split(".")
            .mapNotNull { it.toIntOrNull() }

    fun cleanNotes(body: String): String {
        var s = body.substringBefore("Downloads")
        for (marker in listOf("**", "## ", "# ")) s = s.replace(marker, "")
        s = s.trim()
        return if (s.length > 700) s.take(700).trim() + "…" else s
    }
}
