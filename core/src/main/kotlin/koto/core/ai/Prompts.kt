package koto.core.ai

/**
 * Prompt texts live in resources (koto/prompts, one .txt file each) so they can change without touching code.
 * Placeholders are {{name}}; filling leaves none behind, or fails loudly.
 */
object Prompts {
    const val PLANNER_SYSTEM = "planner_system"
    const val PLANNER_USER = "planner_user"
    const val REPLAN = "replan"
    const val REPAIR = "repair"
    const val CRITIC_SYSTEM = "critic_system"
    const val CRITIC_USER = "critic_user"
    const val WRITER_SYSTEM = "writer_system"
    const val WRITER_USER = "writer_user"

    private val cache = HashMap<String, String>()

    /** Every brace escaped: Android's regex engine (ICU) rejects a bare "}" that the JVM accepts. */
    private val PLACEHOLDER = Regex("\\{\\{(\\w+)\\}\\}")

    fun text(name: String): String = synchronized(cache) {
        cache.getOrPut(name) {
            val stream = Prompts::class.java.getResourceAsStream("/koto/prompts/$name.txt")
                ?: error("missing prompt resource $name")
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trimEnd() + "\n"
        }
    }

    fun fill(name: String, values: Map<String, String>): String {
        var out = text(name)
        for ((k, v) in values) out = out.replace("{{$k}}", v)
        val left = PLACEHOLDER.find(out)
        check(left == null) { "prompt $name: no value for ${left!!.value}" }
        return out
    }
}
