package it.eldavo.ylih.export

import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import org.robolectric.Robolectric
import java.io.File
import java.io.FileNotFoundException

/**
 * A folder picked through the system picker, as far as ylih can tell: a `DocumentsProvider` over
 * a temporary directory, reached through the same `DocumentsContract` calls a real one is.
 *
 * Document ids are paths relative to [root], with [ROOT_ID] standing for the directory itself.
 */
class FakeDocumentsProvider : DocumentsProvider() {

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf())

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(COLUMNS).apply { addRow(row(documentId, file(documentId))) }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor = MatrixCursor(COLUMNS).apply {
        file(parentDocumentId).listFiles().orEmpty().forEach { addRow(row(idOf(it), it)) }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        failOpen?.let { throw it }
        return ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        failCreate?.let { throw it }
        val dir = file(parentDocumentId)
        // A real provider de-duplicates a clashing name the same way.
        var target = File(dir, displayName)
        var n = 1
        while (target.exists()) {
            target = File(dir, displayName.replace(".json", " (${n++}).json"))
        }
        target.createNewFile()
        return idOf(target)
    }

    override fun deleteDocument(documentId: String) {
        failDelete?.let { throw it }
        if (!file(documentId).delete()) throw FileNotFoundException(documentId)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        parentDocumentId == ROOT_ID || documentId.startsWith("$parentDocumentId/")

    private fun file(documentId: String): File {
        val f = if (documentId == ROOT_ID) root else File(root, documentId)
        if (!f.exists()) throw FileNotFoundException(documentId)
        return f
    }

    private fun idOf(file: File): String = file.relativeTo(root).path

    private fun row(id: String, file: File): Array<Any?> = arrayOf(
        id,
        if (id == ROOT_ID) ROOT_NAME else file.name,
        if (file.isDirectory) Document.MIME_TYPE_DIR else "application/json",
        Document.FLAG_SUPPORTS_DELETE or
            if (file.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE,
    )

    companion object {
        const val AUTHORITY = "it.eldavo.ylih.test.documents"
        const val ROOT_ID = "root"
        const val ROOT_NAME = "backups"

        private val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
        )

        lateinit var root: File

        /** Thrown from [createDocument] when set, as a provider does for a folder it lost. */
        var failCreate: Exception? = null

        /** Thrown from [openDocument] when set, standing in for a write the disk refused. */
        var failOpen: Exception? = null

        /** Thrown from [deleteDocument] when set. */
        var failDelete: Exception? = null

        val treeUri: Uri get() = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID)

        /** Registers the provider over [dir] with the attributes the platform demands of one. */
        fun install(dir: File) {
            root = dir
            failCreate = null
            failOpen = null
            failDelete = null
            Robolectric.buildContentProvider(FakeDocumentsProvider::class.java).create(
                ProviderInfo().apply {
                    authority = AUTHORITY
                    exported = true
                    grantUriPermissions = true
                    readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
                    writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
                },
            )
        }
    }
}
