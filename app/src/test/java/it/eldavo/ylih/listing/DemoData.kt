package it.eldavo.ylih.listing

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
 * It is written straight through the DAOs rather than through `SessionRepository`, on purpose:
 * the repository's job is to reconcile against the wall clock and it would refuse to backdate a
 * year of history. Nothing here has to survive those invariants, it only has to look like a
 * plausible year of listening — so the rows are laid down directly.
 *
 * Everything is anchored to the moment the screenshots are taken, so the listing never shows a
 * "last seen" date from whenever the images happened to be recorded.
 */
object DemoData {

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    /** Fixed seed: the same story every run, so re-recording is a no-op unless the UI changed. */
    private const val SEED = 20260725L

    suspend fun seed(db: YlihDatabase, now: Long, zone: ZoneId = ZoneId.systemDefault()) {
        val random = Random(SEED)
        val devices = db.deviceDao()
        val pairs = db.pairDao()
        val sessions = db.sessionDao()

        // The headline device: two generations of the same headphones, which is the one thing
        // this app does that a battery-stats screen cannot.
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
                // because it cannot tell two wired sets apart. Showing both in the listing makes
                // the distinction visible — and keeps an English UI phrase out of the Italian
                // screenshots, where "Wired headphones" would read as a missed translation.
                label = "Sennheiser HD 25",
                generation = 1,
                startedAt = now - 38 * DAY,
            ),
        )

        // Generation 1 wore out; generation 2 is the daily driver. Playback is only measured on
        // the two devices a detailed-tracking user would have had switched on for.
        generate(sessions, retired, now, zone, random, from = 430, to = 139, perDay = 1.6, playback = false)
        generate(sessions, current, now, zone, random, from = 138, to = 0, perDay = 1.9, playback = false)
        generate(sessions, buds, now, zone, random, from = 84, to = 0, perDay = 1.2, playback = true)
        generate(sessions, wiredPair, now, zone, random, from = 38, to = 0, perDay = 0.7, playback = true)

        // One live connection, so the lifetime figures tick and the "Connected" chip is shown.
        sessions.insert(
            SessionEntity(
                pairId = current,
                connectedAt = now - (83 * MINUTE),
                disconnectedAt = null,
                playingMs = null,
                heartbeatAt = now,
            ),
        )
    }

    /**
     * Lays down listening sessions between two day offsets. [perDay] is an average rather than a
     * count — real listening is lumpy, and a flat two-sessions-every-day makes the daily bar
     * chart look like a test fixture, which is exactly what a store screenshot must not do.
     */
    private suspend fun generate(
        sessions: it.eldavo.ylih.data.SessionDao,
        pairId: Long,
        now: Long,
        zone: ZoneId,
        random: Random,
        from: Int,
        to: Int,
        perDay: Double,
        playback: Boolean,
    ) {
        val today = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), zone).toLocalDate()
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
                sessions.insert(
                    SessionEntity(
                        pairId = pairId,
                        connectedAt = start,
                        disconnectedAt = end,
                        // Headphones spend real time round a neck doing nothing; that gap is the
                        // whole reason playback measurement exists.
                        playingMs = if (playback) (length * (55 + random.nextInt(40)) / 100) else null,
                        heartbeatAt = end,
                        endReason = EndReason.DISCONNECTED,
                    ),
                )
                cursor = end + (40 + random.nextInt(220)).toLong() * MINUTE
            }
        }
    }
}
