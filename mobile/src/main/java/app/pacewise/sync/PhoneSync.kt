package app.pacewise.sync

import android.content.Context
import android.util.Log
import app.pacewise.PaceWiseApp
import app.pacewise.reminders.Reminders
import app.pacewise.shared.SyncProtocol
import app.pacewise.shared.WatchPayload
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.tasks.await

private const val TAG = "PaceWiseSync"

object PhoneSync {
    /** Publishes the upcoming workouts and settings. The Data Layer delivers it whenever the watch is reachable. */
    fun pushPlan(context: Context) {
        val app = context.applicationContext as PaceWiseApp
        val payload = app.repo.watchPayload() ?: return
        try {
            val req = PutDataMapRequest.create(SyncProtocol.PATH_PLAN).apply {
                dataMap.putString(SyncProtocol.KEY_JSON, SyncProtocol.json.encodeToString(WatchPayload.serializer(), payload))
                dataMap.putLong(SyncProtocol.KEY_TIME, payload.updatedEpochMs)
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(app).putDataItem(req)
                .addOnFailureListener { Log.w(TAG, "Plan push failed", it) }
        } catch (e: Exception) {
            Log.w(TAG, "Wearable API unavailable", e)
        }
    }

    /** Display name of a connected watch, or null if none is reachable. */
    suspend fun connectedWatch(context: Context): String? = try {
        Wearable.getNodeClient(context).connectedNodes.await().firstOrNull()?.displayName
    } catch (e: Exception) {
        null
    }

    /** Asks the watch to re-send any runs it still holds. Returns false if no watch is reachable. */
    suspend fun requestSync(context: Context): Boolean = try {
        pushPlan(context)
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        nodes.forEach { Wearable.getMessageClient(context).sendMessage(it.id, SyncProtocol.PATH_REQUEST_SYNC, null).await() }
        nodes.isNotEmpty()
    } catch (e: Exception) {
        false
    }
}

class RunListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val app = application as PaceWiseApp
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val item = event.dataItem
            val path = item.uri.path ?: continue
            if (!path.startsWith(SyncProtocol.PATH_RUN_PREFIX)) continue
            try {
                val asset = DataMapItem.fromDataItem(item).dataMap.getAsset(SyncProtocol.KEY_RUN) ?: continue
                val bytes = com.google.android.gms.tasks.Tasks.await(Wearable.getDataClient(this).getFdForAsset(asset))
                    .inputStream.use { it.readBytes() }
                val run = SyncProtocol.decodeRun(bytes)
                val isNew = app.repo.saveRun(run)
                // Tell the watch it can drop its copy.
                item.uri.host?.let { node ->
                    Wearable.getMessageClient(this).sendMessage(node, SyncProtocol.PATH_RUN_ACK, run.id.toByteArray())
                }
                if (isNew) Reminders.notifyRunSynced(this, run)
                PhoneSync.pushPlan(this)
            } catch (e: Exception) {
                Log.e(TAG, "Could not import run $path", e)
            }
        }
    }
}
