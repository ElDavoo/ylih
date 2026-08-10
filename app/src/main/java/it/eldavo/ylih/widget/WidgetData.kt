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
 * Deliberately free of Glance, so it is testable the way `Stats.kt` is — a widget's rendered
 * output only exists inside the launcher, and none of the arithmetic here should need one.
 */
@VisibleForTesting
internal suspend fun loadWidgetData(
    container: AppContainer,
    zone: ZoneId = ZoneId.systemDefault(),
): WidgetData = widgetDataFlow(container, zone).first()

/**
 * The same picture, re-read whenever anything it is drawn from changes.
 *
 * A widget collects this *inside its composition* rather than reading it once on the way in, and
 * that is not a refinement — it is what makes a pushed refresh able to show anything new at all.
 * [YlihWidget] is where that is spelled out.
 *
 * [zone] has no default, unlike the two entry points that wrap it: both of those already resolve
 * one, so a default here would only be a second place for the phone's real zone to come from.
 */
fun widgetDataFlow(
    container: AppContainer,
    zone: ZoneId,
): Flow<WidgetData> = combine(
    container.repository.observeSummaries(),
    // The widgets honour playback-only mode exactly as the app and the notification do; a home
    // screen showing a different number from the app it came from would be the worst of both.
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
 * Everything a widget draws: the figures, and the context that resolves them into words.
 *
 * A flow of *both* rather than a reading of both, because a Glance composition outlives the call
 * that started it — [YlihWidget] is where that is spelled out. The language belongs in here for the
 * same reason the figures do: it can change under a live composition too, and a value that only the
 * next session would pick up is exactly the trap this shape exists to close.
 *
 * The localised context is the part that is easy to drop — below Android 13 the app's language is
 * its own setting, so the context Glance hands in is not the one that resolves the app's strings,
 * or the `Locale` `ui/Format.kt` reads for a decimal separator. A widget that forgot this would
 * quietly be in the system language.
 */
fun widgetContentFlow(
    context: Context,
    zone: ZoneId = ZoneId.systemDefault(),
): Flow<Pair<Context, WidgetData>> {
    val container = (context.applicationContext as YlihApp).container
    // distinctUntilChanged because every SettingsStore flow is a map over the whole settings
    // table, so a write to any other setting reaches this one — and each emission here costs a
    // 30-day re-read of the session table. The store dedupes for the same reason; this is the
    // point where getting it wrong would be expensive rather than merely noisy.
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
 * or 25 hours long, and on the spring-forward day midnight itself may not exist, where this hands
 * back the first instant that does.
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
 * Chronometer works in `SystemClock.elapsedRealtime()`, which bears no relation to wall time, so
 * the conversion happens here — and here rather than inline in the widget because nothing can see
 * inside an `AndroidRemoteViews`, which makes this the only part of the live timer a test can
 * reach.
 */
fun chronometerBase(openSince: Long, now: Long, elapsedRealtime: Long): Long =
    elapsedRealtime - (now - openSince).coerceAtLeast(0L)
