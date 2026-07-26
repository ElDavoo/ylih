package it.eldavo.ylih.ui

import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Every formatter here goes through `Locale.getDefault()`, which no resource qualifier reaches —
 * the same trap `StoreScreenshots` has to work around. Pinning the locale is therefore part of
 * the test rather than an incidental setup detail.
 */
class FormatTest {

    private lateinit var original: Locale
    private val rome = ZoneId.of("Europe/Rome")

    @Before
    fun setUp() {
        original = Locale.getDefault()
        Locale.setDefault(Locale.UK)
    }

    @After
    fun tearDown() {
        Locale.setDefault(original)
    }

    @Test
    fun `durations drop the units that would read as zero`() {
        assertEquals("3h 07m", formatDurationShort(3 * 3_600_000L + 7 * 60_000L))
        assertEquals("12m", formatDurationShort(12 * 60_000L))
        assertEquals("45s", formatDurationShort(45_000L))
        assertEquals("0s", formatDurationShort(0))
    }

    @Test
    fun `a clock that jumped backwards never prints a negative duration`() {
        assertEquals("0s", formatDurationShort(-5_000))
        assertEquals("0.0 h", formatHours(-5_000))
    }

    @Test
    fun `the lifetime headline is grouped and given one decimal`() {
        assertEquals("1,240.5 h", formatHours(1_240_500 * 3_600L))
    }

    @Test
    fun `dates and times are rendered in the current locale`() {
        // 2026-05-20 14:30 Europe/Rome.
        val at = java.time.ZonedDateTime.of(2026, 5, 20, 14, 30, 0, 0, rome)
            .toInstant().toEpochMilli()
        assertEquals("20 May 2026", formatDate(at, rome))
        assertEquals("20 May 2026, 14:30", formatDateTime(at, rome))
        assertEquals("20/5", formatDayLabel(LocalDate.of(2026, 5, 20)))
    }

    @Test
    fun `money keeps two decimals and cost per hour keeps three`() {
        assertEquals("349.00", formatMoney(34_900))
        assertEquals("0.281", formatPerHour(0.2814))
    }

    @Test
    fun `a share of nothing is a dash rather than a division by zero`() {
        assertEquals("—", percent(100, 0))
        assertEquals("50%", percent(1, 2))
        // Playback measured slightly past the connected span (rounding at the edges) still reads
        // as a share rather than as 101%.
        assertEquals("100%", percent(3, 2))
    }
}
