package it.eldavo.ylih.listing

import it.eldavo.ylih.data.BatterySampleEntity
import it.eldavo.ylih.data.DeviceEntity
import it.eldavo.ylih.data.DeviceKind
import it.eldavo.ylih.data.EndReason
import it.eldavo.ylih.data.PairEntity
import it.eldavo.ylih.data.SessionEntity
import it.eldavo.ylih.data.YlihDatabase
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Random

/**
 * The database behind the Play listing screenshots.
 *
 * Written straight through the DAOs rather than `SessionRepository`: the repository reconciles
 * against the wall clock and would refuse to backdate a year of history. Nothing here needs
 * those invariants, only a plausible year of listening, so the rows are laid down directly.
 *
 * Everything anchors to the moment the screenshots are taken, so the listing never shows a
 * "last seen" date from whenever the images were recorded.
 */
object DemoData {

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    /** Fixed seed: the same story every run, so re-recording is a no-op unless the UI changed. */
    private const val SEED = 20260725L

    /** Percentage points between two battery readings. */
    private const val STEP_POINTS = 5

    suspend fun seed(db: YlihDatabase, now: Long, zone: ZoneId = ZoneId.systemDefault()) {
        val random = Random(SEED)
        val devices = db.deviceDao()
        val pairs = db.pairDao()
        val sessions = db.sessionDao()
        val samples = db.batterySampleDao()

        // The headline device: two generations of the same headphones, the one thing this app
        // does that a battery-stats screen cannot.
        val overEar = devices.insert(
            DeviceEntity(
                deviceKey = "bt:4c:2f",
                kind = DeviceKind.BLUETOOTH,
                defaultName = "Sony WH-1000XM4",
                firstSeenAt = now - 430 * DAY,
            ),
        )
        val earbuds = devices.insert(
            DeviceEntity(
                deviceKey = "bt:9a:71",
                kind = DeviceKind.BLE,
                defaultName = "Galaxy Buds3 Pro",
                firstSeenAt = now - 84 * DAY,
            ),
        )
        val wired = devices.insert(
            DeviceEntity(
                deviceKey = "wired:analog",
                kind = DeviceKind.WIRED,
                defaultName = "Wired headphones",
                firstSeenAt = now - 38 * DAY,
            ),
        )

        val retired = pairs.insert(
            PairEntity(
                deviceId = overEar,
                label = "Sony WH-1000XM4",
                generation = 1,
                startedAt = now - 430 * DAY,
                retiredAt = now - 138 * DAY,
                retireReason = "left earcup died",
                purchaseDate = now - 430 * DAY,
                priceCents = 34_900,
            ),
        )
        val current = pairs.insert(
            PairEntity(
                deviceId = overEar,
                label = "Sony WH-1000XM4",
                generation = 2,
                startedAt = now - 138 * DAY,
                purchaseDate = now - 138 * DAY,
                priceCents = 27_900,
            ),
        )
        val buds = pairs.insert(
            PairEntity(
                deviceId = earbuds,
                label = "Galaxy Buds3 Pro",
                generation = 1,
                startedAt = now - 84 * DAY,
                purchaseDate = now - 84 * DAY,
                priceCents = 19_900,
            ),
        )
        val wiredPair = pairs.insert(
            PairEntity(
                deviceId = wired,
                // A renamed pair: the device identity stays the generic thing Android reports,
                // since it can't tell two wired sets apart. Showing both makes the distinction
                // visible, and keeps "Wired headphones" out of the Italian screenshots, where
                // it'd read as a missed translation.
                label = "Sennheiser HD 25",
                generation = 1,
                startedAt = now - 38 * DAY,
            ),
        )

        // Generation 1 wore out; generation 2 is the daily driver. Playback is measured only on
        // the two devices a detailed-tracking user would have switched on.
        //
        // Battery is reported by the two Bluetooth pairs, not the others: a wired set has no
        // battery, and generation 1 is retired, so its page is the frozen-totals one. The
        // declining figures are the point — a pair whose charge still buys what it did when new
        // says nothing about battery health, the screen being advertised.
        generate(sessions, samples, retired, now, zone, random, from = 430, to = 139, perDay = 1.6, playback = false)
        generate(
            sessions, samples, current, now, zone, random,
            from = 138, to = 0, perDay = 1.9, playback = false,
            battery = Battery(newHours = 30.0, wornHours = 21.0),
        )
        generate(
            sessions, samples, buds, now, zone, random,
            from = 84, to = 0, perDay = 1.2, playback = true,
            battery = Battery(newHours = 6.5, wornHours = 5.0),
        )
        generate(sessions, samples, wiredPair, now, zone, random, from = 38, to = 0, perDay = 0.7, playback = true)

        // One live connection, so the lifetime figures tick and the "Connected" chip is shown.
        val live = sessions.insert(
            SessionEntity(
                pairId = current,
                connectedAt = now - (83 * MINUTE),
                disconnectedAt = null,
                playingMs = null,
                heartbeatAt = now,
            ),
        )
        // Draining while the screenshot is taken, as an open session on a phone would be.
        drain(samples, live, current, now - (83 * MINUTE), now, hoursPerCharge = 21.0, from = 68)
    }

