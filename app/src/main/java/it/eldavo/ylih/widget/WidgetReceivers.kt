package it.eldavo.ylih.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * One `AppWidgetProvider` per widget, kept together the way `tracking/` keeps its receivers: they
 * are three lines each and there is nothing to say about one that is not true of the others.
 *
 * These are what the manifest names, and what `appwidget-provider` meta-data hangs off.
 */
class LifetimeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LifetimeWidget()
}

class ActivityWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ActivityWidget()
}

class ChartWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ChartWidget()
}
