package koto.core.safety

/** Why the app recorded an abnormal termination. */
enum class FailureKind(val code: String) {
    CRASH("crash"),
    STALE_LEASE("stale-lease"),
    WATCHDOG_KILL("watchdog-kill"),
    ;

    companion object {
        fun fromCode(code: String): FailureKind? = entries.firstOrNull { it.code == code }
    }
}

data class Failure(val atWallMs: Long, val kind: FailureKind)

/**
 * Failure ledger format: one `<epochMillis> <code>` per line. Append-only, so two processes
 * can both write to it. Unparseable lines are ignored rather than trusted.
 */
object FailureLedger {
    fun line(failure: Failure): String = "${failure.atWallMs} ${failure.kind.code}\n"

    fun parse(text: String): List<Failure> = text.lineSequence().mapNotNull { raw ->
        val parts = raw.trim().split(' ')
        if (parts.size != 2) return@mapNotNull null
        val at = parts[0].toLongOrNull() ?: return@mapNotNull null
        val kind = FailureKind.fromCode(parts[1]) ?: return@mapNotNull null
        Failure(at, kind)
    }.toList()

    /** Keeps only failures that can still count towards safe mode, for compaction. */
    fun recent(failures: List<Failure>, nowWallMs: Long): List<Failure> =
        failures.filter { nowWallMs - it.atWallMs in 0..SafeModePolicy.WINDOW_MS }
}

object SafeModePolicy {
    const val WINDOW_MS = 6 * 60 * 60 * 1000L
    const val THRESHOLD = 3

    fun shouldEnter(failures: List<Failure>, nowWallMs: Long): Boolean =
        FailureLedger.recent(failures, nowWallMs).size >= THRESHOLD
}
