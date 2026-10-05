package koto.app.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.TextView
import koto.app.ai.OllamaClient
import koto.core.ai.PlannerSettings

/**
 * Where the planning model runs: the laptop's address on the home network and the model names.
 * Shared by the setup flow and the laptop screen.
 */
class LaptopForm(private val activity: Activity, private val term: Term, initial: PlannerSettings?) {
    private val host: EditText
    private val port: EditText
    private val model: EditText
    private val nightModel: EditText
    private val result: TextView

    init {
        term.line("Laptop address on your home network:", Pixel.GREY)
        host = term.field(initial?.host.orEmpty(), "192.168.1.20")
        term.line("Port:", Pixel.GREY)
        port = term.field((initial?.port ?: PlannerSettings.DEFAULT_PORT).toString(), numeric = true)
        term.line("Model for the plan:", Pixel.GREY)
        model = term.field(initial?.model ?: PlannerSettings.DEFAULT_MODEL)
        term.line("Model for the nightly tasks (empty: the same):", Pixel.GREY)
        nightModel = term.field(initial?.nightModel.orEmpty())
        result = term.line("", Pixel.GREY)
        term.command("test the laptop", size = term.small) { test() }
    }

    /** The settings, or null with the reason shown. */
    fun read(): PlannerSettings? {
        val h = host.text.toString().trim()
        val p = port.text.toString().trim().toIntOrNull()
        val m = model.text.toString().trim()
        val problem = when {
            h.isEmpty() || ' ' in h -> "Enter the laptop's address, like 192.168.1.20."
            p == null || p !in 1..65535 -> "The port is a number, normally ${PlannerSettings.DEFAULT_PORT}."
            m.isEmpty() || ' ' in m -> "Enter a model name, like ${PlannerSettings.DEFAULT_MODEL}."
            else -> null
        }
        if (problem != null) {
            result.text = problem
            return null
        }
        return PlannerSettings(h, p!!, m, nightModel.text.toString().trim())
    }

    private fun test() {
        val settings = read() ?: return
        result.text = "Testing."
        val main = Handler(Looper.getMainLooper())
        Thread({
            val answer = runCatching { OllamaClient(settings).check() }.getOrElse { "Not reached (${it.javaClass.simpleName})." }
            main.post { if (!activity.isFinishing) result.text = answer }
        }, "koto-laptop-test").start()
    }
}
