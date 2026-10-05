package koto.app.ui

import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import koto.app.data.Store
import koto.app.spell.SpellScheduler
import koto.core.time.ConfigParse
import koto.core.time.SpellConfig
import koto.core.time.SpellConfigParser

/** The takeover windows as plain text rules. Strictly parsed; nothing is saved until all of it is valid. */
class WindowsActivity : KotoActivity() {
    private lateinit var input: EditText
    private lateinit var messages: LinearLayout
    private lateinit var term: Term

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        term = Term(this)
        term.title("windows")
        term.line("When takeovers may happen. Outside waking hours, in quiet hours and in protected blocks they never do.")
        term.gap(0.5f)
        input = term.input(Store(this).configText ?: SpellConfig.TEMPLATE)
        messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        term.column.addView(messages)
        term.gap(0.5f)
        term.command("save") { save() }
        term.command("back") { finish() }
        term.show()
    }

    private fun save() {
        messages.removeAllViews()
        val text = input.text.toString()
        when (val r = SpellConfigParser.parse(text)) {
            is ConfigParse.Ok -> {
                Store(this).configText = text
                SpellScheduler.tick(this)
                note("saved. ${r.config.windows.protectedBlocks.size} protected blocks.", Pixel.GREY)
            }
            is ConfigParse.Invalid -> r.errors.forEach { note(it.toString(), Pixel.WHITE) }
        }
    }

    private fun note(text: String, color: Int) {
        val line = android.widget.TextView(this).apply {
            this.text = text
            Pixel.style(this, term.small, color)
        }
        messages.addView(line)
    }
}
