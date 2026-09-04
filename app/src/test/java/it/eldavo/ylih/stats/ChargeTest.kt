package it.eldavo.ylih.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A charge cycle is a hundred percentage points used, and the hours it bought are what say whether
 * the battery is getting worse. Both halves of that ratio have to come from the same stretches of
 * time or the figure means nothing, which is what most of this file is about.
 */
class ChargeTest {

    private val hour = 3_600_000L
    private val start = 1_700_000_000_000L

    private fun reading(sessionId: Long, hoursIn: Long, level: Int) =
        Reading(sessionId, start + hoursIn * hour, level)

    /** A closed session running [hours] hours from [fromHour], playing for [playing] of them. */
    private fun span(fromHour: Long, hours: Long, playing: Long? = null) = Span(
        startAt = start + fromHour * hour,
        endAt = start + (fromHour + hours) * hour,
        playingMs = playing?.times(hour),
    )

    private fun summarize(
        readings: List<Reading>,
        spans: Map<Long, Span> = mapOf(1L to span(0, 24)),
        counting: Counting = Counting.CONNECTED,
    ) = Charge.summarize(readings, spans, now = start + 48 * hour, counting = counting)

    @Test
    fun `a drop between two readings is drain, and the time between them bought it`() {
        val summary = summarize(
            listOf(reading(1, 0, 80), reading(1, 2, 60)),
        )

        assertEquals(20, summary.pointsDrained)
        assertEquals(2 * hour, summary.countedMs)
        // Twenty points bought two hours, so a hundred would buy ten.
        assertEquals(10 * hour, summary.msPerCycle)
        assertEquals(0.2, summary.cyclesFraction, 0.0001)
        assertTrue(summary.hasData)
    }

    @Test
    fun `a level going up is a charge and counts for nothing`() {
        val summary = summarize(
            listOf(reading(1, 0, 40), reading(1, 1, 90), reading(1, 3, 80)),
        )

        // Only the 90 → 80 leg is drain; the 40 → 90 in between is the headset being charged.
        assertEquals(10, summary.pointsDrained)
        assertEquals(2 * hour, summary.countedMs)
    }

    /**
     * The rule the whole file rests on. Between these two readings the headphones were off the
     * phone: nobody knows whether they were charged in that gap, and there is certainly no
     * listening to credit it with.
     */
    @Test
    fun `a drop across a session boundary is not drain`() {
        val summary = summarize(
            listOf(reading(1, 0, 90), reading(1, 1, 80), reading(2, 20, 30), reading(2, 21, 20)),
            spans = mapOf(1L to span(0, 2), 2L to span(20, 2)),
        )

        // 90 → 80 and 30 → 20, and nothing for the 80 → 30 between the two sessions.
        assertEquals(20, summary.pointsDrained)
        assertEquals(2 * hour, summary.countedMs)
    }

    @Test
    fun `a hundred points is one cycle whatever it took to get there`() {
        // Five evenings of twenty points each, an hour apiece.
        val readings = (0L until 5L).flatMap {
            listOf(reading(it + 1, it * 5, 100), reading(it + 1, it * 5 + 1, 80))
        }
        val spans = (0L until 5L).associate { (it + 1) to span(it * 5, 2) }

        val summary = Charge.summarize(readings, spans, now = start + 48 * hour)

        assertEquals(100, summary.pointsDrained)
        assertEquals(1, summary.cycles.size)
        assertEquals(5 * hour, summary.cycles.single().countedMs)
        assertEquals(0, summary.partialPoints)
    }

    @Test
    fun `a cycle boundary inside one segment splits both the points and the hours`() {
        val summary = summarize(
            // Sixty points over six hours, then eighty more over eight: the second segment
            // straddles the hundred.
            listOf(
                reading(1, 0, 100), reading(1, 6, 40),
                reading(2, 20, 100), reading(2, 28, 20),
            ),
            spans = mapOf(1L to span(0, 7), 2L to span(20, 9)),
        )

        assertEquals(140, summary.pointsDrained)
        assertEquals(1, summary.cycles.size)
        // Forty of the second segment's eighty points complete the first cycle, so half its eight
        // hours goes with them: six plus four.
        assertEquals(10 * hour, summary.cycles.single().countedMs)
        assertEquals(start, summary.cycles.single().startAt)
        // And half its eight hours of wall clock too, so the cycle ends four hours into it.
        assertEquals(start + 24 * hour, summary.cycles.single().endAt)
        // The remainder is the cycle still in progress, not a second completed one.
        assertEquals(40, summary.partialPoints)
        assertEquals(14 * hour, summary.countedMs)
    }

