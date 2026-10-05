package koto.app.ui

import android.os.Bundle
import koto.app.data.Store
import koto.app.safety.Safety

/** The last setup step. Says plainly what will happen and how to get out, then arms. */
class ConsentActivity : KotoActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val term = Term(this)
        term.title("consent")
        term.gap()
        term.line("From now on this phone will be taken over at random moments inside your waking hours.")
        term.line("Never in a protected block or in quiet hours. Never during a call. Never while driving.")
        term.gap(0.5f)
        term.line("Each takeover is one small command. Do it. The beat stops when you obey.")
        term.line("Skipping is possible.")
        term.gap()
        term.line("The way out, if you ever need it:", Pixel.GREY)
        term.line("volume up, down, up, down, up, down, up, down.")
        term.line("Or hold two fingers still on any Kotoamatsukami screen for six seconds.")
        term.line("Either one turns everything off until you arm it again.", Pixel.GREY)
        term.gap(0.5f)
        term.line("Emergency calls always work.", Pixel.GREY)
        term.gap()

        val missing = Permissions.missingRequired(this)
        if (missing.isNotEmpty()) {
            term.line("Missing: ${missing.joinToString(", ") { it.label }}. Takeovers may not reach you.", Pixel.GREY)
            term.gap(0.5f)
        }
        term.command("I understand. arm.") {
            Store(this).consented = true
            Safety.arm(this)
            finish()
        }
        term.command("not yet", Pixel.GREY) { finish() }
        term.show()
    }
}
