package it.eldavo.ylih.tracking

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import it.eldavo.ylih.YlihApp
import it.eldavo.ylih.data.DeviceEntity
import it.eldavo.ylih.data.DeviceKind
import it.eldavo.ylih.data.PairEntity
import it.eldavo.ylih.data.SessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In the default mode these two receivers *are* the app: nothing of ours is resident, so a
 * broadcast that does not reach the database is a pair of headphones whose hours are lost. They
 * are dispatched here through the real manifest filters rather than called directly, because
 * `goAsync()` is what keeps the process alive long enough for Room to finish.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class ReceiversTest {

    private val app: YlihApp = ApplicationProvider.getApplicationContext()
    private val db get() = app.container.database
    private val adapter = app.getSystemService(BluetoothManager::class.java).adapter
    private val audioManager = app.getSystemService(AudioManager::class.java)

    private val hour = 3_600_000L
    private val now get() = app.container.clock.now()

    @Before
    fun setUp() = runBlocking {
        // syncWithSystem() reaches WorkManager, whose androidx.startup initializer never runs here.
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        db.deviceDao().deleteAll()
    }

    private fun headset(
        address: String = "80:C3:BA:A6:5E:C2",
        name: String? = "ACCENTUM Plus",
    ): BluetoothDevice = adapter.getRemoteDevice(address).also { shadowOf(it).setName(name) }

    /**
     * `BluetoothClass` has no public constructor in the SDK, and the class bits are the only
     * thing that tells a car stereo from a headset. `0x0420` is AUDIO_VIDEO / CAR_AUDIO.
     */
    private fun carStereo(): BluetoothDevice = headset(name = "Golf").also {
        shadowOf(it).setBluetoothClass(
            ReflectionHelpers.callConstructor(
                BluetoothClass::class.java,
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 0x0420),
            ),
        )
    }

    private fun broadcast(action: String, device: BluetoothDevice? = null) {
        val intent = Intent(action)
        device?.let { intent.putExtra(BluetoothDevice.EXTRA_DEVICE, it) }
        app.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** The battery broadcast, whose extra the SDK cannot name — see [BatteryBroadcast]. */
    private fun broadcastBattery(level: Int, device: BluetoothDevice = headset()) {
        app.sendBroadcast(
            Intent(BatteryBroadcast.ACTION_BATTERY_LEVEL_CHANGED)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                .putExtra(BatteryBroadcast.EXTRA_BATTERY_LEVEL, level),
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * `goAsync()` borrows the process from Android and hands back a token; returning it in a
     * `finally` is the only reason a receiver that failed is a lost session rather than a
     * process Android complains about and eventually stops trusting. An ordered broadcast is
     * what makes that observable — the result receiver runs only once every receiver has
     * finished, so this timing out *is* the assertion that the token leaked.
     */
    private fun broadcastAndAwaitFinish(action: String, device: BluetoothDevice? = null) {
        val intent = Intent(action)
        device?.let { intent.putExtra(BluetoothDevice.EXTRA_DEVICE, it) }
        sendOrderedAndAwaitFinish(intent)
    }

    private fun sendOrderedAndAwaitFinish(intent: Intent) {
        val finished = AtomicBoolean(false)
        app.sendOrderedBroadcast(
            intent,
            null,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    finished.set(true)
                }
            },
            null,
            0,
            null,
            null,
        )
        settle("the receiver to hand its broadcast token back") { finished.get() }
    }

    /**
     * Also the proof that the ordered broadcast reached the manifest receiver at all: with
     * nothing to observe in a database that is closed, a dispatch that quietly went nowhere
     * would finish just as promptly.
     */
    private fun assertFailureWasLoggedBy(tag: String) {
        assertTrue(
            "nothing was logged under $tag: ${ShadowLog.getLogs().map { it.tag }}",
            ShadowLog.getLogsForTag(tag).any { it.type == Log.ERROR && it.throwable != null },
        )
    }

    /**
     * What the audio stack reports while the headset is on. Opening a session schedules the
     * heartbeat, and under the test scheduler that worker starts the moment it is enqueued —
     * against the real container — so it reconciles the row the broadcast has just written
     * against this list. Left empty, it reads as a pair that is already gone again, which is
     * what made these tests pass or fail on how fast the rest of the JVM was.
     */
    private fun listHeadsetAsConnected() {
        shadowOf(audioManager).setOutputDevices(
            listOf(
                outputDevice(
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    address = "XX:XX:XX:XX:5E:C2",
                    productName = "ACCENTUM Plus",
                ),
            ),
        )
    }

    /** The receivers hand their work to a background scope and finish on Room's own threads. */
    private fun settle(what: String, until: () -> Boolean) {
        repeat(500) {
            shadowOf(Looper.getMainLooper()).idle()
            if (until()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun sessions(): List<SessionEntity> = runBlocking { db.sessionDao().getAll() }

    /** A session left open by a phone that was switched off before the disconnect arrived. */
    private fun seedSessionOpenSinceBeforeBoot(): Long = runBlocking {
        val deviceId = db.deviceDao().insert(
            DeviceEntity(
                deviceKey = "bt:5E:C2",
                kind = DeviceKind.BLUETOOTH,
                defaultName = "ACCENTUM Plus",
                firstSeenAt = now - 10 * hour,
            ),
        )
        val pairId = db.pairDao().insert(
            PairEntity(
                deviceId = deviceId,
                label = "ACCENTUM Plus",
                generation = 1,
                startedAt = now - 10 * hour,
            ),
        )
        db.sessionDao().insert(
            SessionEntity(
                pairId = pairId,
                connectedAt = now - 10 * hour,
                heartbeatAt = now - 9 * hour,
            ),
        )
    }

    @Test
    fun `a connect broadcast is the whole of Bluetooth-only tracking`() {
        listHeadsetAsConnected()

        broadcast(BluetoothDevice.ACTION_ACL_CONNECTED, headset())

        settle("the session to open") { sessions().isNotEmpty() }
        val session = sessions().single()
        assertNull("still connected", session.disconnectedAt)
        // Only the service can measure playback, so this session records none at all.
        assertNull(session.playingMs)
        assertEquals(
            "the two platform views of one headset have to agree on the key",
            "bt:5E:C2",
            runBlocking { db.deviceDao().getAll().single().deviceKey },
        )
    }

    @Test
    fun `a disconnect broadcast closes the session the connect opened`() {
        // Still listed as connected, so the close under test is the broadcast's and not a
        // reconcile deciding the pair had vanished.
        listHeadsetAsConnected()
        broadcast(BluetoothDevice.ACTION_ACL_CONNECTED, headset())
        settle("the session to open") { sessions().isNotEmpty() }

        broadcast(BluetoothDevice.ACTION_ACL_DISCONNECTED, headset())

        settle("the session to close") { sessions().single().disconnectedAt != null }
        assertEquals("one connection is one session", 1, sessions().size)
    }

    @Test
    fun `a headset with no name at all is still identified by its address`() {
        broadcast(BluetoothDevice.ACTION_ACL_CONNECTED, headset(name = null))

        settle("the session to open") { sessions().isNotEmpty() }
        assertEquals("bt:5E:C2", runBlocking { db.deviceDao().getAll().single().deviceKey })
    }

    @Test
    fun `a broadcast the receiver cannot make sense of is dropped, not crashed`() {
        // Both early returns run before goAsync(), so nothing is left pending either way.
        BtConnectionReceiver().onReceive(app, Intent(Intent.ACTION_POWER_CONNECTED))
        BtConnectionReceiver().onReceive(app, Intent(BluetoothDevice.ACTION_ACL_CONNECTED))
        // A car stereo is an audio sink and pairs like a headset, but nobody wears one.
        BtConnectionReceiver().onReceive(
            app,
            Intent(BluetoothDevice.ACTION_ACL_CONNECTED)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, carStereo()),
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(sessions().isEmpty())
    }

    @Test
    fun `booting closes what the shutdown never told us about`() {
        seedSessionOpenSinceBeforeBoot()

        broadcast(Intent.ACTION_BOOT_COMPLETED)

        settle("the pre-boot session to be closed") { sessions().single().disconnectedAt != null }
        val session = sessions().single()
        assertEquals(
            "the hours the phone spent switched off are not listening time",
            session.heartbeatAt,
            session.disconnectedAt,
        )
    }

    @Test
    fun `reinstalling the app repairs the database too`() {
        seedSessionOpenSinceBeforeBoot()

        broadcast(Intent.ACTION_MY_PACKAGE_REPLACED)

        settle("the stale session to be closed") { sessions().single().disconnectedAt != null }
    }

    @Test
    fun `a broadcast BootReceiver does not handle changes nothing`() {
        seedSessionOpenSinceBeforeBoot()

        BootReceiver().onReceive(app, Intent(Intent.ACTION_TIME_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(sessions().single().disconnectedAt)
    }

    @Test
    fun `a connect the database cannot record costs one session, not the process`() {
        // Nothing of the app is resident in this mode, so the receiver is the only thing that can
        // put the process back. Closing the database is the cheapest honest way to make the work
        // it hands to the background scope throw.
        db.close()

        broadcastAndAwaitFinish(BluetoothDevice.ACTION_ACL_CONNECTED, headset())

        assertFailureWasLoggedBy("BtConnectionReceiver")
    }

    @Test
    fun `a boot reconcile that fails still hands the process back`() {
        db.close()

        broadcastAndAwaitFinish(Intent.ACTION_BOOT_COMPLETED)

        assertFailureWasLoggedBy("BootReceiver")
    }

    @Test
    fun `the heartbeat worker closes a session nothing is connected to any more`() {
        val sessionId = seedSessionOpenSinceBeforeBoot()

        val result = runBlocking {
            TestListenableWorkerBuilder<HeartbeatWorker>(app).build().doWork()
        }

        assertEquals(ListenableWorker.Result.success(), result)
        assertNotNull(
            "a missed disconnect costs one interval, not forever",
            runBlocking { db.sessionDao().getAll().single { it.id == sessionId }.disconnectedAt },
        )
    }

    private fun batterySamples() = runBlocking { db.batterySampleDao().getAll() }

    /**
     * The battery broadcast is `@SystemApi` and so is named by its string here as it is in the
     * app — dispatched through the real manifest filter, because a receiver the merged manifest
     * does not carry is a feature that works in a test and nowhere else.
     */
    @Test
    fun `battery levels reported during a session become charge-cycle readings`() {
        listHeadsetAsConnected()
        broadcast(BluetoothDevice.ACTION_ACL_CONNECTED, headset())
        settle("the session to open") { sessions().isNotEmpty() }

        broadcastBattery(90)
        settle("the first reading to land") { batterySamples().isNotEmpty() }
        broadcastBattery(80)
        settle("the second reading to land") { batterySamples().size == 2 }

        assertEquals(listOf(90, 80), batterySamples().map { it.level })
        assertEquals(sessions().single().id, batterySamples().first().sessionId)
    }

    @Test
    fun `the level a disconnect announces is not a reading`() {
        listHeadsetAsConnected()
        broadcast(BluetoothDevice.ACTION_ACL_CONNECTED, headset())
        settle("the session to open") { sessions().isNotEmpty() }

        // -1 is BATTERY_LEVEL_UNKNOWN, which the stack broadcasts for every device on every
        // disconnect; -100 is BATTERY_LEVEL_BLUETOOTH_OFF. Taken at face value the first is a
        // drop of the whole battery into nothing.
        broadcastBattery(-1)
        broadcastBattery(-100)
        broadcastBattery(70)

        settle("the real reading to land") { batterySamples().isNotEmpty() }
        assertEquals(listOf(70), batterySamples().map { it.level })
    }

    @Test
    fun `a battery broadcast the receiver cannot use is dropped, not crashed`() {
        // Every one of these returns before goAsync(), so nothing is left pending either way.
        BtBatteryReceiver().onReceive(app, Intent(Intent.ACTION_POWER_CONNECTED))
        BtBatteryReceiver().onReceive(app, Intent(BatteryBroadcast.ACTION_BATTERY_LEVEL_CHANGED))
        // A car stereo reports its battery like anything else on the link, and nobody wears one.
        BtBatteryReceiver().onReceive(
            app,
            Intent(BatteryBroadcast.ACTION_BATTERY_LEVEL_CHANGED)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, carStereo())
                .putExtra(BatteryBroadcast.EXTRA_BATTERY_LEVEL, 80),
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(batterySamples().isEmpty())
    }

    @Test
    fun `a battery reading the database cannot record costs the reading, not the process`() {
        db.close()

        sendOrderedAndAwaitFinish(
            Intent(BatteryBroadcast.ACTION_BATTERY_LEVEL_CHANGED)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, headset())
                .putExtra(BatteryBroadcast.EXTRA_BATTERY_LEVEL, 80),
        )

        assertFailureWasLoggedBy("BtBatteryReceiver")
    }
}
