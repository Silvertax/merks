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

/** Asks GitHub at most once a day for the latest release. No personal data is sent. */
object Updater {
    private const val REPO_URL = "https://github.com/Silvertax/merks"
    private const val LATEST_API = "https://api.github.com/repos/Silvertax/merks/releases/latest"
    private const val DAY = 24 * 60 * 60 * 1000L

    data class Info(val tag: String, val url: String)

    private fun currentVersion(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"

    /** The update to point out right now, or null. */
    fun pending(ctx: Context): Info? {
        if (!Prefs.updates(ctx)) return null
        val p = Prefs.prefs(ctx)
        val tag = p.getString("update_tag", null) ?: return null
        val url = p.getString("update_url", null) ?: return null
        if (!Versions.newer(tag, currentVersion(ctx)) || p.getString("update_skip", null) == tag) return null
        return Info(tag, url)
    }

    fun skip(ctx: Context, tag: String) = Prefs.prefs(ctx).edit().putString("update_skip", tag).apply()

    fun checkIfDue(ctx: Context, onDone: () -> Unit) {
        if (!Prefs.updates(ctx)) return
        val p = Prefs.prefs(ctx)
        val now = System.currentTimeMillis()
        if (now - p.getLong("update_checked_at", 0) < DAY) return
        p.edit().putLong("update_checked_at", now).apply()
        val app = ctx.applicationContext
        Thread {
            fetch()?.let { info ->
                Prefs.prefs(app).edit().putString("update_tag", info.tag).putString("update_url", info.url).apply()
                onDone()
            }
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
