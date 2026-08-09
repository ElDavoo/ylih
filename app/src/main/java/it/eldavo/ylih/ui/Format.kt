package it.eldavo.ylih.ui

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale

private const val SECOND = 1_000L
private const val MINUTE = 60 * SECOND
private const val HOUR = 60 * MINUTE

/** "3h 07m" / "12m" / "45s" — for live timers and session rows. */
fun formatDurationShort(ms: Long): String {
    val safe = ms.coerceAtLeast(0)
    val hours = safe / HOUR
    val minutes = (safe % HOUR) / MINUTE
    val seconds = (safe % MINUTE) / SECOND
    return when {
        hours > 0 -> String.format(Locale.getDefault(), "%dh %02dm", hours, minutes)
        minutes > 0 -> String.format(Locale.getDefault(), "%dm", minutes)
        else -> String.format(Locale.getDefault(), "%ds", seconds)
    }
}

/** "1,240.5 h" — the lifetime headline. */
fun formatHours(ms: Long): String =
    String.format(Locale.getDefault(), "%,.1f h", ms.coerceAtLeast(0) / HOUR.toDouble())

/**
 * The three localized formatters, built once per locale rather than once per call.
 *
 * `ofLocalizedDateTime` resolves a pattern out of the CLDR resource bundle every time it is
 * asked, and the session list calls two of them per row — so a pair with three hundred sessions
 * was building six hundred formatters for every repaint. They are immutable and thread-safe once
 * built; the cache key is the locale, because `AppLocale` can change it under a running process
 * and every one of these reads `Locale.getDefault()`.
 */
private class Formatters(val locale: Locale) {
    val dateTime: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
    val date: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(locale)

    /**
     * The chart's axis labels: the locale's own short date with the year taken out.
     *
     * It used to be a hardcoded `d/M`, which put the day first in all 77 languages — wrong for
     * en-US, ja and hu among others. There is no "day and month" style in `java.time`, and the
     * skeleton API that would give one (`DateFormat.getBestDateTimePattern`) is Android's, which
     * this file cannot reach: it is read by plain JVM tests. So the short pattern is asked for and
     * the year field stripped along with whatever separator it brought — `dd/MM/y` becomes
     * `dd/MM`, `M/d/yy` becomes `M/d`, `y/MM/dd` becomes `MM/dd`.
     */
    val dayLabel: DateTimeFormatter = DateTimeFormatter.ofPattern(
        DateTimeFormatterBuilder
            .getLocalizedDateTimePattern(FormatStyle.SHORT, null, IsoChronology.INSTANCE, locale)
            .replace(YEAR_FIELD, " ")
            .trim(),
        locale,
    )
}

/** A run of year letters with any punctuation it is attached to on either side. */
private val YEAR_FIELD = Regex("[^\\p{L}]*[yu]+[^\\p{L}]*")

@Volatile
private var formatters: Formatters? = null

private fun formatters(): Formatters {
    val locale = Locale.getDefault()
    return formatters?.takeIf { it.locale == locale } ?: Formatters(locale).also { formatters = it }
}

fun formatDateTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    formatters().dateTime.format(Instant.ofEpochMilli(epochMs).atZone(zone))

fun formatDate(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    formatters().date.format(Instant.ofEpochMilli(epochMs).atZone(zone))

fun formatDayLabel(date: LocalDate): String = formatters().dayLabel.format(date)

fun formatMoney(cents: Long): String =
    String.format(Locale.getDefault(), "%,.2f", cents / 100.0)

/**
 * [formatMoney] without the grouping separators, for the field that has to be read back.
 *
 * Grouping is what makes a price ambiguous to re-parse: an Italian install writes 1234.56 as
 * "1.234,56", and there is no honest way to tell that apart from someone typing "1.234" meaning
 * one and a bit. Without it there is only ever one separator in the string, so [parsePriceCents]
 * can take either character to mean the decimal point and the field round-trips whatever this put
 * in it.
 */
fun formatPriceInput(cents: Long): String =
    String.format(Locale.getDefault(), "%.2f", cents / 100.0)

/**
 * Reads a typed price back into minor units, or null for anything that is not one.
 *
 * `BigDecimal` rather than a `Double`: "12.99" is 12.989999999999998 as a Double, and truncating
 * that after multiplying by a hundred stored 1298 — a cent less than was typed, every time.
 */
fun parsePriceCents(text: String): Long? {
    val normalised = text.trim().replace(',', '.')
    if (normalised.isEmpty()) return null
    return runCatching {
        BigDecimal(normalised).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
    }.getOrNull()?.takeIf { it >= 0 }
}

/** Cost per listening hour; three decimals because the number gets small fast. */
fun formatPerHour(value: Double): String =
    String.format(Locale.getDefault(), "%.3f", value)
