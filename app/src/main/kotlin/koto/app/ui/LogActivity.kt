package koto.app.ui

import android.os.Bundle
import koto.app.data.SpellLog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The raw record of what happened: results only, never what is planned. A plain check that
 * logging works until the weekly report (Layer 4) replaces this screen.
 */
class LogActivity : KotoActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val term = Term(this)
        term.title("log")
        term.line("Reading.", Pixel.GREY)
        term.show()
        val dayStart = java.time.LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        SpellLog.recent(this, LIMIT, dayStart) { rows, opensToday -> render(rows, opensToday) }
    }

    private fun render(rows: List<koto.app.data.SpellRow>, opensToday: Int) {
        val term = Term(this)
        term.title("log")
        term.line("Distraction-app opens today: $opensToday.", Pixel.GREY)
        term.line("Marks today: ${rows.count { it.mark && isToday(it.startedAt) }}.", Pixel.GREY)
        term.gap(0.5f)
        if (rows.isEmpty()) term.line("Nothing yet.")
        for (r in rows) {
            val parts = listOfNotNull(
                TIME.format(Instant.ofEpochMilli(r.startedAt).atZone(ZoneId.systemDefault())),
                r.task + if (r.floor) " (floor)" else "",
                r.outcome ?: "running",
                r.latencyMs?.let { "%.1fs".format(it / 1000.0) },
                r.feedback?.label,
                r.source.takeIf { it != "schedule" },
                "mark".takeIf { r.mark },
            )
            term.line(parts.joinToString("  "), if (r.outcome == null) Pixel.WHITE else Pixel.GREY, term.small)
        }
        term.gap()
        term.command("back") { finish() }
        term.show()
    }

    private fun isToday(atMs: Long): Boolean =
        Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()).toLocalDate() == java.time.LocalDate.now()

    private companion object {
        const val LIMIT = 40
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    }
}
