package app.pacewise.wear

import android.content.Context
import android.net.Uri
import android.util.Log
import app.pacewise.shared.SyncProtocol
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

private const val TAG = "PaceWiseSync"

object WearSync {
    /**
     * Publishes every unsynced run as a DataItem. The Data Layer holds on to it and delivers
     * it when the phone is next reachable; the phone answers with an ack and the copy is dropped.
     */
    fun uploadPending(context: Context) {
        val store = context.store
        try {
            val client = Wearable.getDataClient(context)
            for (id in store.pendingIds()) {
                val bytes = store.pendingBytes(id) ?: continue
                val req = PutDataMapRequest.create(SyncProtocol.PATH_RUN_PREFIX + id).apply {
                    dataMap.putAsset(SyncProtocol.KEY_RUN, Asset.createFromBytes(bytes))
                    // A fresh timestamp makes sure the phone is notified again on a retry.
                    dataMap.putLong(SyncProtocol.KEY_TIME, System.currentTimeMillis())
                }.asPutDataRequest().setUrgent()
                client.putDataItem(req).addOnFailureListener { Log.w(TAG, "Upload of $id failed", it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Wearable API unavailable", e)
        }
    }

    /** Reads the plan the phone last published, in case the change event was missed. */
    fun fetchPlan(context: Context) {
        try {
            val uri = Uri.Builder().scheme("wear").authority("*").path(SyncProtocol.PATH_PLAN).build()
            Wearable.getDataClient(context).getDataItems(uri, DataClient.FILTER_LITERAL).addOnSuccessListener { items ->
                for (item in items) {
                    DataMapItem.fromDataItem(item).dataMap.getString(SyncProtocol.KEY_JSON)?.let(context.store::savePayload)
                }
                items.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Wearable API unavailable", e)
        }
    }
}

class WearListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED || event.dataItem.uri.path != SyncProtocol.PATH_PLAN) continue
            DataMapItem.fromDataItem(event.dataItem).dataMap.getString(SyncProtocol.KEY_JSON)?.let(store::savePayload)
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            SyncProtocol.PATH_RUN_ACK -> {
                val id = String(event.data)
                store.removePending(id)
                val uri = Uri.Builder().scheme("wear").authority("*").path(SyncProtocol.PATH_RUN_PREFIX + id).build()
                Wearable.getDataClient(this).deleteDataItems(uri)
            }
            SyncProtocol.PATH_REQUEST_SYNC -> WearSync.uploadPending(this)
        }
    }
}
