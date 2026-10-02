package android.util

import java.util.logging.Level
import java.util.logging.Logger

/**
 * Desktop stand-in for Android's `android.util.Log`, so sources shared with the
 * Android app compile unchanged. Routes to java.util.logging.
 */
object Log {
    private fun log(level: Level, tag: String, msg: String, tr: Throwable? = null): Int {
        Logger.getLogger(tag).log(level, msg, tr)
        return 0
    }

    fun v(tag: String, msg: String): Int = log(Level.FINEST, tag, msg)
    fun d(tag: String, msg: String): Int = log(Level.FINE, tag, msg)
    fun d(tag: String, msg: String, tr: Throwable?): Int = log(Level.FINE, tag, msg, tr)
    fun i(tag: String, msg: String): Int = log(Level.INFO, tag, msg)
    fun i(tag: String, msg: String, tr: Throwable?): Int = log(Level.INFO, tag, msg, tr)
    fun w(tag: String, msg: String): Int = log(Level.WARNING, tag, msg)
    fun w(tag: String, msg: String, tr: Throwable?): Int = log(Level.WARNING, tag, msg, tr)
    fun e(tag: String, msg: String): Int = log(Level.SEVERE, tag, msg)
    fun e(tag: String, msg: String, tr: Throwable?): Int = log(Level.SEVERE, tag, msg, tr)
}
