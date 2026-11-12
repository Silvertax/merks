package de.silvertax.merks

import android.content.Context
import org.json.JSONObject
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

/** Asks GitHub for the latest release, only when "Check for updates now" is tapped. No personal data is sent. */
object Updater {
    private const val REPO_URL = "https://github.com/Silvertax/merks"
    private const val LATEST_API = "https://api.github.com/repos/Silvertax/merks/releases/latest"

    data class Info(val tag: String, val url: String)

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
            // Only ever open a page of this repository.
            if (tag.isEmpty()) null else Info(tag, if (url.startsWith("$REPO_URL/")) url else "$REPO_URL/releases/latest")
        }
    } catch (e: Exception) {
        null
    }
}
