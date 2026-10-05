package koto.app.ui

import android.os.Bundle
import koto.app.ai.PlannerRunner
import koto.app.data.PlanStore

/** Where the laptop is. Changing it never shows or changes the plan. */
class LaptopActivity : KotoActivity() {
    private lateinit var form: LaptopForm

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = PlanStore(this)
        val term = Term(this)
        term.title("laptop")
        term.line("Plans and nightly tasks are made by Ollama on your laptop. Only a short summary is sent, and only inside your home network.", Pixel.GREY)
        term.gap(0.5f)
        form = LaptopForm(this, term, store.settings())
        term.gap()
        val saved = term.line("", Pixel.GREY)
        term.command("save") {
            val s = form.read() ?: return@command
            store.saveSettings(s)
            PlannerRunner.schedule(this)
            saved.text = "Saved."
        }
        if (store.profile() != null) {
            term.command("try now", Pixel.GREY, term.small) {
                val s = form.read() ?: return@command
                store.saveSettings(s)
                PlannerRunner.kick(this)
                saved.text = "Working in the background. It can take a long time."
            }
        }
        term.command("back", Pixel.GREY) { finish() }
        term.show()
    }
}
