package com.gabrielpc.enginesoundsimulator.audio

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor

/**
 * Deliberately open local installer bridge. A write is staged and verified by
 * [AudioPackStore] before the car package becomes visible to the renderer.
 */
class AudioPackContentProvider : ContentProvider() {
    private lateinit var store: AudioPackStore

    override fun onCreate(): Boolean {
        store = AudioPackStore(requireNotNull(context).filesDir)
        return true
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "w" || mode == "wt") { "Audio packs are write-only through this provider" }
        val packId = requirePackId(uri)
        val (readSide, writeSide) = ParcelFileDescriptor.createPipe()
        Thread({
            ParcelFileDescriptor.AutoCloseInputStream(readSide).use { source ->
                runCatching { store.install(packId, source) }
            }
        }, "audio-pack-import-$packId").apply { isDaemon = true }.start()
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

    override fun getType(uri: Uri): String = "application/vnd.byd.audio-pack"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Use openFile")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Use openFile")

    private fun requirePackId(uri: Uri): String {
        require(uri.pathSegments.size == 2 && uri.pathSegments.first() == "packs") { "Invalid audio pack URI" }
        return uri.pathSegments.last()
    }
}