    /** How long a charge lasts, when the pair was new and by the end of the history generated. */
    private data class Battery(val newHours: Double, val wornHours: Double)

    /**
     * Lays down listening sessions between two day offsets. [perDay] is an average rather than a
     * count — real listening is lumpy, and a flat two-sessions-every-day makes the daily bar
     * chart look like a test fixture, which a store screenshot must not do.
     */
    private suspend fun generate(
        sessions: it.eldavo.ylih.data.SessionDao,
        samples: it.eldavo.ylih.data.BatterySampleDao,
        pairId: Long,
        now: Long,
        zone: ZoneId,
        random: Random,
        from: Int,
        to: Int,
        perDay: Double,
        playback: Boolean,
        battery: Battery? = null,
    ) {
        val today = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), zone).toLocalDate()
        // Carried across sessions: what a session starts at is what the last one left, unless
        // the pair was charged in between.
        var level = 100
        for (daysAgo in from downTo to) {
            val date = today.minusDays(daysAgo.toLong())
            // Weekends run longer, and roughly one day in six has no listening at all.
            val weekend = date.dayOfWeek.value >= 6
            val count = (perDay * (if (weekend) 1.5 else 1.0) + random.nextDouble() - 0.5).toInt()
            if (count <= 0 || random.nextInt(6) == 0) continue

            val midnight = date.atStartOfDay(zone).toInstant().toEpochMilli()
            var cursor = midnight + 8 * HOUR + random.nextInt(90).toLong() * MINUTE
            repeat(count) {
                val length = (25 + random.nextInt(if (weekend) 190 else 130)).toLong() * MINUTE
                val start = cursor
                val end = start + length
                // Never write a session into the future; today is only partly over.
                if (end >= now - 90 * MINUTE) return@repeat
                val sessionId = sessions.insert(
                    SessionEntity(
                        pairId = pairId,
                        connectedAt = start,
                        disconnectedAt = end,
                        // Headphones spend real time round a neck doing nothing; that gap is why
                        // playback measurement exists.
                        playingMs = if (playback) (length * (55 + random.nextInt(40)) / 100) else null,
                        heartbeatAt = end,
                        endReason = EndReason.DISCONNECTED,
                    ),
                )
                if (battery != null) {
                    // The charge lasts less as the pair ages — the shape the cycle chart exists to
                    // show. Interpolated over the range rather than modelled: a store screenshot
                    // must be plausible, not simulated.
                    val worn = (from - daysAgo).toDouble() / (from - to).coerceAtLeast(1)
                    val hours = battery.newHours + (battery.wornHours - battery.newHours) * worn
                    level = drain(samples, sessionId, pairId, start, end, hours, level)
                    // Put on charge when it gets low, as a person would. Never mid-session, so
                    // no reading pair ever straddles a charge.
                    if (level <= 8 + random.nextInt(22)) level = 100
                }
                cursor = end + (40 + random.nextInt(220)).toLong() * MINUTE
            }
        }
    }

    /**
     * Writes one session's worth of battery readings and returns the level it ended on.
     *
     * Readings come in [STEP_POINTS] steps rather than per point — both what most headsets
     * actually report and what keeps this to a few hundred rows a pair, since these classes seed
     * once per test in the ordinary unit-test run too, not only when recording. The first
     * reading is at the connect, since that's when most headsets report — the case
     * `BtBatteryReceiver` retries for.
     */
    private suspend fun drain(
        samples: it.eldavo.ylih.data.BatterySampleDao,
        sessionId: Long,
        pairId: Long,
        start: Long,
        end: Long,
        hoursPerCharge: Double,
        from: Int,
    ): Int {
        val stepMs = (hoursPerCharge * HOUR * STEP_POINTS / 100).toLong().coerceAtLeast(MINUTE)
        var level = from
        var at = start
        samples.insert(BatterySampleEntity(sessionId = sessionId, pairId = pairId, at = at, level = level))
        while (at + stepMs <= end && level > STEP_POINTS) {
            at += stepMs
            level -= STEP_POINTS
            samples.insert(
                BatterySampleEntity(sessionId = sessionId, pairId = pairId, at = at, level = level),
            )
        }
        return level
    }
}
