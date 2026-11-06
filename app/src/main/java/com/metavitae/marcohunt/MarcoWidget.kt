package com.metavitae.marcohunt

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Home-screen widget: tap it and the hunt opens (with the Kithe opening). */
class MarcoWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, IntroActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        for (id in ids) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_marco)
            v.setOnClickPendingIntent(R.id.widget_root, open)
            mgr.updateAppWidget(id, v)
        }
    }
}
