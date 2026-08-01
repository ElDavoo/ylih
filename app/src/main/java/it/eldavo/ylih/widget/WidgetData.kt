package it.eldavo.ylih.widget

import android.content.Context
import it.eldavo.ylih.AppLocale
import it.eldavo.ylih.YlihApp
import it.eldavo.ylih.data.AppContainer
import it.eldavo.ylih.stats.Counting
import it.eldavo.ylih.stats.Stats
import it.eldavo.ylih.ui.countedMs
import it.eldavo.ylih.ui.toSpan
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** How far back the chart widget looks, and therefore how much history a refresh reads. */
const val WIDGET_DAYS = 30

/** One pair's line in the lifetime widget. */
data class WidgetRow(
    val pairId: Long,
    val label: String,
    val lifetimeMs: Long,
    /** Non-null while the pair is connected; the Chronometer counts up from here. */
    val openSince: Long?,
)

/** Everything the three widgets can show, read in one pass. */
data class WidgetData(
    /** Active pairs, the connected one first, then by hours. */
    val rows: List<WidgetRow>,
    val totalMs: Long,
    val todayMs: Long,
    val weekMs: Long,
    val monthMs: Long,
    val series: List<Pair<LocalDate, Long>>,
    val counting: Counting,
    /** The instant every figure above was taken at; the Chronometer base is derived from it. */
    val now: Long,
)

/**
 * Reads the whole widget picture off the database.
 *
 * Deliberately free of Glance, so it is testable the way `Stats.kt` is — a widget's rendered
 * output only exists inside the launcher, and none of the arithmetic here should need one.
 */
suspend fun loadWidgetData(
    container: AppContainer,
    zone: ZoneId = ZoneId.systemDefault(),
): WidgetData {
    val now = container.clock.now()
    // The widgets honour playback-only mode exactly as the app and the notification do; a home
    // screen showing a different number from the app it came from would be the worst of both.
    val counting = if (container.settings.playbackOnlyNow()) Counting.PLAYBACK else Counting.CONNECTED
    val summaries = container.repository.observeSummaries().first()
    val spans = container.repository.sessionsSince(windowStart(now, zone)).map { it.toSpan() }

    val series = Stats.dailySeries(spans, zone, now, WIDGET_DAYS, counting)
    return WidgetData(
        rows = summaries
            // Retired pairs are frozen for good and would crowd the live ones out of a widget
            // three rows tall. The app is where the museum lives.
            .filter { it.retiredAt == null }
            .map { WidgetRow(it.pairId, it.label, it.countedMs(now, counting), it.openSince) }
            .sortedWith(
                compareByDescending<WidgetRow> { it.openSince != null }
                    .thenByDescending { it.lifetimeMs },
            ),
        // Retired pairs *are* in the grand total: it is the same lifetime figure the stats
        // screen puts at the top, and hours do not stop having happened.
        totalMs = summaries.sumOf { it.countedMs(now, counting) },
        todayMs = Stats.recentMs(spans, zone, now, 1, counting),
        weekMs = Stats.recentMs(spans, zone, now, 7, counting),
        monthMs = series.sumOf { it.second },
        series = series,
        counting = counting,
        now = now,
    )
}

/**
 * Everything a `provideGlance` needs before it can compose: the figures, and the context that
 * resolves them into words.
 *
 * All three widgets open the same way and the localised context is the part that is easy to drop —
 * below Android 13 the app's language is its own setting, so the context Glance hands in is not
 * the one that resolves the app's strings, or the `Locale` `ui/Format.kt` reads for a decimal
 * separator. A widget that forgot this would quietly be in the system language.
 */
suspend fun widgetContent(context: Context): Pair<Context, WidgetData> {
    val container = (context.applicationContext as YlihApp).container
    return AppLocale.wrapSuspending(context) to loadWidgetData(container)
}

/** Local midnight [WIDGET_DAYS] - 1 days back — the oldest instant any window here reaches. */
internal fun windowStart(now: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        .minusDays((WIDGET_DAYS - 1).toLong())
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()

/**
 * What a `Chronometer` must count up from to show the age of a session opened at [openSince].
 *
 * Chronometer works in `SystemClock.elapsedRealtime()`, which bears no relation to wall time, so
 * the conversion happens here — and here rather than inline in the widget because nothing can see
 * inside an `AndroidRemoteViews`, which makes this the only part of the live timer a test can
 * reach.
 */
fun chronometerBase(openSince: Long, now: Long, elapsedRealtime: Long): Long =
    elapsedRealtime - (now - openSince).coerceAtLeast(0L)
