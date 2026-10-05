package koto.app.data

import android.content.Context
import android.content.SharedPreferences
import koto.core.time.ConfigParse
import koto.core.time.DayPlan
import koto.core.time.SpellConfig
import koto.core.time.SpellConfigParser
import kotlinx.serialization.json.Json

/** Ordinary app state (main process only). Safety-critical state lives in SafetyFiles instead. */
class Store(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("koto", Context.MODE_PRIVATE)

    /** The raw windows config text, exactly as the user wrote it. */
    var configText: String?
        get() = prefs.getString(KEY_CONFIG, null)
        set(value) = prefs.edit().putString(KEY_CONFIG, value).apply()

    /** The parsed config, or null if none was saved (a saved config is always valid). Cached per text. */
    fun config(): SpellConfig? {
        val text = configText ?: return null
        synchronized(Store) {
            if (text != cachedText) {
                cachedConfig = (SpellConfigParser.parse(text) as? ConfigParse.Ok)?.config
                cachedText = text
            }
            return cachedConfig
        }
    }

    var consented: Boolean
        get() = prefs.getBoolean(KEY_CONSENT, false)
        set(value) = prefs.edit().putBoolean(KEY_CONSENT, value).apply()

    fun plan(): DayPlan? = prefs.getString(KEY_PLAN, null)?.let {
        runCatching { json.decodeFromString(DayPlan.serializer(), it) }.getOrNull()
    }

    fun savePlan(plan: DayPlan?) {
        prefs.edit().putString(KEY_PLAN, plan?.let { json.encodeToString(DayPlan.serializer(), it) }).apply()
    }

    fun recentTaskIds(): List<String> =
        prefs.getString(KEY_RECENT, "").orEmpty().split(',').filter { it.isNotEmpty() }

    fun pushRecentTask(id: String) {
        val list = (listOf(id) + recentTaskIds().filter { it != id }).take(RECENT_KEEP)
        prefs.edit().putString(KEY_RECENT, list.joinToString(",")).apply()
    }

    /** Wall time the phone was last seen entering a vehicle, or 0 when not driving. */
    var vehicleSinceMs: Long
        get() = prefs.getLong(KEY_VEHICLE, 0)
        set(value) = prefs.edit().putLong(KEY_VEHICLE, value).apply()

    private companion object {
        var cachedText: String? = null
        var cachedConfig: SpellConfig? = null

        const val KEY_CONFIG = "config_text"
        const val KEY_CONSENT = "consented"
        const val KEY_PLAN = "day_plan"
        const val KEY_RECENT = "recent_tasks"
        const val KEY_VEHICLE = "vehicle_since"
        const val RECENT_KEEP = 3
        val json = Json { ignoreUnknownKeys = true }
    }
}
