package app.tremolo.phone.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import app.tremolo.phone.TremoloApp
import app.tremolo.phone.data.EventLogEntry
import app.tremolo.phone.data.SampleWindow
import app.tremolo.phone.data.SkipRecord
import app.tremolo.phone.data.TremoloDao
import app.tremolo.shared.dsp.AnalyzerParams
import app.tremolo.shared.dsp.TremorAnalyzer
import app.tremolo.shared.model.RawWindowCodec
import app.tremolo.shared.protocol.ActiveTestRequest
import app.tremolo.shared.protocol.ProtocolJson
import app.tremolo.shared.protocol.SkipEvent
import app.tremolo.shared.protocol.TrialConfig
import app.tremolo.shared.protocol.WatchEvent
import app.tremolo.shared.protocol.WatchInfo
import app.tremolo.shared.protocol.WearPaths
import app.tremolo.shared.protocol.WindowKind
import app.tremolo.shared.protocol.WindowMeta
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private const val TAG = "TremoloSync"

/** Phone -> watch commands. */
class WearBridge(private val context: Context) {

    /** Tells the watch which trial to sample for; null stops sampling. */
    suspend fun pushTrial(config: TrialConfig?) {
        val request = PutDataMapRequest.create(WearPaths.TRIAL).apply {
            dataMap.putString(
                WearPaths.KEY_CONFIG,
                config?.let { ProtocolJson.encodeToString(TrialConfig.serializer(), it) } ?: "",
            )
        }.asPutDataRequest().setUrgent()
        runCatching { Wearable.getDataClient(context).putDataItem(request).await() }
            .onFailure { Log.w(TAG, "Could not push trial", it) }
    }

    suspend fun isWatchConnected(): Boolean = runCatching {
        Wearable.getNodeClient(context).connectedNodes.await().isNotEmpty()
    }.getOrDefault(false)

    /** Returns false if no watch could be reached. */
    suspend fun startActiveTest(kind: WindowKind, trialId: String?, seconds: Int = 30): Boolean {
        val request = ActiveTestRequest(UUID.randomUUID().toString(), trialId, kind, seconds)
        val json = ProtocolJson.encodeToString(ActiveTestRequest.serializer(), request)
        return sendToWatch(WearPaths.ACTIVE_TEST, json.toByteArray())
    }

    /** Debug: record one window now, optionally tagged with what the wearer is doing. */
    suspend fun captureNow(label: String? = null): Boolean =
        sendToWatch(WearPaths.CAPTURE_NOW, label.orEmpty().toByteArray())

    /** What the paired watch says about itself; null until its app has run once. */
    suspend fun watchInfo(): WatchInfo? = runCatching {
        val items = Wearable.getDataClient(context)
            .getDataItems(Uri.parse("wear://*${WearPaths.WATCH_INFO}")).await()
        try {
            items.firstOrNull()?.let { item ->
                ProtocolJson.decodeFromString(
                    WatchInfo.serializer(),
                    DataMapItem.fromDataItem(item).dataMap.getString(WearPaths.KEY_INFO).orEmpty(),
                )
            }
        } finally {
            items.release()
        }
    }.getOrNull()

    private suspend fun sendToWatch(path: String, data: ByteArray): Boolean = runCatching {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        val messages = Wearable.getMessageClient(context)
        nodes.forEach { messages.sendMessage(it.id, path, data).await() }
        nodes.isNotEmpty()
    }.getOrDefault(false)
}

