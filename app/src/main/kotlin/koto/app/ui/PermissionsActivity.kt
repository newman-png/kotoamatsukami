package koto.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings

class PermissionsActivity : KotoActivity() {

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val term = Term(this)
        term.title("permissions")
        term.line("Tap a line to fix it.")
        term.gap()
        for (p in Permissions.list(this)) {
            val mark = if (p.ok) "[ok]" else "[  ]"
            val need = when (p.need) {
                Need.REQUIRED -> ""
                Need.RECOMMENDED -> "  recommended"
                Need.OPTIONAL -> "  optional"
            }
            term.command("$mark ${p.label}$need", if (p.ok) Pixel.GREY else Pixel.WHITE, term.small) { p.fix(this) }
            if (!p.ok) term.line(p.why, Pixel.GREY)
        }
        term.gap()
        term.line("Sideloaded apps on Android 13+ must allow restricted settings before the guard can be switched on.", Pixel.GREY)
        term.command("app info", size = term.small) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        term.gap()
        term.command("back") { finish() }
        term.show()
    }

    @Deprecated("Framework callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == Permissions.REQUEST_CODE) Permissions.onResult(this, permissions, grantResults)
        render()
    }

    override fun onEscaped() = render()
}
