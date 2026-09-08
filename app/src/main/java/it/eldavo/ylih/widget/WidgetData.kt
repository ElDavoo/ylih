package it.eldavo.ylih.widget

import android.content.Context
import androidx.annotation.VisibleForTesting
import it.eldavo.ylih.AppLocale
import it.eldavo.ylih.YlihApp
import it.eldavo.ylih.data.AppContainer
import it.eldavo.ylih.data.PairSummary
import it.eldavo.ylih.stats.Counting
import it.eldavo.ylih.stats.Stats
import it.eldavo.ylih.ui.countedMs
import it.eldavo.ylih.ui.toSpan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
 * Reads the whole widget picture off the database, once.
 *
 * Free of Glance, so it's testable like `Stats.kt`: a widget's rendered output only exists inside
 * the launcher, and none of the arithmetic here needs one.
 */
@VisibleForTesting
internal suspend fun loadWidgetData(
    container: AppContainer,
    zone: ZoneId = ZoneId.systemDefault(),
): WidgetData = widgetDataFlow(container, zone).first()

/**
 * The same picture, re-read whenever anything it is drawn from changes.
 *
 * A widget collects this *inside its composition*, not as a one-time read on the way in — that's
 * what lets a pushed refresh show anything new. See [YlihWidget].
 *
 * [zone] has no default, unlike the two entry points wrapping it: both already resolve one, so a
 * default here would just be a second source for the phone's real zone.
 */
fun widgetDataFlow(
    container: AppContainer,
    zone: ZoneId,
): Flow<WidgetData> = combine(
    container.repository.observeSummaries(),
    // Widgets honour playback-only mode exactly as the app and notification do; a home screen
    // showing a different number than the app it came from would be the worst of both.
    container.settings.playbackOnly,
) { summaries, playbackOnly ->
    widgetData(
        container = container,
        zone = zone,
        summaries = summaries,
        counting = if (playbackOnly) Counting.PLAYBACK else Counting.CONNECTED,
    )
}

private suspend fun widgetData(
    container: AppContainer,
    zone: ZoneId,
    summaries: List<PairSummary>,
    counting: Counting,
): WidgetData {
    val now = container.clock.now()
    val spans = container.repository.sessionsSince(windowStart(now, zone)).map { it.toSpan() }

    val series = Stats.dailySeries(spans, zone, now, WIDGET_DAYS, counting)
    return WidgetData(
        rows = summaries
            // Retired pairs are frozen for good and would crowd live ones out of a widget three
            // rows tall. The app is where the museum lives.
            .filter { it.retiredAt == null }
            .map { WidgetRow(it.pairId, it.label, it.countedMs(now, counting), it.openSince) }
            .sortedWith(
                compareByDescending<WidgetRow> { it.openSince != null }
                    .thenByDescending { it.lifetimeMs },
            ),
        // Retired pairs *are* in the grand total: it's the same lifetime figure atop the stats
        // screen, and hours don't stop having happened.
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
 * Everything a widget draws: the figures, and the context that resolves them into words.
 *
 * A flow of *both*, not a one-time read of both, because a Glance composition outlives the call
 * that started it — see [YlihWidget]. The language belongs here for the same reason the figures do:
 * it can change under a live composition too, and this shape closes exactly the trap of a value
 * only the next session would pick up.
 *
 * The localised context is easy to drop: below Android 13 the app's language is its own setting,
 * so the context Glance hands in resolves neither the app's strings nor the `Locale`
 * `ui/Format.kt` reads for a decimal separator. Skipping it would silently leave a widget in the
 * system language.
 */
fun widgetContentFlow(
    context: Context,
    zone: ZoneId = ZoneId.systemDefault(),
): Flow<Pair<Context, WidgetData>> {
    val container = (context.applicationContext as YlihApp).container
    // distinctUntilChanged because every SettingsStore flow maps over the whole settings table, so
    // a write to any other setting reaches this one, and each emission costs a 30-day re-read of
    // the session table. The store dedupes for the same reason — wrong here is expensive, not
    // just noisy.
    val language = container.settings.language.distinctUntilChanged()
    return widgetDataFlow(container, zone).combine(language) { data, _ ->
        AppLocale.wrapSuspending(context) to data
    }
}

/** Local midnight [WIDGET_DAYS] - 1 days back — the oldest instant any window here reaches. */
internal fun windowStart(now: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        .minusDays((WIDGET_DAYS - 1).toLong())
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()

/**
 * The next instant at which every window above answers a different question — see [WidgetRolloverWorker].
 *
 * `atStartOfDay(zone)` rather than `+ 24h`, for the reason `Stats.dailyMs` gives: a DST day is 23
 * or 25 hours long, and on the spring-forward day midnight itself may not exist — this hands back
 * the first instant that does.
 */
internal fun nextLocalMidnight(now: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        .plusDays(1)
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()

/**
 * What a `Chronometer` must count up from to show the age of a session opened at [openSince].
 *
 * Chronometer works in `SystemClock.elapsedRealtime()`, unrelated to wall time, so the conversion
 * happens here rather than inline in the widget: nothing can see inside an `AndroidRemoteViews`,
 * so this is the only part of the live timer a test can reach.
 */
fun chronometerBase(openSince: Long, now: Long, elapsedRealtime: Long): Long =
    elapsedRealtime - (now - openSince).coerceAtLeast(0L)
