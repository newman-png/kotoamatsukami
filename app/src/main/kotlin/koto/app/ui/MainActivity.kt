package koto.app.ui

import android.content.Intent
import koto.app.data.Store
import koto.app.safety.Safety
import koto.app.spell.KotoService
import koto.app.spell.SpellScheduler
import koto.core.art.EyeForm

/**
 * The only screen the user normally sees outside a takeover. Shows state, never the plan.
 * There is no "off" command while armed: the escape hatch is the way out.
 */
class MainActivity : KotoActivity() {
    private var note: String? = null

    override fun onResume() {
        super.onResume()
        if (Safety.isArmed(this)) {
            // Self-heal in case an aggressive OEM killed the service or dropped the alarm.
            KotoService.start(this)
            SpellScheduler.tick(this)
        }
        render()
    }

    override fun onEscaped() = render()

    private fun render() {
        val store = Store(this)
        val files = Safety.files(this)
        val armed = Safety.isArmed(this)
        val term = Term(this)

        term.title("kotoamatsukami")
        term.gap()
        term.eye(EyeForm.BARE)
        term.gap()
        val status = when {
            files.safeMode != null -> "safe mode."
            store.config() == null -> "not set up."
            !store.consented -> "not armed."
            files.disabled -> "off."
            else -> "armed."
        }
        term.line(status, Pixel.WHITE, term.large)
        if (files.safeMode != null) term.line("Takeovers stopped after repeated failures.", Pixel.GREY)
        val missing = Permissions.missingRequired(this).size
        if (missing > 0) term.line("$missing permission${if (missing == 1) "" else "s"} missing.", Pixel.GREY)
        note?.let { term.line(it, Pixel.GREY) }
        term.gap()

        term.command("permissions") { startActivity(Intent(this, PermissionsActivity::class.java)) }
        term.command("windows") { startActivity(Intent(this, WindowsActivity::class.java)) }
        if (!armed) {
            term.command("arm") {
                when {
                    store.config() == null -> startActivity(Intent(this, WindowsActivity::class.java))
                    !store.consented -> startActivity(Intent(this, ConsentActivity::class.java))
                    else -> {
                        Safety.arm(this)
                        note = null
                        render()
                    }
                }
            }
        } else {
            term.command("test spell") {
                SpellScheduler.scheduleTest(this)
                note = "A takeover comes in ${SpellScheduler.TEST_DELAY_MS / 1000} seconds. Leave the app if you like."
                render()
            }
        }
        term.show()
    }
}
