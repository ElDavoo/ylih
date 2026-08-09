package it.eldavo.ylih.ui

import android.os.Build
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every formatter here goes through `Locale.getDefault()`, which no resource qualifier reaches —
 * the same trap `StoreScreenshots` has to work around. Pinning the locale is therefore part of
 * the test rather than an incidental setup detail.
 *
 * Under Robolectric rather than as a plain JVM test, because the durations come from
 * `android.icu` — which is the point of them: the units are CLDR's, in every language the app
 * ships, and not something anyone here has to translate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
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
        assertEquals("3h 7m", formatDurationShort(3 * 3_600_000L + 7 * 60_000L))
        assertEquals("12m", formatDurationShort(12 * 60_000L))
        assertEquals("45s", formatDurationShort(45_000L))
        assertEquals("0s", formatDurationShort(0))
    }

    /**
     * The whole reason these go through ICU. "h", "m" and "s" are English, and a screen reader
     * says them aloud in all 77 languages; CLDR has the abbreviation each language actually uses,
     * and the ordering and separators that go with it.
     */
    @Test
    fun `every language gets its own units, not English ones`() {
        val threeSeven = 3 * 3_600_000L + 7 * 60_000L

        Locale.setDefault(Locale.ITALY)
        assertEquals("3h 7min", formatDurationShort(threeSeven))

        Locale.setDefault(Locale.JAPAN)
        assertEquals("3h7m", formatDurationShort(threeSeven))

        Locale.setDefault(Locale.forLanguageTag("fi"))
        assertEquals("3t 7min", formatDurationShort(threeSeven))
        assertEquals("45s", formatDurationShort(45_000L))
    }

    /**
     * Android 6 has no `android.icu`, and still has to print something. It gets the format every
     * language used to get, zero padding and all — this branch is the old code, unchanged.
     */
    @Test
    @Config(sdk = [Build.VERSION_CODES.M])
    fun `below android 7 the units fall back to the ones every language used to get`() {
        assertEquals("3h 07m", formatDurationShort(3 * 3_600_000L + 7 * 60_000L))
        assertEquals("12m", formatDurationShort(12 * 60_000L))
        assertEquals("1,240.5 h", formatHours(1_240_500 * 3_600L))
    }

    @Test
    fun `a clock that jumped backwards never prints a negative duration`() {
        assertEquals("0s", formatDurationShort(-5_000))
        assertEquals("0.0h", formatHours(-5_000))
    }

    @Test
    fun `the lifetime headline is grouped and given one decimal`() {
        assertEquals("1,240.5h", formatHours(1_240_500 * 3_600L))

        Locale.setDefault(Locale.ITALY)
        assertEquals("a decimal comma and a point for grouping", "1.240,5h", formatHours(1_240_500 * 3_600L))
    }

    @Test
    fun `dates and times are rendered in the current locale`() {
        // 2026-05-20 14:30 Europe/Rome.
        val at = java.time.ZonedDateTime.of(2026, 5, 20, 14, 30, 0, 0, rome)
            .toInstant().toEpochMilli()
        assertEquals("20 May 2026", formatDate(at, rome))
        assertEquals("20 May 2026, 14:30", formatDateTime(at, rome))
        assertEquals("20/05", formatDayLabel(LocalDate.of(2026, 5, 20)))
    }

    /**
     * The chart axis used to be a hardcoded `d/M`, which is the wrong order in a good number of
     * the languages this app ships. Two locales that disagree about it is the whole assertion.
     */
    @Test
    fun `the chart axis follows the locale rather than one fixed order`() {
        val day = LocalDate.of(2026, 5, 20)

        Locale.setDefault(Locale.US)
        assertEquals("month first in en-US", "5/20", formatDayLabel(day))

        Locale.setDefault(Locale.UK)
        assertEquals("day first in en-GB", "20/05", formatDayLabel(day))

        Locale.setDefault(Locale.JAPAN)
        assertEquals("and no year in any of them", "05/20", formatDayLabel(day))
    }

    @Test
    fun `money keeps two decimals and cost per hour keeps three`() {
        assertEquals("349.00", formatMoney(34_900))
        assertEquals("0.281", formatPerHour(0.2814))
    }

    /**
     * The price a pair was bought for is typed once and then re-read every time the dialog is
     * opened, so the two directions have to agree exactly. They used to disagree twice over: the
     * field was filled from `cents / 100`, which dropped the minor units, and the answer was
     * parsed as a Double, which lost a cent to binary rounding.
     */
    @Test
    fun `a price survives being written into the field and read back`() {
        for (cents in listOf(0L, 5L, 99L, 1_299L, 12_345L, 1_234_567L)) {
            assertEquals("$cents cents", cents, parsePriceCents(formatPriceInput(cents)))
        }
    }

    @Test
    fun `a typed price keeps its minor units`() {
        assertEquals(1_299L, parsePriceCents("12.99"))
        assertEquals(1_299L, parsePriceCents(" 12,99 "))
        assertEquals(12_300L, parsePriceCents("123"))
        // Rounded rather than truncated, so half a cent does not quietly disappear.
        assertEquals(1_300L, parsePriceCents("12.995"))
    }

    @Test
    fun `anything that is not a price reads as no price at all`() {
        assertEquals(null, parsePriceCents(""))
        assertEquals(null, parsePriceCents("   "))
        assertEquals(null, parsePriceCents("free"))
        assertEquals(null, parsePriceCents("1.234.56"))
        assertEquals(null, parsePriceCents("-5"))
    }

    @Test
    fun `the field is filled without grouping so it can be read back`() {
        // formatMoney groups for display; the field must not, or "1,234.56" comes back as 1.234.
        assertEquals("1,234.56", formatMoney(123_456))
        assertEquals("1234.56", formatPriceInput(123_456))
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
