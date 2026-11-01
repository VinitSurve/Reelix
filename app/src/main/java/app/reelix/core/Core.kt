package app.reelix.core

import android.util.Log
import app.reelix.BuildConfig

/** Wall-clock abstraction so time-dependent logic (backoff, retries) is testable. */
fun interface Clock {
    fun now(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}

/**
 * Privacy-conscious logging. Only short technical messages are logged and only in debug builds.
 * Never pass URLs, captions, transcripts, notes or secrets to this logger.
 */
object ReelixLog {
    private const val TAG = "Reelix"

    fun d(message: String) {
        if (BuildConfig.DEBUG) runCatching { Log.d(TAG, message) }
    }

    fun w(message: String, error: Throwable? = null) {
        if (BuildConfig.DEBUG) runCatching { Log.w(TAG, "$message${error?.let { ": ${it.javaClass.simpleName}" } ?: ""}") }
    }
}

/** Produces a short, user-safe description of an exception without leaking content. */
fun Throwable.safeDescription(): String {
    val name = javaClass.simpleName.ifBlank { "Error" }
    val msg = message?.take(160)?.replace(Regex("""https?://\S+"""), "<url>")
    return if (msg.isNullOrBlank()) name else "$name: $msg"
}