/** Stores captures and skip reports arriving from the watch, then acknowledges them. */
class WindowIngestor(
    private val context: Context,
    private val dao: TremoloDao,
    /** Current analysis settings, including the user's personal calibration. */
    private val params: () -> AnalyzerParams,
) {
    private val rawDir = File(context.filesDir, "raw").apply { mkdirs() }

    private fun paused() = (context.applicationContext as TremoloApp).prefs.demoMode

    /** [item] must be frozen (detached from its buffer). Safe to call twice for one item. */
    suspend fun ingest(item: DataItem) = withContext(Dispatchers.IO) {
        // On example data nothing is taken in or acknowledged; it is picked up afterwards.
        if (paused()) return@withContext
        val path = item.uri.path ?: return@withContext
        val nodeId = item.uri.host ?: return@withContext
        val map = DataMapItem.fromDataItem(item).dataMap
        when {
            path.startsWith(WearPaths.WINDOW_PREFIX) -> {
                val meta = map.getString(WearPaths.KEY_META)?.let {
                    runCatching { ProtocolJson.decodeFromString(WindowMeta.serializer(), it) }.getOrNull()
                } ?: return@withContext
                if (!dao.hasWindow(meta.windowId)) {
                    val asset = map.getAsset(WearPaths.KEY_RAW) ?: return@withContext
                    val bytes = Wearable.getDataClient(context).getFdForAsset(asset).await()
                        .inputStream.use { it.readBytes() }
                    val file = File(rawDir, "${meta.windowId}.trw")
                    file.writeBytes(bytes)
                    val features = runCatching {
                        TremorAnalyzer.analyze(RawWindowCodec.read(bytes.inputStream()), params())
                    }.getOrNull()
                    dao.insertWindow(
                        SampleWindow(
                            windowId = meta.windowId,
                            trialId = meta.trialId,
                            kind = meta.kind,
                            startMillis = meta.startMillis,
                            rawPath = file.absolutePath,
                            label = meta.label,
                            analysisVersion = TremorAnalyzer.VERSION,
                            features = features,
                            batteryPercent = meta.batteryPercent.takeIf { it >= 0 },
                            plannedGapSeconds = meta.plannedGapSeconds.takeIf { it > 0 },
                        ),
                    )
                }
                acknowledge(nodeId, path)
            }

            path.startsWith(WearPaths.SKIP_PREFIX) -> {
                val skip = map.getString(WearPaths.KEY_SKIP)?.let {
                    runCatching { ProtocolJson.decodeFromString(SkipEvent.serializer(), it) }.getOrNull()
                } ?: return@withContext
                dao.insertSkip(SkipRecord(skip.eventId, skip.trialId, skip.timeMillis, skip.reason))
                acknowledge(nodeId, path)
            }

            path.startsWith(WearPaths.EVENT_PREFIX) -> {
                val event = map.getString(WearPaths.KEY_EVENT)?.let {
                    runCatching { ProtocolJson.decodeFromString(WatchEvent.serializer(), it) }.getOrNull()
                } ?: return@withContext
                dao.insertEvent(
                    EventLogEntry(
                        timeMillis = event.timeMillis,
                        type = event.type,
                        note = event.note.ifBlank { "from watch" },
                        externalId = event.eventId,
                    ),
                )
                acknowledge(nodeId, path)
            }
        }
    }

    /** Re-analyses stored captures that were analysed by an older version of the analyser. */
    suspend fun reanalyseOutdated() = withContext(Dispatchers.IO) {
        if (paused()) return@withContext
        for (window in dao.windowsAnalysedBefore(TremorAnalyzer.VERSION)) {
            val features = runCatching {
                File(window.rawPath).inputStream().use { TremorAnalyzer.analyze(RawWindowCodec.read(it), params()) }
            }.getOrNull()
            dao.setAnalysis(window.windowId, features, TremorAnalyzer.VERSION)
        }
    }

    /** Picks up anything that arrived while the listener service was not running. */
    suspend fun catchUp() {
        runCatching {
            val uri = Uri.Builder().scheme("wear").authority("*").path("/tremolo/").build()
            val buffer = Wearable.getDataClient(context)
                .getDataItems(uri, DataClient.FILTER_PREFIX).await()
            val items = try {
                buffer.map { it.freeze() }
            } finally {
                buffer.release()
            }
            items.forEach { item -> runCatching { ingest(item) } }
        }.onFailure { Log.w(TAG, "Catch-up failed", it) }
    }

    /** The watch deletes its copy when this arrives; if it is lost, catch-up re-sends it. */
    private suspend fun acknowledge(nodeId: String, path: String) {
        runCatching {
            Wearable.getMessageClient(context)
                .sendMessage(nodeId, WearPaths.ACK, path.toByteArray()).await()
        }
    }
}

class WatchListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val items = events.filter { it.type == DataEvent.TYPE_CHANGED }.map { it.dataItem.freeze() }
        val ingestor = (application as TremoloApp).ingestor
        runBlocking {
            items.forEach { item ->
                runCatching { ingestor.ingest(item) }.onFailure { Log.w(TAG, "Ingest failed", it) }
            }
        }
    }
}
