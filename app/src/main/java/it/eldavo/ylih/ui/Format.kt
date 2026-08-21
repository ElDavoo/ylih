package it.eldavo.ylih.ui

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.os.Build
import androidx.annotation.RequiresApi
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
 * The units come from CLDR through ICU rather than from string resources. They are the same
 * abbreviations the platform's own clock and battery screens use, in all 77 languages, and there
 * is nothing here for anyone to translate: "3t 7min" in Finnish, "3小时7分钟" in Chinese, and the
 * right ordering and separator in Arabic and Hebrew, none of which a `"%dh %02dm"` could reach.
 *
 * It used to be exactly that format string, so every language read the English "h", "m" and "s" —
 * and a screen reader said them aloud.
 *
 * The zero padding goes with it: ICU has no notion of it, and the alternative was giving up the
 * localisation to keep a live timer one character steadier as it crosses 9 to 10 minutes.
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
 * The three localized formatters, built once per locale rather than once per call.
 *
 * `ofLocalizedDateTime` resolves a pattern out of the CLDR resource bundle every time it is
 * asked, and the session list calls two of them per row — so a pair with three hundred sessions
 * was building six hundred formatters for every repaint. They are immutable and thread-safe once
 * built; the cache key is the locale, because `AppLocale` can change it under a running process
 * and every one of these reads `Locale.getDefault()`.
 */
private class Formatters(val locale: Locale) {

    val percent: NumberFormat = NumberFormat.getPercentInstance(locale)

    /** Null on Android 6, the one release this ships to without `android.icu`. */
    private val icu: IcuUnits? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) IcuUnits(locale) else null

    // The `SDK_INT` test rather than a null check on [icu], which is the same question asked the
    // same way once already: lint reads the version comparison and nothing else as a guard, and it
    // is right to, since a null here would otherwise be free to mean something new later.
    fun duration(hours: Long, minutes: Long, seconds: Long): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && icu != null) {
            icu.duration(hours, minutes, seconds)
        } else {
            legacy(hours, minutes, seconds)
        }

    fun hours(value: Double): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && icu != null) {
            icu.hours(value)
        } else {
            String.format(locale, "%,.1f h", value)
        }

    /**
     * What every language used to get. There is no CLDR to ask below API 24, and one platform
     * version is not worth carrying a translated copy of the unit names for.
     */
    private fun legacy(hours: Long, minutes: Long, seconds: Long): String = when {
        hours > 0 -> String.format(locale, "%dh %02dm", hours, minutes)
        minutes > 0 -> String.format(locale, "%dm", minutes)
        else -> String.format(locale, "%ds", seconds)
    }

    val dateTime: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
    val date: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(locale)

    /**
     * The abbreviated day of the week, out of CLDR — the same reason the units go through ICU. An
     * `EEE` pattern resolves against the locale's own data, so there is no English "Mon" leaking into
     * every other language, and nothing here for anyone to translate.
     */
    val weekday: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", locale)

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

/**
 * Every `android.icu` type in this file, behind one API check.
 *
 * A class rather than a few guarded calls so that the version gate is the single act of building
 * it: nothing outside can name a `MeasureUnit`, so there is nowhere left to reach API 24 from a
 * path that has not checked. `Formatters` holds one or null and falls back on the null.
 *
 * NARROW rather than SHORT because these sit in chips, cards and a headline where "3h 7m" belongs
 * and "3 hrs, 7 mins" does not. Two formats because the whole-unit durations want no decimals and
 * the lifetime headline wants exactly one.
 */
@RequiresApi(Build.VERSION_CODES.N)
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
