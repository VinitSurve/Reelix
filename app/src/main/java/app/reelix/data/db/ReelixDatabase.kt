package app.reelix.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.reelix.data.db.dao.ProcessingDao
import app.reelix.data.db.dao.SavedItemDao
import app.reelix.data.db.dao.SearchDao
import app.reelix.data.db.dao.TagDao
import app.reelix.data.db.entity.ProcessingEventEntity
import app.reelix.data.db.entity.ProcessingJobEntity
import app.reelix.data.db.entity.ProcessingResultEntity
import app.reelix.data.db.entity.SavedItemEntity
import app.reelix.data.db.entity.SavedItemFtsEntity
import app.reelix.data.db.entity.SavedItemTagEntity
import app.reelix.data.db.entity.TagEntity

@Database(
    entities = [
        SavedItemEntity::class,
        TagEntity::class,
        SavedItemTagEntity::class,
        ProcessingJobEntity::class,
        ProcessingResultEntity::class,
        ProcessingEventEntity::class,
        SavedItemFtsEntity::class,
    ],
    version = ReelixDatabase.VERSION,
    exportSchema = true,
)
abstract class ReelixDatabase : RoomDatabase() {
    abstract fun savedItemDao(): SavedItemDao
    abstract fun tagDao(): TagDao
    abstract fun processingDao(): ProcessingDao
    abstract fun searchDao(): SearchDao

    companion object {
        const val VERSION = 3
        const val NAME = "reelix.db"

        /** Explicit migration from schema v1 to v2: adds media & extended AI fields, recreates FTS. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `saved_items` ADD COLUMN `mediaPath` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `saved_items` ADD COLUMN `mediaMimeType` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `saved_items` ADD COLUMN `mediaSizeBytes` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `saved_items` ADD COLUMN `mediaDurationMs` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `saved_items` ADD COLUMN `mediaFileName` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `saved_items` ADD COLUMN `audioPath` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `saved_items` ADD COLUMN `audioSizeBytes` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `saved_items` ADD COLUMN `audioExtractionStatus` TEXT NOT NULL DEFAULT 'PENDING'")

                db.execSQL("ALTER TABLE `processing_results` ADD COLUMN `detailedSummary` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `processing_results` ADD COLUMN `importantConcepts` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `processing_results` ADD COLUMN `actionableSteps` TEXT DEFAULT NULL")

                db.execSQL("DROP TABLE IF EXISTS `saved_item_fts`")
                db.execSQL(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS `saved_item_fts` USING FTS4(
                        `item_id`, `title`, `caption`, `transcript`, `summary`, `detailedSummary`,
                        `tags`, `category`, `notes`, `keywords`, `concepts`, `actionableSteps`,
                        notindexed=`item_id`, tokenize=unicode61
                    )
                    """.trimIndent(),
                )
            }
        }

        /** Migration v2 to v3: adds projection consistency tracking dimensions to prevent stale rollback. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `saved_items` ADD COLUMN `lastSyncedGeneration` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `saved_items` ADD COLUMN `lastSyncedAttemptEpoch` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `saved_items` ADD COLUMN `lastSyncedProjectionVersion` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

        fun build(context: Context): ReelixDatabase =
            Room.databaseBuilder(context.applicationContext, ReelixDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()

        fun inMemory(context: Context): ReelixDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, ReelixDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
