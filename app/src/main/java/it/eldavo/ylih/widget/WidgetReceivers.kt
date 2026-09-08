package it.eldavo.ylih.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * One `AppWidgetProvider` per widget, kept together like `tracking/`'s receivers: three lines each,
 * nothing true of one that isn't true of the others.
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
