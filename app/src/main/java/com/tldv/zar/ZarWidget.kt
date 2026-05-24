package com.tldv.zar

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class ZarWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateWidget(context, manager, it, null) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_ROLL) {
            val result = (1..6).random()
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                android.content.ComponentName(context, ZarWidget::class.java)
            )
            ids.forEach { updateWidget(context, manager, it, result) }
        }
    }

    companion object {
        const val ACTION_ROLL = "com.tldv.zar.WIDGET_ROLL"

        fun updateWidget(context: Context, manager: AppWidgetManager, id: Int, result: Int?) {
            val views = RemoteViews(context.packageName, R.layout.widget_zar)

            val intent = Intent(context, ZarWidget::class.java).apply {
                action = ACTION_ROLL
            }
            val pending = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetResult, pending)
            views.setTextViewText(R.id.widgetResult, result?.toString() ?: "?")
            manager.updateAppWidget(id, views)
        }
    }
}
