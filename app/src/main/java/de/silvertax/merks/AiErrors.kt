package de.silvertax.merks

import android.content.Context
import de.silvertax.merks.parse.AiError
import de.silvertax.merks.parse.AiProblem

/** An AI error in plain words, in the app's language. */
fun AiError.text(ctx: Context): String {
    val c = Lang.context(ctx)
    return when (problem) {
        AiProblem.BAD_MODEL_NAME -> c.getString(R.string.ai_err_bad_name)
        AiProblem.KEY -> c.getString(R.string.ai_err_key)
        AiProblem.FORBIDDEN -> c.getString(R.string.ai_err_forbidden)
        AiProblem.MODEL_NOT_FOUND -> c.getString(R.string.ai_err_model)
        AiProblem.NO_CREDIT -> c.getString(R.string.ai_err_credit, code)
        AiProblem.RATE_LIMIT -> c.getString(R.string.ai_err_rate)
        AiProblem.OVERLOADED -> c.getString(R.string.ai_err_overloaded)
        AiProblem.SERVER -> c.getString(R.string.ai_err_server, code)
        AiProblem.HTTP -> c.getString(R.string.ai_err_http, code, detail)
        AiProblem.NO_CONNECTION -> c.getString(R.string.ai_err_connection, detail)
        AiProblem.ONLY_THINKING -> c.getString(R.string.ai_err_only_thinking)
        AiProblem.NO_TEXT -> c.getString(R.string.ai_err_no_text)
        AiProblem.UNREADABLE -> c.getString(R.string.ai_err_unreadable)
    }
}