    @Test
    fun `a segment of a hundred points on its own completes a cycle`() {
        // A headset that reports at full and then not again until it is flat.
        val summary = summarize(listOf(reading(1, 0, 100), reading(1, 7, 0)))

        assertEquals(1, summary.cycles.size)
        assertEquals(7 * hour, summary.cycles.single().countedMs)
        assertEquals(0, summary.partialPoints)
        assertEquals(7 * hour, summary.msPerCycle)
    }

    @Test
    fun `counting playback credits a segment its share of the session's playing time`() {
        val summary = summarize(
            listOf(reading(1, 0, 90), reading(1, 2, 70)),
            // A ten-hour session with five hours of audio in it: half the time was listening.
            spans = mapOf(1L to span(0, 10, playing = 5)),
            counting = Counting.PLAYBACK,
        )

        assertEquals(20, summary.pointsDrained)
        assertEquals(hour, summary.countedMs)
        assertEquals(5 * hour, summary.msPerCycle)
    }

    /**
     * A session recorded in Bluetooth-only mode has no playback figure at all. Crediting it zero
     * would read as a charge that gave nothing back, so its drain leaves the denominator too — the
     * same choice `Stats.counted` makes.
     */
    @Test
    fun `counting playback drops a session that never measured it`() {
        val readings = listOf(
            reading(1, 0, 90), reading(1, 2, 70),
            reading(2, 20, 60), reading(2, 22, 40),
        )
        val spans = mapOf(1L to span(0, 10, playing = 5), 2L to span(20, 10))

        val connected = Charge.summarize(readings, spans, now = start + 48 * hour)
        val playback =
            Charge.summarize(readings, spans, now = start + 48 * hour, counting = Counting.PLAYBACK)

        assertEquals(40, connected.pointsDrained)
        assertEquals(20, playback.pointsDrained)
    }

    @Test
    fun `an open session's playback share is measured against how long it has been running`() {
        val readings = listOf(reading(1, 0, 90), reading(1, 2, 70))
        val open = Span(startAt = start, endAt = null, playingMs = 2 * hour)

        // Four hours in, half of them playing: the two-hour segment is worth one hour.
        val summary = Charge.summarize(
            readings,
            mapOf(1L to open),
            now = start + 4 * hour,
            counting = Counting.PLAYBACK,
        )

        assertEquals(hour, summary.countedMs)
    }

    @Test
    fun `no readings is no answer rather than a zero`() {
        val summary = summarize(emptyList())

        assertFalse(summary.hasData)
        assertEquals(0, summary.pointsDrained)
        assertEquals(0L, summary.msPerCycle)
        assertTrue(summary.cycles.isEmpty())
    }

    @Test
    fun `one reading on its own says nothing`() {
        val summary = summarize(listOf(reading(1, 0, 100)))

        assertFalse(summary.hasData)
    }

    @Test
    fun `a repeated level is drain of nothing and is ignored`() {
        val summary = summarize(
            listOf(reading(1, 0, 70), reading(1, 1, 70), reading(1, 2, 60)),
        )

        assertEquals(10, summary.pointsDrained)
        assertEquals(hour, summary.countedMs)
    }

    /**
     * A clock corrected backwards mid-session leaves a reading dated before the one it followed.
     * The drain is real, but there is no stretch of time to credit it to, so neither side takes it
     * — the alternative is a negative number of hours dragging the whole ratio down.
     */
    @Test
    fun `a backwards clock step is dropped from both sides`() {
        val summary = summarize(
            listOf(Reading(1, start + 2 * hour, 80), Reading(1, start + hour, 60)),
        )

        assertFalse(summary.hasData)
        assertEquals(0L, summary.countedMs)
    }

