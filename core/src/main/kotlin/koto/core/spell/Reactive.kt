package koto.core.spell

/** Rule for reactive spells: the [opens]-th distraction-app open within [windowMinutes] triggers one. */
data class ReactiveRule(val opens: Int = DEFAULT_OPENS, val windowMinutes: Int = DEFAULT_WINDOW_MINUTES) {
    companion object {
        const val DEFAULT_OPENS = 3
        const val DEFAULT_WINDOW_MINUTES = 60
    }
}

/**
 * Catches the user in the act. Feed it every time a distraction app comes to the front from a
 * different app. Opens of all distraction apps count together. After it fires it stays quiet for
 * [cooldownMs] and starts counting from zero.
 */
class ReactiveDetector(
    private val rule: ReactiveRule,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {
    private val opens = ArrayDeque<Long>()
    private var lastFiredMs: Long? = null

    /** Returns true when this open should trigger a reactive takeover. */
    fun onOpen(atMs: Long): Boolean {
        opens.addLast(atMs)
        val windowMs = rule.windowMinutes * 60_000L
        while (opens.isNotEmpty() && opens.first() <= atMs - windowMs) opens.removeFirst()
        val last = lastFiredMs
        if (last != null && atMs - last < cooldownMs) return false
        if (opens.size < rule.opens) return false
        lastFiredMs = atMs
        opens.clear()
        return true
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 20 * 60_000L
    }
}

/** Short names for common distraction apps, so the config can say `distract instagram tiktok`. */
object Distractions {
    val ALIASES: Map<String, List<String>> = mapOf(
        "instagram" to listOf("com.instagram.android"),
        "threads" to listOf("com.instagram.barcelona"),
        "tiktok" to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.ss.android.ugc.aweme"),
        "youtube" to listOf("com.google.android.youtube"),
        "reddit" to listOf("com.reddit.frontpage"),
        "x" to listOf("com.twitter.android"),
        "twitter" to listOf("com.twitter.android"),
        "facebook" to listOf("com.facebook.katana", "com.facebook.lite"),
        "snapchat" to listOf("com.snapchat.android"),
        "pinterest" to listOf("com.pinterest"),
        "netflix" to listOf("com.netflix.mediaclient"),
        "twitch" to listOf("tv.twitch.android.app"),
        "tumblr" to listOf("com.tumblr"),
        "9gag" to listOf("com.ninegag.android.app"),
        "discord" to listOf("com.discord"),
    )

    val DEFAULT_NAMES = listOf("instagram", "threads", "tiktok", "youtube", "reddit", "x", "facebook", "snapchat")

    val DEFAULT_PACKAGES: Set<String> = DEFAULT_NAMES.flatMap { ALIASES.getValue(it) }.toSet()

    /** Packages for a config word: a known alias, or a raw package name like com.example.app. */
    fun resolve(word: String): List<String>? {
        val w = word.lowercase()
        ALIASES[w]?.let { return it }
        return if (Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+$").matches(w)) listOf(w) else null
    }
}
