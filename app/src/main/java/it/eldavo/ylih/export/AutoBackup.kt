package it.eldavo.ylih.export

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import it.eldavo.ylih.YlihApp
import it.eldavo.ylih.data.AppContainer
import it.eldavo.ylih.data.AutoBackupError
import it.eldavo.ylih.data.SettingsStore
import it.eldavo.ylih.tracking.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Writes a JSON backup into a folder the user picked, on a schedule, so history survives a lost
 * phone without anyone remembering to export (#48).
 *
 * Periodic rather than on every change, the way Aegis backs up its vault: a connected pair writes
 * a heartbeat every minute, so a change-triggered backup would be a new file every minute.
 *
 * The folder is a `DocumentsContract` tree held through a persisted grant — no storage permission,
 * and any provider the picker offers, cloud ones included. Called directly rather than through
 * `androidx.documentfile`: four calls don't justify a dependency, and every dependency here costs
 * a hand-regenerated `verification-metadata.xml`.
 */
object AutoBackup {
    /** How many of its own files a folder keeps; older ones are deleted after each success. */
    const val KEPT = 10

    private const val MIME = "application/json"
    private const val TAG = "AutoBackup"

    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /**
     * Only these are ever pruned: the folder is the user's, and may hold anything else. The
     * suffix is how providers de-duplicate a clashing name.
     */
    private val ownFile = Regex("""^ylih-backup-\d{8}-\d{6}(?: \(\d+\))?\.json$""")

    /**
     * Timestamped, never one file overwritten: a write that dies halfway through must not take
     * the last good backup with it. Local time, since a person browsing the folder reads it.
     */
    fun fileName(now: Long, zone: ZoneId): String =
        "ylih-backup-${stamp.format(Instant.ofEpochMilli(now).atZone(zone))}.json"

    /**
     * The whole database as a backup file's contents. The manual export goes through here too, so
     * the two can never write different files.
     */
    suspend fun payload(container: AppContainer): ByteArray =
        container.repository.withWriteLock { JsonBackup.export(container.database, container.clock.now()) }
            .toByteArray()

    /**
     * One backup, if automatic backups are on: writes it, prunes, records the outcome, and tells
     * the user when backups have just started failing.
     *
     * @return the error, or null for a backup written or nothing to do.
     */
    suspend fun run(context: Context, zone: ZoneId = ZoneId.systemDefault()): AutoBackupError? =
        withContext(Dispatchers.IO) {
            val container = (context.applicationContext as YlihApp).container
            val state = container.settings.autoBackupNow()
            val tree = state.folder?.toUri() ?: return@withContext null
            val now = container.clock.now()
            val error = write(context.contentResolver, tree, fileName(now, zone)) { payload(container) }
            container.settings.recordAutoBackup(now, error)
            when {
                error == null -> Notifications.cancelBackupFailed(context)
                // Once, on the change: a folder that's gone stays gone, and a notification every
                // period would be nagging about something the first one already said.
                state.error == null -> Notifications.notifyBackupFailed(context, error)
            }
            error
        }

    private suspend fun write(
        resolver: ContentResolver,
        tree: Uri,
        name: String,
        payload: suspend () -> ByteArray,
    ): AutoBackupError? {
        // Checked first, since nothing else says so as plainly: a grant revoked from the system
        // settings, and a phone restored from Android's own backup, which brings back this
        // setting's row but never the grant it relied on.
        if (resolver.persistedUriPermissions.none { it.uri == tree && it.isWritePermission }) {
            return AutoBackupError.ACCESS_LOST
        }
        val bytes = payload()
        // Null, or thrown as FileNotFoundException, SecurityException or IllegalArgumentException
        // depending on the provider — every one of them a folder that's gone or no longer ours.
        val doc = orNull("create a backup in $tree") {
            DocumentsContract.createDocument(resolver, folderDocument(tree), MIME, name)
        } ?: return AutoBackupError.ACCESS_LOST
        try {
            resolver.openOutputStream(doc, "wt")?.use { it.write(bytes) }
                ?: throw FileNotFoundException("No stream for $doc")
        } catch (e: IOException) {
            Log.w(TAG, "Backup write failed", e)
            // A truncated file named like a backup is worse than none: it would be kept, and
            // pruning would count it as one of the good ones.
            delete(resolver, doc)
            return AutoBackupError.WRITE_FAILED
        }
        prune(resolver, tree)
        return null
    }

    /**
     * Deletes all but the newest [KEPT] of ylih's own files. Only after a success, so a run that
     * fails can never shrink the set of good backups.
     */
    private fun prune(resolver: ContentResolver, tree: Uri) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        // Failing to list is cosmetic: the backup is written, and the next success tries again.
        val own = orNull("list $tree") {
            query(resolver, children) { cursor ->
                val id = cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
                val name = cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
                buildList {
                    while (cursor.moveToNext()) {
                        val fileName = cursor.getString(name)
                        if (ownFile.matches(fileName)) add(fileName to cursor.getString(id))
                    }
                }
            }
        }.orEmpty()
        // The timestamp sorts as text, so newest first is a reverse sort by name.
        own.sortedByDescending { it.first }.drop(KEPT).forEach { (_, id) ->
            delete(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id))
        }
    }

    private fun delete(resolver: ContentResolver, doc: Uri) {
        orNull("delete $doc") { DocumentsContract.deleteDocument(resolver, doc) }
    }

    /**
     * The folder's name as its provider shows it, for the settings screen; null if it can't say,
     * which is also how a folder that's gone answers.
     */
    fun folderName(resolver: ContentResolver, tree: Uri): String? = orNull("name $tree") {
        query(resolver, folderDocument(tree)) { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME))
            } else {
                null
            }
        }
    }

    private fun folderDocument(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    // The Bundle overload, not the selection-string one: DocumentsProvider rejects the older form
    // outright.
    private fun <T> query(resolver: ContentResolver, uri: Uri, read: (Cursor) -> T): T? =
        resolver.query(
            uri,
            arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME),
            null as Bundle?,
            null,
        )?.use(read)

    /**
     * A provider call, logged and turned into null if it throws. A provider is another app reached
     * over binder, and what it throws for the same failure varies from one to the next. Nothing
     * inside suspends, so the broad catch can't swallow a cancellation.
     */
    private inline fun <T> orNull(what: String, call: () -> T): T? = try {
        call()
    } catch (e: Exception) {
        Log.w(TAG, "Could not $what", e)
        null
    }
}

class AutoBackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        when (AutoBackup.run(applicationContext)) {
            // Retrying can't bring back a folder or a grant; the user has been told, and the next
            // period tries again in case they fixed it without opening the app.
            null, AutoBackupError.ACCESS_LOST -> Result.success()
            AutoBackupError.WRITE_FAILED -> Result.retry()
        }
    } catch (e: Exception) {
        // See HeartbeatWorker: Room reports a failed transaction start as a cancellation, so a
        // cancellation here is a failure in disguise.
        currentCoroutineContext().ensureActive()
        Log.w(TAG, "Automatic backup failed", e)
        Result.retry()
    }

    companion object {
        const val NAME = "ylih-auto-backup"

        private const val TAG = "AutoBackupWorker"
    }
}

/**
 * Keeps the periodic backup in step with the settings: scheduled at the chosen interval while a
 * folder is set, cancelled otherwise.
 *
 * [policy] is [ExistingPeriodicWorkPolicy.KEEP] for the routine caller, `syncWithSystem`, which
 * re-arms the work on a phone restored from Android's own backup — WorkManager's database may not
 * have come back with the settings, and without this they would say backups are on while nothing
 * ran. [ExistingPeriodicWorkPolicy.UPDATE] moves the interval without restarting it;
 * [ExistingPeriodicWorkPolicy.REPLACE] starts a new schedule, whose first run is immediate — what
 * a newly picked folder wants.
 */
suspend fun scheduleAutoBackup(
    context: Context,
    settings: SettingsStore,
    policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP,
) {
    val state = settings.autoBackupNow()
    val workManager = WorkManager.getInstance(context)
    if (state.folder == null) {
        workManager.cancelUniqueWork(AutoBackupWorker.NAME)
        return
    }
    workManager.enqueueUniquePeriodicWork(
        AutoBackupWorker.NAME,
        policy,
        PeriodicWorkRequestBuilder<AutoBackupWorker>(state.everyDays.toLong(), TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .build(),
    )
}
