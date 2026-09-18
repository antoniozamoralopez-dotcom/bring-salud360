package com.salud360.entreno;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

public class Salud360Widget extends AppWidgetProvider {
    private static final String PREFS = "salud360_prefs";

    static void updateAppWidget(Context context, AppWidgetManager manager, int appWidgetId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String training = prefs.getString("widget_training", "Abre Salud 360 para sincronizar");
        String secondary = prefs.getString("widget_secondary", "Panel de salud");

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_salud360);
        views.setTextViewText(R.id.widget_training, training);
        views.setTextViewText(R.id.widget_secondary, secondary);

        Intent launch = new Intent(context, MainActivity.class);
        launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(
                context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, pending);
        manager.updateAppWidget(appWidgetId, views);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int id : appWidgetIds) updateAppWidget(context, appWidgetManager, id);
    }
}
