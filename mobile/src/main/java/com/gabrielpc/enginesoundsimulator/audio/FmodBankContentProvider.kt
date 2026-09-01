package com.gabrielpc.enginesoundsimulator.audio

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor

/** Open local bridge used by the companion installer to publish verified FMOD banks. */
class FmodBankContentProvider : ContentProvider() {
    private lateinit var store: FmodBankStore

    override fun onCreate(): Boolean {
        store = FmodBankStore(requireNotNull(context).filesDir)
        return true
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "w" || mode == "wt") { "FMOD banks are write-only through this provider" }
        val packId = requirePackId(uri)
        val (readSide, writeSide) = ParcelFileDescriptor.createPipe()
        Thread({
            ParcelFileDescriptor.AutoCloseInputStream(readSide).use { source ->
                runCatching { store.install(packId, source) }
            }
        }, "fmod-bank-import-$packId").apply { isDaemon = true }.start()
        return writeSide
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        require(uri.pathSegments == listOf("packs")) { "Query the /packs inventory only" }
        return MatrixCursor(arrayOf("id", "installed")).apply {
            store.installedPackIds().forEach { id -> addRow(arrayOf<Any>(id, 1)) }
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        require(uri.pathSegments == listOf("packs")) { "Delete the /packs inventory only" }
        store.deleteAll()
        return 1
    }

    override fun getType(uri: Uri): String = "application/vnd.byd.fmod-bank"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Use openFile")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Use openFile")

    private fun requirePackId(uri: Uri): String {
        require(uri.pathSegments.size == 2 && uri.pathSegments.first() == "packs") { "Invalid FMOD bank URI" }
        return uri.pathSegments.last()
    }
}
