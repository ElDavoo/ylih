package it.eldavo.ylih.export

import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import it.eldavo.ylih.YlihApp
import it.eldavo.ylih.ui.YlihViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * The schedule has to follow the setting, not the other way round: a folder set with nothing
 * scheduled is backups the settings screen says are on and never happen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class AutoBackupScheduleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val app: YlihApp = ApplicationProvider.getApplicationContext()
    private val settings get() = app.container.settings

    @Before
    fun setUp() {
        // See YlihViewModelTest: this has to come before Dispatchers.setMain.
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        FakeDocumentsProvider.install(tmp.root)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun scheduled(): WorkInfo? = WorkManager.getInstance(app)
        .getWorkInfosForUniqueWork(AutoBackupWorker.NAME)
        .get()
        .firstOrNull { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }

    @Test
    fun `nothing is scheduled while backups are off`() = runTest {
        scheduleAutoBackup(app, settings)

        assertNull(scheduled())
    }

    @Test
    fun `a folder schedules backups at the chosen interval, and the routine call keeps it`() = runTest {
        settings.setAutoBackupFolder("content://docs/tree/a")
        scheduleAutoBackup(app, settings)
        val first = scheduled()!!
        assertEquals(TimeUnit.DAYS.toMillis(7), first.periodicityInfo!!.repeatIntervalMillis)

        // syncWithSystem's call, once a minute with the service up: it must not restart the clock.
        scheduleAutoBackup(app, settings)
        assertEquals(first.id, scheduled()!!.id)
    }

    @Test
    fun `sync re-arms a schedule that never came back with a restored phone`() = runTest {
        settings.setAutoBackupFolder("content://docs/tree/a")

        app.container.trackingController.syncWithSystem()

        assertTrue(scheduled() != null)
    }

    @Test
    fun `choosing a folder schedules, changing the interval moves it, and off cancels`() = runTest {
        val viewModel = YlihViewModel(app)

        viewModel.chooseAutoBackupFolder(FakeDocumentsProvider.treeUri).join()
        assertEquals(TimeUnit.DAYS.toMillis(7), scheduled()!!.periodicityInfo!!.repeatIntervalMillis)

        viewModel.setAutoBackupEvery(1).join()
        assertEquals(TimeUnit.DAYS.toMillis(1), scheduled()!!.periodicityInfo!!.repeatIntervalMillis)

        viewModel.disableAutoBackup().join()
        assertNull(scheduled())
        assertTrue(app.contentResolver.persistedUriPermissions.isEmpty())
    }

    @Test
    fun `a new folder releases the grant on the old one`() = runTest {
        val viewModel = YlihViewModel(app)
        val old = "content://${FakeDocumentsProvider.AUTHORITY}/tree/old".toUri()
        app.contentResolver.takePersistableUriPermission(
            old,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        settings.setAutoBackupFolder(old.toString())

        viewModel.chooseAutoBackupFolder(FakeDocumentsProvider.treeUri).join()

        assertEquals(
            listOf(FakeDocumentsProvider.treeUri),
            app.contentResolver.persistedUriPermissions.map { it.uri },
        )
        assertEquals(FakeDocumentsProvider.treeUri.toString(), settings.autoBackupNow().folder)
    }

    @Test
    fun `the view model names the folder, or falls back to its path when the provider cannot`() =
        runTest {
            val viewModel = YlihViewModel(app)
            viewModel.chooseAutoBackupFolder(FakeDocumentsProvider.treeUri).join()

            assertEquals(FakeDocumentsProvider.ROOT_NAME, folderNameOnceIt(viewModel) { it != null })

            // Gone: the provider can't say, and a blank row would read as no folder at all.
            tmp.root.deleteRecursively()
            settings.setAutoBackupEvery(30)
            assertEquals(
                FakeDocumentsProvider.ROOT_ID,
                folderNameOnceIt(viewModel) { it != FakeDocumentsProvider.ROOT_NAME },
            )
        }

    // Real time, not the test's virtual clock: the name is read on Dispatchers.IO.
    private suspend fun folderNameOnceIt(viewModel: YlihViewModel, matches: (String?) -> Boolean) =
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                viewModel.autoBackup.first { it != null && matches(it.folderName) }!!.folderName
            }
        }
}
