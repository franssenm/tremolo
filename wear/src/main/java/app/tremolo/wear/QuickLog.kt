package app.tremolo.wear

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import app.tremolo.shared.i18n.tr
import app.tremolo.shared.protocol.ProtocolJson
import app.tremolo.shared.protocol.WatchEvent
import app.tremolo.shared.protocol.WearPaths
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.common.util.concurrent.ListenableFuture
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Things worth noting the moment they happen, loggable with one tap from the wrist. */
val QUICK_EVENTS =
    listOf("Tremor bad", "Tremor good", "Side effect", "Medication taken", "Stressed", "Tired")

/**
 * Stores an event for the phone. The Data Layer keeps it on the watch until the phone is
 * reachable, so logging works away from the phone too.
 */
suspend fun logEvent(context: Context, type: String, note: String = ""): Boolean = runCatching {
    val event = WatchEvent(UUID.randomUUID().toString(), System.currentTimeMillis(), type, note)
    val request = PutDataMapRequest.create(WearPaths.EVENT_PREFIX + event.eventId).apply {
        dataMap.putString(WearPaths.KEY_EVENT, ProtocolJson.encodeToString(WatchEvent.serializer(), event))
    }.asPutDataRequest().setUrgent()
    Wearable.getDataClient(context).putDataItem(request).await()
}.isSuccess

/**
 * A tile (the screens reached by swiping sideways from the watch face) with the four most
 * used log buttons. Like the app, it shows nothing about measurements.
 */
class QuickLogTileService : TileService() {
    private val buttons = listOf("Tremor bad", "Tremor good", "Side effect", "Medication taken")

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { completer ->
            val clicked = buttons.getOrNull(requestParams.currentState.lastClickableId.toIntOrNull() ?: -1)
            (application as WearApp).appScope.launch {
                val title = when {
                    clicked == null -> tr("Log now")
                    logEvent(applicationContext, clicked) ->
                        tr("Logged at " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())) + ":\n" + tr(clicked)
                    else -> tr("Could not log. Try again.")
                }
                completer.set(
                    TileBuilders.Tile.Builder()
                        .setResourcesVersion(RESOURCES_VERSION)
                        .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(title)))
                        .build(),
                )
            }
            "quick log tile"
        }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> = CallbackToFutureAdapter.getFuture { completer ->
        completer.set(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())
        "quick log tile resources"
    }

    private fun text(value: String, size: Float, color: Int) = LayoutElementBuilders.Text.Builder()
        .setText(value)
        .setMaxLines(2)
        .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
        .setFontStyle(
            LayoutElementBuilders.FontStyle.Builder().setSize(sp(size)).setColor(argb(color)).build(),
        )
        .build()

    private fun button(index: Int) = LayoutElementBuilders.Box.Builder()
        .setWidth(dp(72f))
        .setHeight(dp(42f))
        .setModifiers(
            ModifiersBuilders.Modifiers.Builder()
                .setBackground(
                    ModifiersBuilders.Background.Builder()
                        .setColor(argb(0xFF1F4A52.toInt()))
                        .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(21f)).build())
                        .build(),
                )
                .setClickable(
                    ModifiersBuilders.Clickable.Builder()
                        .setId(index.toString())
                        // Reloads the tile; the request then says which button was pressed.
                        .setOnClick(ActionBuilders.LoadAction.Builder().build())
                        .build(),
                )
                .build(),
        )
        .addContent(text(tr(buttons[index].replace("Medication taken", "Med taken")), 12f, 0xFFFFFFFF.toInt()))
        .build()

    private fun spacer(width: Float = 6f, height: Float = 6f) =
        LayoutElementBuilders.Spacer.Builder().setWidth(dp(width)).setHeight(dp(height)).build()

    private fun row(first: Int, second: Int) = LayoutElementBuilders.Row.Builder()
        .addContent(button(first))
        .addContent(spacer())
        .addContent(button(second))
        .build()

    private fun layout(title: String): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(androidx.wear.protolayout.DimensionBuilders.expand())
            .setHeight(androidx.wear.protolayout.DimensionBuilders.expand())
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .addContent(text(title, 13f, 0xFF7FE3EC.toInt()))
                    .addContent(spacer())
                    .addContent(row(0, 1))
                    .addContent(spacer())
                    .addContent(row(2, 3))
                    .build(),
            )
            .build()

    private companion object {
        const val RESOURCES_VERSION = "1"
    }
}

/** A watch-face complication (a small slot on the face itself) that opens the log buttons. */
class LogComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        build(request.complicationType)

    private fun build(type: ComplicationType): ComplicationData? {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val image = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_stat_tremolo)).build()
        val description = PlainComplicationText.Builder(tr("Log a tremor event")).build()
        return when (type) {
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(PlainComplicationText.Builder("Log").build(), description)
                    .setMonochromaticImage(image)
                    .setTapAction(open)
                    .build()

            ComplicationType.MONOCHROMATIC_IMAGE ->
                MonochromaticImageComplicationData.Builder(image, description).setTapAction(open).build()

            else -> null
        }
    }
}
