package it.eldavo.ylih.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import it.eldavo.ylih.Distribution
import it.eldavo.ylih.R
import it.eldavo.ylih.YlihApp
import it.eldavo.ylih.data.DeviceEntity
import it.eldavo.ylih.data.DeviceKind
import it.eldavo.ylih.data.PairEntity
import it.eldavo.ylih.data.SessionEntity
import it.eldavo.ylih.export.JsonBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
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
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * The view model is thin apart from backup and restore, which is where the app either keeps or
 * loses everything it has recorded — including what it tells the user when that goes wrong.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class YlihViewModelTest {

    private val app: YlihApp = ApplicationProvider.getApplicationContext()
    private val db get() = app.container.database
    private val resolver get() = shadowOf(app.contentResolver)

    private val uri: Uri = "content://test/backup.json".toUri()
    private val hour = 3_600_000L
    private val now = app.container.clock.now()

    private lateinit var viewModel: YlihViewModel

    @Before
    fun setUp() {
        // syncWithSystem() reaches WorkManager, whose androidx.startup initializer does not run
        // under Robolectric. This has to happen before Dispatchers.setMain: the helper's own
        // initialisation goes through the main dispatcher, and on a test dispatcher it never
        // completes, leaving WorkManager looking uninitialised at the first getInstance().
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        // viewModelScope dispatches on Dispatchers.Main, which under Robolectric is a paused
        // looper the test thread itself owns — nothing would ever run.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        runBlocking { db.deviceDao().deleteAll() }
        viewModel = YlihViewModel(app)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun seed(): Long = runBlocking {
        val deviceId = db.deviceDao().insert(
            DeviceEntity(
                deviceKey = "bt:5E:C2",
                kind = DeviceKind.BLUETOOTH,
                defaultName = "ACCENTUM Plus",
                firstSeenAt = now - 100 * hour,
            ),
        )
        val pairId = db.pairDao().insert(
            PairEntity(
                deviceId = deviceId,
                label = "ACCENTUM Plus",
                generation = 1,
                startedAt = now - 100 * hour,
            ),
        )
        db.sessionDao().insert(
            SessionEntity(
                pairId = pairId,
                connectedAt = now - 3 * hour,
                disconnectedAt = now - hour,
                playingMs = hour,
                heartbeatAt = now - hour,
            ),
        )
        pairId
    }

    @Test
    fun `exporting writes a restorable backup and says so`() = runTest {
        seed()
        val written = ByteArrayOutputStream()
        resolver.registerOutputStream(uri, written)

        viewModel.exportTo(uri).join()

        assertEquals(app.getString(R.string.export_done), viewModel.messages.first())
        // The proof that it is a backup and not just bytes: it imports.
        assertEquals(1, JsonBackup.import(db, written.toString(Charsets.UTF_8)))
    }

    @Test
    fun `an export that cannot be written reports why instead of claiming success`() = runTest {
        seed()
        resolver.registerOutputStream(
            uri,
            object : OutputStream() {
                override fun write(b: Int) = throw IOException("no space left on device")
                override fun write(b: ByteArray) = throw IOException("no space left on device")
            },
        )

        viewModel.exportTo(uri).join()

        assertEquals("no space left on device", viewModel.messages.first())
    }

    @Test
    fun `importing replaces the database and counts the pairs restored`() = runTest {
        seed()
        val payload = JsonBackup.export(db, now)
        db.deviceDao().deleteAll()
        resolver.registerInputStream(uri, payload.byteInputStream())

        viewModel.importFrom(uri).join()

        assertEquals(
            app.resources.getQuantityString(R.plurals.import_done, 1, 1),
            viewModel.messages.first(),
        )
        assertEquals("ACCENTUM Plus", db.pairDao().getAll().single().label)
    }

    @Test
    fun `an import that is not a backup leaves the history alone`() = runTest {
        seed()
        resolver.registerInputStream(uri, "not json at all".byteInputStream())

        viewModel.importFrom(uri).join()

        assertTrue(viewModel.messages.first().isNotEmpty())
        assertEquals("the existing history survives", 1, db.pairDao().getAll().size)
    }

    @Test
    fun `a document the picker no longer opens is named in the failure`() = runTest {
        // A provider that has lost the file answers null rather than throwing, and both halves of
        // backup have to notice: a restore that quietly did nothing reads exactly like a restore
        // of an empty backup.
        seed()
        registerGoneProvider()
        resolver.registerOutputStreamSupplier(uri) { null }

        viewModel.exportTo(uri).join()
        assertEquals(app.getString(R.string.error_could_not_open, uri), viewModel.messages.first())

        viewModel.importFrom(uri).join()
        assertEquals(app.getString(R.string.error_could_not_open, uri), viewModel.messages.first())
        assertEquals("the existing history survives", 1, db.pairDao().getAll().size)
    }

    /**
     * Registered rather than stubbed through [ShadowContentResolver.registerInputStreamSupplier]:
     * a supplier that yields null falls back to the shadow's own unregistered stream, which throws
     * on the first read instead of handing back the null the real resolver would.
     */
    private fun registerGoneProvider() {
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? = null
            override fun getType(uri: Uri): String? = null
            override fun query(
                uri: Uri,
                projection: Array<out String>?,
                selection: String?,
                selectionArgs: Array<out String>?,
                sortOrder: String?,
            ): Cursor? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun update(
                uri: Uri,
                values: ContentValues?,
                selection: String?,
                selectionArgs: Array<out String>?,
            ) = 0
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        val authority = checkNotNull(uri.authority)
        provider.attachInfo(app, ProviderInfo().apply { this.authority = authority; exported = true })
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }

    @Test
    fun `the observed lists reflect what is in the database`() = runTest {
        val pairId = seed()

        assertEquals(listOf("ACCENTUM Plus"), viewModel.summaries.first { it.isNotEmpty() }
            .map { it.label })
        assertEquals(listOf("ACCENTUM Plus"), viewModel.devices.first { it.isNotEmpty() }
            .map { it.defaultName })
        assertEquals(1, viewModel.allSpans.first { it.isNotEmpty() }.size)
        assertEquals(
            setOf(pairId),
            viewModel.spansByPair.first { it.isNotEmpty() }.keys,
        )
        assertEquals(1, viewModel.sessions(pairId).first().size)
        assertNotNull(viewModel.summary(pairId).first())
        assertTrue("the live clock has a value from the first frame", viewModel.now.value > 0)
    }

    @Test
    fun `the editing calls all reach the database`() = runTest {
        val pairId = seed()
        val deviceId = db.deviceDao().getAll().single().id
        val sessionId = db.sessionDao().getAll().single().id

        viewModel.renamePair(pairId, "WH-1000XM5").join()
        assertEquals("WH-1000XM5", db.pairDao().byId(pairId)!!.label)

        viewModel.setPurchaseInfo(pairId, now - 200 * hour, 14_900).join()
        assertEquals(14_900L, db.pairDao().byId(pairId)!!.priceCents)

        viewModel.setDeviceIgnored(deviceId, ignored = true).join()
        assertTrue(db.deviceDao().byId(deviceId)!!.ignored)

        viewModel.deleteSession(sessionId).join()
        assertTrue(db.sessionDao().getAll().isEmpty())

        viewModel.retirePair(pairId, "sold").join()
        assertEquals("sold", db.pairDao().byId(pairId)!!.retireReason)

        viewModel.deletePair(pairId).join()
        assertNull(db.pairDao().byId(pairId))
    }

    @Test
    fun `an edit lands even with no home screen to redraw`() = runTest {
        val pairId = seed()

        // Every foreground edit is followed by a Glance updateAll, and nothing here is hosting a
        // widget for it to update. That the rename still arrives is the assertion: a cosmetic
        // redraw must never be able to fail a write the user asked for.
        viewModel.renamePair(pairId, "Sennheiser HD 25").join()

        assertEquals("Sennheiser HD 25", db.pairDao().byId(pairId)?.label)
    }

    @Test
    fun `finishing onboarding is remembered`() = runTest {
        app.container.settings.setOnboardingDone(false)

        viewModel.completeOnboarding().join()

        assertTrue(app.container.settings.onboardingDoneNow())
    }

    @Test
    fun `syncing from the UI repairs the database`() = runTest {
        seed()
        // The seeded session is closed, so a sync has nothing to reopen and must not invent one.
        viewModel.syncWithSystem().join()

        assertEquals(1, db.sessionDao().getAll().size)
    }

    @Test
    fun `a build that cannot run detailed tracking explains itself rather than failing quietly`() =
        runTest {
            shadowOf(app).denyPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
            app.container.settings.setDetailedTracking(false)

            viewModel.setDetailedTracking(true).join()

            if (Distribution.HAS_SPECIAL_USE_FGS) {
                assertTrue(viewModel.detailedTracking.first { it })
            } else {
                assertEquals(
                    app.getString(R.string.detailed_needs_bluetooth),
                    viewModel.messages.first(),
                )
            }
            assertEquals(
                Distribution.HAS_SPECIAL_USE_FGS,
                viewModel.detailedTrackingSupported.value,
            )
            app.container.settings.setDetailedTracking(false)
        }
}
