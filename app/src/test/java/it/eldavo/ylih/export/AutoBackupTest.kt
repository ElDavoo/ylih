package it.eldavo.ylih.export

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import it.eldavo.ylih.R
import it.eldavo.ylih.YlihApp
import it.eldavo.ylih.data.AutoBackupError
import it.eldavo.ylih.data.DeviceEntity
import it.eldavo.ylih.data.DeviceKind
import it.eldavo.ylih.data.PairEntity
import it.eldavo.ylih.data.YlihDatabase
import it.eldavo.ylih.tracking.Notifications
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.ZoneId

/**
 * Automatic backups exist for someone who won't look for months, so each way a backup can go
 * wrong has to leave the folder holding what it held and the user told — never a folder quietly
 * emptied or a file that won't import.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class AutoBackupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val app: YlihApp = ApplicationProvider.getApplicationContext()
    private val settings get() = app.container.settings
    private val zone: ZoneId = ZoneId.of("Europe/Rome")
    private val notifications get() = shadowOf(app.getSystemService(NotificationManager::class.java))

    private val grant = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    @Before
    fun setUp() {
        FakeDocumentsProvider.install(tmp.root)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        runBlocking {
            val deviceId = app.container.database.deviceDao().insert(
                DeviceEntity(
                    deviceKey = "bt:5E:C2",
                    kind = DeviceKind.BLUETOOTH,
                    defaultName = "ACCENTUM",
                    firstSeenAt = 0,
                ),
            )
            app.container.database.pairDao().insert(
                PairEntity(deviceId = deviceId, label = "ACCENTUM", generation = 1, startedAt = 0),
            )
        }
    }

    private suspend fun enable() {
        app.contentResolver.takePersistableUriPermission(FakeDocumentsProvider.treeUri, grant)
        settings.setAutoBackupFolder(FakeDocumentsProvider.treeUri.toString())
    }

    private fun files(): List<String> = tmp.root.list().orEmpty().sorted()

    @Test
    fun `with no folder chosen nothing is written`() = runTest {
        assertNull(AutoBackup.run(app, zone))
        assertEquals(emptyList<String>(), files())
    }

    @Test
    fun `a backup lands in the folder and restores the history it was taken from`() = runTest {
        enable()

        assertNull(AutoBackup.run(app, zone))

        val file = tmp.root.listFiles()!!.single()
        assertTrue(file.name, Regex("""ylih-backup-\d{8}-\d{6}\.json""").matches(file.name))
        val restored = Room.inMemoryDatabaseBuilder(app, YlihDatabase::class.java).build()
        try {
            JsonBackup.import(restored, file.readText())
            assertEquals("ACCENTUM", restored.pairDao().getAll().single().label)
        } finally {
            restored.close()
        }
        val state = settings.autoBackupNow()
        assertNotNull("the settings screen shows when it last worked", state.lastOkAt)
        assertNull(state.error)
    }

    @Test
    fun `the file is named for the local time it was written`() {
        // 2026-09-21 08:30:05 UTC is 10:30:05 in Rome, which is what the user browsing sees.
        assertEquals("ylih-backup-20260921-103005.json", AutoBackup.fileName(1_789_979_405_000L, zone))
    }

    @Test
    fun `only the newest ten of its own files are kept, and nothing else is touched`() = runTest {
        enable()
        val old = (1..12).map { "ylih-backup-202001%02d-120000.json".format(it) }
        old.forEach { tmp.root.resolve(it).writeText("{}") }
        tmp.root.resolve("holiday.jpg").writeText("not ours")
        tmp.root.resolve("ylih-backup-notes.txt").writeText("not ours either")

        assertNull(AutoBackup.run(app, zone))

        val kept = files().filter { it.startsWith("ylih-backup-2") }
        assertEquals(AutoBackup.KEPT, kept.size)
        // The new one is the newest by name, so it survives and the three oldest go.
        assertTrue("the oldest are pruned", old.take(3).none { it in kept })
        assertTrue(files().containsAll(listOf("holiday.jpg", "ylih-backup-notes.txt")))
    }

    @Test
    fun `a grant that is gone is reported, told once, and never retried into a loop`() = runTest {
        // A phone restored from Android's own backup: the row came back, the grant did not.
        settings.setAutoBackupFolder(FakeDocumentsProvider.treeUri.toString())

        assertEquals(AutoBackupError.ACCESS_LOST, AutoBackup.run(app, zone))
        assertEquals(AutoBackupError.ACCESS_LOST, settings.autoBackupNow().error)
        val posted = notifications.allNotifications.single()
        assertEquals(
            app.getString(R.string.settings_auto_backup_access_lost),
            posted.extras.getString(android.app.Notification.EXTRA_TEXT),
        )

        app.getSystemService(NotificationManager::class.java).cancelAll()
        assertEquals(AutoBackupError.ACCESS_LOST, AutoBackup.run(app, zone))
        assertEquals("a folder that stays gone is not news twice", 0, notifications.allNotifications.size)
    }

    @Test
    fun `a folder deleted under the grant is access lost`() = runTest {
        enable()
        FakeDocumentsProvider.failCreate = IllegalArgumentException("Parent document no longer exists")

        assertEquals(AutoBackupError.ACCESS_LOST, AutoBackup.run(app, zone))
    }

    @Test
    fun `a write that fails leaves no half file behind and the older backups alone`() = runTest {
        enable()
        val old = List(AutoBackup.KEPT) { "ylih-backup-202001%02d-120000.json".format(it + 1) }
        old.forEach { tmp.root.resolve(it).writeText("{}") }
        FakeDocumentsProvider.failOpen = IOException("no space left on device")

        assertEquals(AutoBackupError.WRITE_FAILED, AutoBackup.run(app, zone))

        assertEquals("nothing pruned by a run that wrote nothing", old, files())
        assertEquals(
            app.getString(R.string.settings_auto_backup_write_failed),
            notifications.allNotifications.single().extras.getString(android.app.Notification.EXTRA_TEXT),
        )
    }

    @Test
    fun `a success after a failure clears both the error and the notification`() = runTest {
        enable()
        FakeDocumentsProvider.failOpen = IOException("no space left on device")
        AutoBackup.run(app, zone)
        FakeDocumentsProvider.failOpen = null

        assertNull(AutoBackup.run(app, zone))

        assertNull(settings.autoBackupNow().error)
        assertEquals(0, notifications.allNotifications.size)
    }

    @Test
    fun `a folder that refuses a deletion still counts the backup written`() = runTest {
        enable()
        tmp.root.resolve("ylih-backup-20200101-120000.json").writeText("{}")
        // Deletion refused: the file stays, and so does the success.
        FakeDocumentsProvider.failDelete = SecurityException("read-only")
        repeat(AutoBackup.KEPT) { tmp.root.resolve("ylih-backup-201901%02d-120000.json".format(it + 1)).writeText("{}") }

        assertNull(AutoBackup.run(app, zone))
    }

    @Test
    fun `without notification permission the failure is still recorded for settings`() = runTest {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        settings.setAutoBackupFolder(FakeDocumentsProvider.treeUri.toString())

        assertEquals(AutoBackupError.ACCESS_LOST, AutoBackup.run(app, zone))

        assertEquals(AutoBackupError.ACCESS_LOST, settings.autoBackupNow().error)
        assertEquals(0, notifications.allNotifications.size)
    }

    @Test
    fun `the failure notification opens settings rather than the first tab`() = runTest {
        Notifications.notifyBackupFailed(app, AutoBackupError.ACCESS_LOST)

        val intent = shadowOf(notifications.allNotifications.single().contentIntent).savedIntent
        assertTrue(intent.getBooleanExtra(it.eldavo.ylih.MainActivity.EXTRA_OPEN_SETTINGS, false))
    }

    @Test
    fun `the folder is named as its provider names it, and a gone one has no name`() {
        assertEquals(
            FakeDocumentsProvider.ROOT_NAME,
            AutoBackup.folderName(app.contentResolver, FakeDocumentsProvider.treeUri),
        )
        tmp.root.deleteRecursively()
        assertNull(AutoBackup.folderName(app.contentResolver, FakeDocumentsProvider.treeUri))
    }

    @Test
    fun `the worker succeeds on a lost folder and retries a failed write`() = runTest {
        val worker = TestListenableWorkerBuilder<AutoBackupWorker>(app).build()
        settings.setAutoBackupFolder(FakeDocumentsProvider.treeUri.toString())
        assertEquals(
            "retrying cannot bring a grant back",
            ListenableWorker.Result.success(),
            worker.doWork(),
        )

        enable()
        FakeDocumentsProvider.failOpen = IOException("busy")
        assertEquals(ListenableWorker.Result.retry(), worker.doWork())

        FakeDocumentsProvider.failOpen = null
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }

    @Test
    fun `the worker retries when the database itself fails`() = runTest {
        enable()
        app.container.database.close()

        val result = TestListenableWorkerBuilder<AutoBackupWorker>(app).build().doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }
}
