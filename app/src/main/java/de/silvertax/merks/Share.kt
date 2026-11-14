package de.silvertax.merks

import android.app.Activity
import android.content.Context
import android.content.Intent

/** Entries as plain text, to pass on through a messenger, mail or whatever the phone offers. */
object Share {
    /**
     * "Sa., 14. Nov., 10:00 Uhr: Zahnarzt"; a note is just its text. [start] is the date to show, the next
     * one for a series. What goes with it follows on the next lines, each starting with [indent].
     */
    fun line(ctx: Context, e: Entry, start: Long? = e.start, indent: String = ""): String {
        val head = if (start == null) e.title else Format.whenText(ctx, e, start) + ": " + e.title
        val note = e.note?.takeIf { it.isNotBlank() }?.lines()?.joinToString("") { "\n" + indent + it.trim() }.orEmpty()
        return head + note
    }

    /** What is coming up, one line each, soonest first as given. */
    fun list(ctx: Context, upcoming: List<Pair<Entry, Long?>>): String =
        Lang.context(ctx).getString(R.string.share_heading) + "\n\n" +
            upcoming.joinToString("\n") { (e, start) -> "• " + line(ctx, e, start, indent = "   ") }

    fun send(activity: Activity, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        runCatching { activity.startActivity(Intent.createChooser(send, null)) }
    }
}