    @Test
    fun `readings arriving out of order are put back in order first`() {
        val summary = summarize(
            listOf(reading(1, 2, 60), reading(1, 0, 80), reading(1, 1, 70)),
        )

        assertEquals(20, summary.pointsDrained)
        assertEquals(2 * hour, summary.countedMs)
    }

    @Test
    fun `cycles come back oldest first, so a failing battery reads as a fall`() {
        // Two full cycles: the first bought ten hours, the second only six.
        val readings = listOf(
            reading(1, 0, 100), reading(1, 10, 0),
            reading(2, 20, 100), reading(2, 26, 0),
        )
        val spans = mapOf(1L to span(0, 11), 2L to span(20, 7))

        val summary = Charge.summarize(readings, spans, now = start + 48 * hour)

        assertEquals(listOf(10 * hour, 6 * hour), summary.cycles.map { it.countedMs })
        assertEquals(8 * hour, summary.msPerCycle)
    }

    /** [hours] one per cycle, each drained in a session of its own. */
    private fun cyclesOf(vararg hours: Double): ChargeSummary {
        val readings = mutableListOf<Reading>()
        val spans = mutableMapOf<Long, Span>()
        var at = start
        hours.forEachIndexed { i, h ->
            val id = (i + 1).toLong()
            val length = (h * hour).toLong()
            spans[id] = Span(at, at + length, null)
            readings += Reading(id, at, 100)
            readings += Reading(id, at + length, 0)
            at += length + 24 * hour
        }
        return Charge.summarize(readings, spans, now = at)
    }

    @Test
    fun `a battery that has halved reports half of what it managed when new`() {
        val summary = cyclesOf(10.0, 5.0)

        assertEquals(2, summary.cycles.size)
        assertEquals(1, summary.comparisonWindow)
        assertEquals(0.5, summary.versusNew!!, 0.0001)
    }

    /**
     * The reason this is not the literal last cycle over the literal first: one unlucky pair of
     * cycles would say the battery had collapsed, when averaging both ends says it has barely
     * moved. Eight cycles average two at each end.
     */
    @Test
    fun `both ends are averaged, so one odd cycle does not decide the figure`() {
        val steady = cyclesOf(10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 4.0)

        assertEquals(2, steady.comparisonWindow)
        // (10 + 4) / 2 against (10 + 10) / 2 — not 4 against 10.
        assertEquals(0.7, steady.versusNew!!, 0.0001)
    }

    @Test
    fun `the window grows with the history but stops at five`() {
        assertEquals(1, cyclesOf(*DoubleArray(4) { 8.0 }).comparisonWindow)
        assertEquals(2, cyclesOf(*DoubleArray(8) { 8.0 }).comparisonWindow)
        assertEquals(5, cyclesOf(*DoubleArray(20) { 8.0 }).comparisonWindow)
        assertEquals(
            "and never so far that the two ends could overlap",
            MAX_COMPARISON_CYCLES,
            cyclesOf(*DoubleArray(60) { 8.0 }).comparisonWindow,
        )
    }

    @Test
    fun `one cycle cannot be compared with anything`() {
        assertNull(cyclesOf(9.0).versusNew)
        assertNull(summarize(emptyList()).versusNew)
    }

    @Test
    fun `a battery that got better than new says so rather than clamping`() {
        // Ordinary noise on a young pair, and pretending otherwise would be inventing a figure.
        assertEquals(1.2, cyclesOf(5.0, 6.0).versusNew!!, 0.0001)
    }

    @Test
    fun `a session with no span at all still counts its connected time`() {
        // Belt and braces: nothing deletes a session out from under its readings, but the maths
        // must not silently drop drain if something ever does.
        val summary = summarize(
            listOf(reading(7, 0, 80), reading(7, 1, 70)),
            spans = emptyMap(),
        )

        assertEquals(10, summary.pointsDrained)
        assertEquals(hour, summary.countedMs)
    }
}
