package de.silvertax.merks

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Versions {
    private fun parts(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V").split('.').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    /** True when [tag] (e.g. "v0.2.0") is a higher version than [current] (e.g. "0.1.0"). */
    fun newer(tag: String, current: String): Boolean {
        val a = parts(tag)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

/**
 * Asks GitHub for the latest release, only when "Check for updates now" is tapped, and on "Update now"
 * downloads its APK and hands it to the phone's installer. No personal data is sent.
 */
object Updater {
    private const val REPO_URL = "https://github.com/Silvertax/merks"
    private const val LATEST_API = "https://api.github.com/repos/Silvertax/merks/releases/latest"
    private const val APK_NAME = "merks.apk"
    private const val DIR = "update"
    private const val MAX_BYTES = 200L * 1024 * 1024

    /** [url] is the release page, [apk] the file itself. */
    data class Info(val tag: String, val url: String, val apk: String = "$REPO_URL/releases/download/$tag/$APK_NAME")

    sealed interface Result {
        data class Newer(val info: Info) : Result
        data object UpToDate : Result
        data object Failed : Result
    }

    fun currentVersion(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"

    /** In the F-Droid version, F-Droid brings the updates; a download from GitHub would not install over it. */
    val available: Boolean get() = !BuildConfig.FDROID

    /** Tests put their own answer here, so that nothing goes to the network. */
    internal var source: (() -> Info?)? = null

    /** Tests put their own download here: it writes the file at the address into [File] and reports per cent. */
    internal var download: ((String, File, (Int) -> Unit) -> Boolean)? = null

    /** Asks in the background; [onDone] is called from that background thread. */
    fun check(ctx: Context, onDone: (Result) -> Unit) {
        val current = currentVersion(ctx)
        val ask = source ?: ::fetch
        Thread {
            val info = ask()
            onDone(
                when {
                    info == null -> Result.Failed
                    Versions.newer(info.tag, current) -> Result.Newer(info)
                    else -> Result.UpToDate
                },
            )
        }.start()
    }

    private fun fetch(): Info? = try {
        val c = URL(LATEST_API).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        if (c.responseCode != 200) {
            null
        } else {
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = o.optString("tag_name")
            val url = o.optString("html_url")
            val assets = o.optJSONArray("assets")
            val apk = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
                .firstOrNull { it.optString("name") == APK_NAME }?.optString("browser_download_url").orEmpty()
            // Only ever open a page or load a file of this repository.
            when {
                tag.isEmpty() -> null
                else -> {
                    val page = if (url.startsWith("$REPO_URL/")) url else "$REPO_URL/releases/latest"
                    if (apk.startsWith("$REPO_URL/releases/download/")) Info(tag, page, apk) else Info(tag, page)
                }
            }
        }
    } catch (e: Exception) {
        null
    }

    /** Where the update for [info] is kept until it is installed. */
    private fun apkFile(ctx: Context, tag: String): File =
        File(File(ctx.cacheDir, DIR), "merks-" + tag.removePrefix("v").filter { it.isLetterOrDigit() || it == '.' } + ".apk")

    /**
     * True when [apk] really is this app and not older than the installed one. Whether it carries the
     * right signature is checked by the phone's installer, which refuses anything else.
     */
    private fun fits(ctx: Context, apk: File): Boolean = try {
        val pm = ctx.packageManager
        val found = pm.getPackageArchiveInfo(apk.path, 0)
        val here = pm.getPackageInfo(ctx.packageName, 0)
        found != null && found.packageName == ctx.packageName &&
            PackageInfoCompat.getLongVersionCode(found) >= PackageInfoCompat.getLongVersionCode(here)
    } catch (e: Exception) {
        false
    }

    /** The update for [tag] if it has been downloaded completely. */
    fun downloaded(ctx: Context, tag: String): File? = apkFile(ctx, tag).takeIf { it.isFile && fits(ctx, it) }

    /**
     * Downloads the update in the background unless it is already there. [onProgress] gets per cent
     * (or -1 while the size is unknown), [onDone] the file or null; both are called from that background thread.
     */
    fun fetchApk(ctx: Context, info: Info, onProgress: (Int) -> Unit, onDone: (File?) -> Unit) {
        val app = ctx.applicationContext
        val load = download ?: ::load
        Thread {
            downloaded(app, info.tag)?.let {
                onDone(it)
                return@Thread
            }
            val target = apkFile(app, info.tag)
            val dir = target.parentFile!!
            // Older downloads are of no use any more.
            dir.listFiles()?.forEach { it.delete() }
            dir.mkdirs()
            val part = File(dir, "download.part")
            val ok = info.apk.startsWith("$REPO_URL/releases/download/") &&
                runCatching { load(info.apk, part, onProgress) }.getOrDefault(false) &&
                part.renameTo(target) && fits(app, target)
            if (!ok) {
                part.delete()
                target.delete()
            }
            onDone(target.takeIf { ok })
        }.start()
    }

    private fun load(address: String, to: File, onProgress: (Int) -> Unit): Boolean {
        val c = URL(address).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 20000
        try {
            if (c.responseCode != 200) return false
            val total = c.contentLengthLong
            if (total > MAX_BYTES) return false
            var done = 0L
            var shown = -2
            c.inputStream.use { input ->
                to.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (done > MAX_BYTES) return false
                        val percent = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else -1
                        if (percent != shown) {
                            shown = percent
                            onProgress(percent)
                        }
                    }
                }
            }
            return total <= 0 || done == total
        } finally {
            c.disconnect()
        }
    }

    /** Whether the phone lets this app hand over an update; the user allows that once in the system settings. */
    fun mayInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** The system page with the switch that allows it. */
    fun allowIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", ctx.packageName, null))

    /** Opens the phone's installer with [apk]; it asks before it installs anything. */
    fun installIntent(ctx: Context, apk: File): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", apk), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /** After an update the downloaded file is of no use any more. */
    fun cleanUp(ctx: Context) {
        File(ctx.cacheDir, DIR).listFiles()?.forEach { it.delete() }
        Prefs.setPendingUpdate(ctx, null)
    }
}
