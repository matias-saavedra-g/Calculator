package com.mattia.calculator.engine

/**
 * Messenger protocol between the UI process and the `:engine` process.
 *
 * Request  : Message(what = MSG_*, arg1 = request id, data = {text, ans, quick}, replyTo = client)
 * Response : Message(what = same, arg1 = same id, arg2 = engine pid, data = {json} or {error})
 */
internal object EngineProtocol {
    const val MSG_WARMUP = 1
    const val MSG_EVAL = 2

    const val KEY_TEXT = "text"
    const val KEY_ANS = "ans"
    const val KEY_QUICK = "quick"
    const val KEY_JSON = "json"
    const val KEY_ERROR = "error"
}
