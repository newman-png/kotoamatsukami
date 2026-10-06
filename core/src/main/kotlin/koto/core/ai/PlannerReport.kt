package koto.core.ai

/**
 * How planner events are worded for the in-app planner log and the main screen. Errors keep
 * their cause; draft reports keep which rules failed but never the plan's numbers, which stay
 * hidden from the user (they go to logcat only).
 */
object PlannerReport {
    const val SHORT = 140

    /** "IllegalStateException: missing prompt resource planner_user (Prompts.kt:22)", with causes. */
    fun describe(t: Throwable): String {
        val parts = ArrayList<String>()
        var e: Throwable? = t
        val seen = HashSet<Throwable>()
        while (e != null && seen.add(e) && parts.size < 3) {
            val where = e.stackTrace.firstOrNull { it.className.startsWith("koto.") }
                ?.let { " (${it.fileName ?: it.className.substringAfterLast('.')}:${it.lineNumber})" }.orEmpty()
            parts += e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "") + where
            e = e.cause
        }
        return parts.joinToString(", caused by ")
    }

    /** Cut to one short line for the main screen. */
    fun short(text: String): String {
        val line = text.lineSequence().firstOrNull().orEmpty()
        return if (line.length <= SHORT) line else line.take(SHORT - 3) + "..."
    }

    private val RULE = Regex("\\[([A-Z_]+)\\]")

    /**
     * A [MasterPlanner] report line made safe for the user's eyes: a rejected draft keeps the
     * names of the rules it broke, not the numbers. Every other line is safe as it is.
     */
    fun visible(line: String): String {
        val i = line.indexOf(": rejected:")
        if (i < 0) return line
        val rules = RULE.findAll(line).map { it.groupValues[1] }.distinct().toList()
        return line.substring(0, i) + ": rejected (" + (rules.ifEmpty { listOf("intent checks") }.joinToString()) + ")"
    }
}
