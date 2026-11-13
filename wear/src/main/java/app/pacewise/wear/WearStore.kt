package app.pacewise.wear

import android.app.Application
import android.content.Context
import app.pacewise.shared.RunRecord
import app.pacewise.shared.SyncProtocol
import app.pacewise.shared.WatchPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class WearApp : Application() {
    lateinit var store: WearStore
        private set

    override fun onCreate() {
        super.onCreate()
        store = WearStore(this)
    }
}

val Context.store: WearStore get() = (applicationContext as WearApp).store

/** The plan received from the phone and the runs still waiting to be handed over. */
class WearStore(context: Context) {
    private val planFile = File(context.filesDir, "plan.json")
    private val pendingDir = File(context.filesDir, "pending").apply { mkdirs() }

    private val _payload = MutableStateFlow(readPayload())
    val payload: StateFlow<WatchPayload> = _payload

    private val _pending = MutableStateFlow(pendingIds().size)
    val pendingCount: StateFlow<Int> = _pending

    private fun readPayload(): WatchPayload = try {
        if (planFile.exists()) SyncProtocol.json.decodeFromString(WatchPayload.serializer(), planFile.readText()) else WatchPayload()
    } catch (e: Exception) {
        WatchPayload()
    }

    fun savePayload(json: String) {
        val p = try {
            SyncProtocol.json.decodeFromString(WatchPayload.serializer(), json)
        } catch (e: Exception) {
            return
        }
        if (p.updatedEpochMs < _payload.value.updatedEpochMs) return
        planFile.writeText(json)
        _payload.value = p
    }

    fun addPending(run: RunRecord) {
        File(pendingDir, "${run.id}.gz").writeBytes(SyncProtocol.encodeRun(run))
        _pending.value = pendingIds().size
    }

    fun pendingIds(): List<String> = pendingDir.listFiles()?.map { it.nameWithoutExtension }.orEmpty()

    fun pendingBytes(id: String): ByteArray? = File(pendingDir, "$id.gz").takeIf { it.exists() }?.readBytes()

    fun removePending(id: String) {
        File(pendingDir, "$id.gz").delete()
        _pending.value = pendingIds().size
    }
}
