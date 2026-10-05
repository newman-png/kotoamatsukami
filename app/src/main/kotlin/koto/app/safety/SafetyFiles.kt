package koto.app.safety

import android.content.Context
import android.util.AtomicFile
import koto.core.safety.Failure
import koto.core.safety.FailureLedger
import koto.core.safety.LockLease
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Safety-critical state as small files, so the main process and the watchdog process see the
 * same truth without caching. Every read tolerates corruption by returning the safe default:
 * no lease, not disabled, no failures.
 */
class SafetyFiles(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "safety").apply { mkdirs() }
    private val lease = AtomicFile(File(dir, "lease.json"))
    private val heartbeat = File(dir, "heartbeat")
    private val disabledFlag = File(dir, "disabled")
    private val safeModeFlag = File(dir, "safemode")
    private val failureLog = File(dir, "failures.log")

    fun readLease(): LockLease? = try {
        json.decodeFromString(LockLease.serializer(), String(lease.readFully(), Charsets.UTF_8))
    } catch (e: IOException) {
        null
    } catch (e: RuntimeException) {
        clearLease() // corrupt: the safe reading of a corrupt lease is "no lease"
        null
    }

    fun writeLease(value: LockLease) {
        val out = lease.startWrite()
        try {
            out.write(json.encodeToString(LockLease.serializer(), value).toByteArray(Charsets.UTF_8))
            lease.finishWrite(out)
        } catch (e: IOException) {
            lease.failWrite(out)
            throw e
        }
    }

    fun clearLease() {
        lease.delete()
        heartbeat.delete()
    }

    /** Main-thread liveness, in elapsed realtime, written while a lease is held. */
    fun writeHeartbeat(elapsedMs: Long) {
        runCatching { heartbeat.writeText(elapsedMs.toString()) }
    }

    fun readHeartbeat(): Long? = runCatching { heartbeat.readText().trim().toLong() }.getOrNull()

    /** Set by the escape hatch. Survives reboot. Only arming clears it. */
    var disabled: Boolean
        get() = disabledFlag.exists()
        set(value) {
            if (value) disabledFlag.writeText(System.currentTimeMillis().toString()) else disabledFlag.delete()
        }

    /** Reason for safe mode, or null when not in safe mode. */
    var safeMode: String?
        get() = if (safeModeFlag.exists()) runCatching { safeModeFlag.readText() }.getOrDefault("unknown") else null
        set(value) {
            if (value != null) safeModeFlag.writeText(value) else safeModeFlag.delete()
        }

    fun appendFailure(failure: Failure) {
        runCatching {
            FileOutputStream(failureLog, true).use { it.write(FailureLedger.line(failure).toByteArray(Charsets.UTF_8)) }
        }
    }

    fun failures(): List<Failure> =
        runCatching { FailureLedger.parse(failureLog.readText()) }.getOrDefault(emptyList())

    fun clearFailures() {
        failureLog.delete()
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
