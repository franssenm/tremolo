package app.tremolo.phone

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.Toast
import app.tremolo.phone.data.EventLogEntry
import app.tremolo.shared.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home-screen widget: log the most common events with one tap, without opening the app.
 * Shows nothing about measurements, so it is safe during a trial.
 */
class QuickLogWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_quick_log)
        BUTTONS.forEach { (viewId, type) ->
            views.setOnClickPendingIntent(
                viewId,
                PendingIntent.getBroadcast(
                    context, viewId,
                    Intent(context, QuickLogWidget::class.java).setAction(ACTION_LOG).putExtra(EXTRA_TYPE, type),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        views.setOnClickPendingIntent(
            R.id.widget_rate,
            PendingIntent.getActivity(
                context, R.id.widget_rate,
                Intent(context, MainActivity::class.java)
                    .putExtra(Reminders.EXTRA_ROUTE, "rating")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_LOG) return
        val type = intent.getStringExtra(EXTRA_TYPE) ?: return
        val app = context.applicationContext as TremoloApp
        val pending = goAsync()
        app.appScope.launch {
            try {
                app.dao.insertEvent(EventLogEntry(timeMillis = System.currentTimeMillis(), type = type, note = ""))
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, tr("Tremolo logged: $type"), Toast.LENGTH_SHORT).show()
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val ACTION_LOG = "app.tremolo.phone.WIDGET_LOG"
        const val EXTRA_TYPE = "type"
        val BUTTONS = listOf(
            R.id.widget_bad to "Tremor bad",
            R.id.widget_good to "Tremor good",
            R.id.widget_side_effect to "Side effect",
        )
    }
}
