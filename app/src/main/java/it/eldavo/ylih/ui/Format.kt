package it.eldavo.ylih.ui

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.text.NumberFormat
import java.time.format.FormatStyle
import java.util.Locale

private const val SECOND = 1_000L
private const val MINUTE = 60 * SECOND
private const val HOUR = 60 * MINUTE

/**
 * "3h 7m" / "12m" / "45s" — for live timers and session rows, in the reader's own units.
 *
 * Units come from CLDR via ICU, not string resources: the same abbreviations the platform's clock
 * and battery screens use, in all 77 languages — "3t 7min" in Finnish, "3小时7分钟" in Chinese, and
 * correct ordering and separator in Arabic and Hebrew, none reachable by `"%dh %02dm"`. That was
 * the old format string, so every language read the English "h", "m" and "s" — spoken aloud by a
 * screen reader.
 *
 * Zero padding went with it: ICU has no notion of it, and the alternative was giving up
 * localisation just to keep a live timer's width steady crossing 9 to 10 minutes.
 */
fun formatDurationShort(ms: Long): String {
    val safe = ms.coerceAtLeast(0)
    val hours = safe / HOUR
    val minutes = (safe % HOUR) / MINUTE
    val seconds = (safe % MINUTE) / SECOND
    return formatters().duration(hours, minutes, seconds)
}

/** "1,240.5h" — the lifetime headline, with the hour unit from CLDR. See [formatDurationShort]. */
fun formatHours(ms: Long): String =
    formatters().hours(ms.coerceAtLeast(0) / HOUR.toDouble())

/**
 * The three localized formatters, built once per locale rather than per call.
 *
 * `ofLocalizedDateTime` resolves a pattern from the CLDR bundle on every call, and the session
 * list calls two per row — three hundred sessions built six hundred formatters per repaint.
 * Immutable and thread-safe once built; cached by locale, since `AppLocale` can change it
 * mid-process and each formatter reads `Locale.getDefault()`.
 */
private class Formatters(val locale: Locale) {

    val percent: NumberFormat = NumberFormat.getPercentInstance(locale)

    private val icu: IcuUnits = IcuUnits(locale)

    fun duration(hours: Long, minutes: Long, seconds: Long): String =
        icu.duration(hours, minutes, seconds)

    fun hours(value: Double): String = icu.hours(value)

    val dateTime: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
    val date: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(locale)

    /**
     * The abbreviated day of the week, out of CLDR — same reason the units go through ICU. An
     * `EEE` pattern resolves against the locale's own data, so no English "Mon" leaks into other
     * languages, and nothing here needs translating.
     */
    val weekday: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", locale)

    /**
     * The chart's axis labels: the locale's own short date with the year stripped out.
     *
     * Used to be hardcoded `d/M`, putting the day first in all 77 languages — wrong for en-US, ja
     * and hu among others. `java.time` has no "day and month" style, and the skeleton API that
     * would give one (`DateFormat.getBestDateTimePattern`) is Android's, unreachable here since
     * plain JVM tests read this file. So the short pattern is asked for and the year field
     * stripped with whatever separator it brought — `dd/MM/y` becomes `dd/MM`, `M/d/yy` becomes
     * `M/d`, `y/MM/dd` becomes `MM/dd`.
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

/**
 * Every `android.icu` type in this file, in one place.
 *
 * A class, not free functions, so nothing outside can name a `MeasureUnit`, and the two
 * `MeasureFormat`s build once per locale alongside the formatters using them.
 *
 * NARROW, not SHORT: these sit in chips, cards and a headline, where "3h 7m" belongs and
 * "3 hrs, 7 mins" doesn't. Two formats — whole-unit durations want no decimals, the lifetime
 * headline wants exactly one.
 */
private class IcuUnits(locale: Locale) {

    private val whole = measures(locale, fractionDigits = 0)
    private val fractional = measures(locale, fractionDigits = 1)

    fun duration(hours: Long, minutes: Long, seconds: Long): String = when {
        hours > 0 -> whole.formatMeasures(
            Measure(hours, MeasureUnit.HOUR),
            Measure(minutes, MeasureUnit.MINUTE),
        )

        minutes > 0 -> whole.formatMeasures(Measure(minutes, MeasureUnit.MINUTE))
        else -> whole.formatMeasures(Measure(seconds, MeasureUnit.SECOND))
    }

    fun hours(value: Double): String = fractional.format(Measure(value, MeasureUnit.HOUR))

    private fun measures(locale: Locale, fractionDigits: Int): MeasureFormat =
        MeasureFormat.getInstance(
            locale,
            MeasureFormat.FormatWidth.NARROW,
            android.icu.text.NumberFormat.getInstance(locale).apply {
                minimumFractionDigits = fractionDigits
                maximumFractionDigits = fractionDigits
                isGroupingUsed = true
            },
        )
}

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

/** "Mon", "lun.", "月" — see [Formatters.weekday]. */
fun formatWeekday(date: LocalDate): String = formatters().weekday.format(date)

/** A fraction as the locale writes a percentage — see `percent`. */
fun formatPercent(fraction: Double): String = formatters().percent.format(fraction)

fun formatMoney(cents: Long): String =
    String.format(Locale.getDefault(), "%,.2f", cents / 100.0)

/**
 * [formatMoney] without grouping separators, for a field that has to be read back.
 *
 * Grouping makes a price ambiguous to re-parse: an Italian install writes 1234.56 as "1.234,56",
 * indistinguishable from someone typing "1.234" meaning one and a bit. Without it only one
 * separator ever appears, so [parsePriceCents] can take either character as the decimal point and
 * round-trip.
 */
fun formatPriceInput(cents: Long): String =
    String.format(Locale.getDefault(), "%.2f", cents / 100.0)

/**
 * Reads a typed price back into minor units, or null for anything that isn't one.
 *
 * `BigDecimal` rather than `Double`: "12.99" is 12.989999999999998 as a Double, and truncating
 * that after multiplying by a hundred stored 1298 — a cent short, every time.
 */
fun parsePriceCents(text: String): Long? {
    val normalised = text.trim().replace(',', '.')
    if (normalised.isEmpty()) return null
    return runCatching {
        BigDecimal(normalised).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
    }.getOrNull()?.takeIf { it >= 0 }
}

/**
 * Charge cycles, to a tenth.
 *
 * One decimal because that's what the readings support: a headset reporting over HFP moves in
 * twenty-five-point steps, so a hundredth would be false precision. Whole cycles would be worse —
 * the figure would sit at "1" for months.
 */
fun formatCycles(value: Double): String =
    String.format(Locale.getDefault(), "%.1f", value.coerceAtLeast(0.0))

/** Cost per listening hour; three decimals because the number gets small fast. */
fun formatPerHour(value: Double): String =
    String.format(Locale.getDefault(), "%.3f", value)
