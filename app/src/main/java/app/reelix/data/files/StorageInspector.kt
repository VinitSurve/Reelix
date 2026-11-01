package app.reelix.data.files

import android.content.Context
import app.reelix.data.db.ReelixDatabase
import app.reelix.integrations.transcription.TranscriptionModelManager
import java.io.File

data class StorageSummary(
    val databaseBytes: Long,
    val thumbnailBytes: Long,
    val mediaBytes: Long,
    val modelBytes: Long,
) {
    val totalBytes: Long get() = databaseBytes + thumbnailBytes + mediaBytes + modelBytes
}

interface StorageInspector {
    fun inspect(): StorageSummary
}

class AndroidStorageInspector(
    private val context: Context,
    private val thumbnails: ThumbnailStore,
    private val mediaStorage: MediaStorage,
    private val modelManager: TranscriptionModelManager,
) : StorageInspector {
    override fun inspect(): StorageSummary {
        val dbFile = context.getDatabasePath(ReelixDatabase.NAME)
        val dbBytes = listOf(
            dbFile,
            File(dbFile.path + "-wal"),
            File(dbFile.path + "-shm"),
        ).filter { it.exists() }.sumOf { it.length() }

        val modelFile = modelManager.getModelFile()
        val modelBytes = modelFile?.length() ?: 0L

        return StorageSummary(
            databaseBytes = dbBytes,
            thumbnailBytes = thumbnails.totalBytes(),
            mediaBytes = mediaStorage.totalBytes(),
            modelBytes = modelBytes,
        )
    }
}
