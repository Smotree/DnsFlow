package ru.dnsflow.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates the app from the latest GitHub release of the public repository: a release tagged
 * "v<versionName>" with the signed APK attached. Android itself refuses an APK signed with
 * another key, so only our own builds can be installed over the app.
 */
object Updater {
    private const val TAG = "Updater"
    private const val LATEST = "https://api.github.com/repos/Smotree/DnsFlow/releases/latest"

    data class Release(val version: String, val notes: String, val apkUrl: String, val size: Long)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val percent: Int) : State
        /** Downloaded; waiting for the user to allow installing from this app. */
        data class NeedsPermission(val release: Release, val apk: File) : State
        data class Failed(val message: String, val release: Release? = null) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var checkedThisRun = false

    fun currentVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    /** Checks once per app start unless [force]. Errors (no internet) stay quiet on the automatic check. */
    suspend fun check(context: Context, force: Boolean = false) {
        if (checkedThisRun && !force) return
        if (_state.value is State.Downloading) return
        checkedThisRun = true
        _state.value = State.Checking
        val result = runCatching { withContext(Dispatchers.IO) { latest() } }
        _state.value = result.fold(
            onSuccess = { r ->
                if (r != null && isNewer(r.version, currentVersion(context))) State.Available(r) else State.UpToDate
            },
            onFailure = { e ->
                Log.w(TAG, "check failed", e)
                if (force) State.Failed("Не удалось проверить обновления: нет связи с GitHub") else State.Idle
            },
        )
    }

    /** Downloads the APK and opens the system installer. */
    suspend fun install(context: Context, release: Release) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "Priemka-${release.version}.apk")
        if (!(apk.exists() && apk.length() == release.size)) {
            dir.listFiles()?.forEach { it.delete() }
            _state.value = State.Downloading(release, 0)
            val ok = runCatching { withContext(Dispatchers.IO) { download(release, apk) } }
            if (ok.isFailure) {
                Log.w(TAG, "download failed", ok.exceptionOrNull())
                apk.delete()
                _state.value = State.Failed("Не удалось скачать обновление", release)
                return
            }
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = State.NeedsPermission(release, apk)
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        _state.value = State.Available(release)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun latest(): Release? {
        val conn = (URL(LATEST).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (conn.responseCode == 404) return null // no release yet
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = json.optJSONArray("assets") ?: return null
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") } ?: return null
            return Release(
                version = json.optString("tag_name").removePrefix("v"),
                notes = json.optString("body").trim(),
                apkUrl = apk.optString("browser_download_url"),
                size = apk.optLong("size"),
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun download(release: Release, to: File) {
        val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
            conn.inputStream.use { input ->
                to.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val percent = if (total > 0) (done * 100 / total).toInt() else 0
                        if (percent != shown) {
                            shown = percent
                            _state.value = State.Downloading(release, percent)
                        }
                    }
                }
            }
            if (release.size > 0 && to.length() != release.size) error("size ${to.length()} != ${release.size}")
        } finally {
            conn.disconnect()
        }
    }

    /** "2.10" > "2.9" > "2.0": compares the dot-separated numbers. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
