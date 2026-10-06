package koto.app.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * What the planner did, one line per event, for the log screen: attempts, laptop errors with
 * their cause or HTTP status, which drafts passed. Never the plan's content. Kept apart from the
 * plan files so a new setup doesn't erase it. Every line also goes to logcat.
 */
object PlannerLog {
    private const val TAG = "koto.planner"
    private const val FILE = "planner-log.txt"
    private const val KEEP = 300
    private val TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

    fun add(context: Context, line: String, error: Throwable? = null) {
        if (error != null) Log.e(TAG, line, error) else Log.i(TAG, line)
        val f = File(context.applicationContext.filesDir, FILE)
        synchronized(this) {
            try {
                val old = if (f.exists()) f.readLines() else emptyList()
                val entry = "${LocalDateTime.now().format(TIME)}  ${line.replace('\n', ' ')}"
                f.writeText((old + entry).takeLast(KEEP).joinToString("\n", postfix = "\n"))
            } catch (e: IOException) {
                Log.e(TAG, "cannot write the planner log", e)
            }
        }
    }

    /** The newest [n] lines, newest first. */
    fun recent(context: Context, n: Int): List<String> {
        val f = File(context.applicationContext.filesDir, FILE)
        return synchronized(this) {
            try {
                if (f.exists()) f.readLines().filter { it.isNotBlank() }.takeLast(n).reversed() else emptyList()
            } catch (e: IOException) {
                emptyList()
            }
        }
    }
}
