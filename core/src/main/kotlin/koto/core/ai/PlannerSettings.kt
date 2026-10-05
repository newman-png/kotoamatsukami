package koto.core.ai

import kotlinx.serialization.Serializable

/** Where the planning model lives. Kept on the phone only; never in the repository. */
@Serializable
data class PlannerSettings(
    /** The laptop's address on the home network, e.g. 192.168.1.20. */
    val host: String,
    val port: Int = DEFAULT_PORT,
    /** Used for master planning and the critic. */
    val model: String = DEFAULT_MODEL,
    /** Used for the nightly tasks; empty means the same model. */
    val nightModel: String = "",
) {
    fun modelFor(purpose: Purpose): String = if (purpose == Purpose.WRITE && nightModel.isNotBlank()) nightModel else model

    companion object {
        const val DEFAULT_PORT = 11434
        const val DEFAULT_MODEL = "qwen3:14b"
    }
}

/** What the planner is doing, for the main screen. Never anything about the plan's content. */
@Serializable
data class PlannerStatus(
    val lastAttemptMs: Long = 0,
    val lastSuccessMs: Long = 0,
    /** A short plain sentence about the last problem, or empty. */
    val note: String = "",
    /** The day whose night work (re-plan and books) is finished. */
    val nightDoneFor: String? = null,
    /** The last week re-planned in code, and by the model. */
    val codeReplanWeek: Int = 0,
    val aiReplanWeek: Int = 0,
)

/** Only addresses on the home network (or this device) may be planned on. */
object LanAddress {
    /** True for loopback, private (10/8, 172.16/12, 192.168/16), link-local, CGNAT/Tailscale (100.64/10) and IPv6 ULA. */
    fun isPrivate(address: ByteArray): Boolean {
        val b = address.map { it.toInt() and 0xFF }
        return when (b.size) {
            4 -> b[0] == 127 || b[0] == 10 || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168) ||
                (b[0] == 169 && b[1] == 254) || (b[0] == 100 && b[1] in 64..127)
            16 -> isLoopback6(b) || (b[0] and 0xFE) == 0xFC || (b[0] == 0xFE && (b[1] and 0xC0) == 0x80)
            else -> false
        }
    }

    private fun isLoopback6(b: List<Int>) = b.take(15).all { it == 0 } && b[15] == 1
}
