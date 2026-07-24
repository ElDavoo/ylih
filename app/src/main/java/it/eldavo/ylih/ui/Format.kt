package it.eldavo.ylih.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

fun formatDateTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
        .format(Instant.ofEpochMilli(epochMs).atZone(zone))

fun formatDate(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(Instant.ofEpochMilli(epochMs).atZone(zone))

fun formatDayLabel(date: LocalDate): String =
    DateTimeFormatter.ofPattern("d/M", Locale.getDefault()).format(date)

fun formatMoney(cents: Long): String =
    String.format(Locale.getDefault(), "%,.2f", cents / 100.0)

/** Cost per listening hour; three decimals because the number gets small fast. */
fun formatPerHour(value: Double): String =
    String.format(Locale.getDefault(), "%.3f", value)
