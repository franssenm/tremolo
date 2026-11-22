package app.tremolo.wear

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.tremolo.shared.protocol.ActiveTestRequest
import app.tremolo.shared.protocol.ProtocolJson
import app.tremolo.shared.protocol.TrialConfig
import app.tremolo.shared.protocol.WearPaths
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.tasks.await

/** Receives trial changes, acknowledgements and test requests from the phone. */
class PhoneListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.dataItem.uri.path != WearPaths.TRIAL) continue
            val config = if (event.type == DataEvent.TYPE_DELETED) null else TrialSync.parse(event.dataItem)
            TrialSync.apply(this, config)
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WearPaths.ACK -> {
                // The phone has stored this item, so the watch copy can go.
                val path = String(event.data, Charsets.UTF_8)
                if (path.startsWith(WearPaths.WINDOW_PREFIX) || path.startsWith(WearPaths.SKIP_PREFIX) ||
                    path.startsWith(WearPaths.EVENT_PREFIX)
                ) {
                    val uri = Uri.Builder().scheme("wear").path(path).build()
                    Wearable.getDataClient(this).deleteDataItems(uri)
                }
            }
            WearPaths.ACTIVE_TEST -> {
                val request = runCatching {
                    ProtocolJson.decodeFromString(
                        ActiveTestRequest.serializer(), String(event.data, Charsets.UTF_8),
                    )
                }.getOrNull() ?: return
                CaptureService.startTest(this, request.kind, request.seconds, leadInSeconds = 5)
            }
            // A labelled recording asked for from the phone's Calibrate screen.
            WearPaths.CAPTURE_NOW -> {
                val label = String(event.data, Charsets.UTF_8)
                CaptureService.start(this, CaptureService.ACTION_DEBUG_CAPTURE) {
                    putExtra(CaptureService.EXTRA_LABEL, label)
                }
            }
        }
    }
}

/** Keeps the watch's stored trial in step with what the phone last published. */
object TrialSync {
    fun parse(item: DataItem): TrialConfig? =
        DataMapItem.fromDataItem(item).dataMap
            .getString(WearPaths.KEY_CONFIG)
            ?.takeIf { it.isNotEmpty() }
            ?.let {
                runCatching { ProtocolJson.decodeFromString(TrialConfig.serializer(), it) }.getOrNull()
            }

    fun apply(context: Context, config: TrialConfig?) {
        val store = context.trialStore
        if (store.config?.trialId != config?.trialId) store.nextCaptureMillis = 0L
        store.config = config
        if (store.activeConfig() != null) {
            CaptureService.start(context, CaptureService.ACTION_SYNC)
        } else {
            CaptureScheduler.cancel(context)
            context.stopService(Intent(context, CaptureService::class.java))
        }
    }

    /**
     * Reads the trial the phone has published. Change events are only delivered while the
     * app is installed and running, so a trial started before that would otherwise be missed.
     * Leaves the stored trial alone if the phone has never published one or cannot be read.
     */
    suspend fun refresh(context: Context) {
        runCatching {
            val uri = Uri.Builder().scheme("wear").authority("*").path(WearPaths.TRIAL).build()
            val buffer = Wearable.getDataClient(context).getDataItems(uri).await()
            try {
                val item = buffer.firstOrNull() ?: return
                val config = parse(item)
                // Nothing new: leave a running service (or guided test) undisturbed.
                if (config == context.trialStore.config) return
                apply(context, config)
            } finally {
                buffer.release()
            }
        }
    }
}
