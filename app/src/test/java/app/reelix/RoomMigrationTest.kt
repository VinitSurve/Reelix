package app.reelix

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import app.reelix.data.db.ReelixDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomMigrationTest {

    private lateinit var context: Context
    private val dbName = "migration_test_${System.nanoTime()}.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migration1To2AddsNewColumnsAndRecreatesFtsWithoutDataLoss() {
        val helperConfig = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Create Schema v1 tables
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `saved_items` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `originalUrl` TEXT NOT NULL,
                            `normalizedUrl` TEXT NOT NULL,
                            `shortcode` TEXT,
                            `contentType` TEXT NOT NULL,
                            `copyIndex` INTEGER NOT NULL,
                            `source` TEXT NOT NULL,
                            `userTitle` TEXT,
                            `userCaption` TEXT,
                            `userTranscript` TEXT,
                            `userNotes` TEXT,
                            `userCategory` TEXT,
                            `extractedTitle` TEXT,
                            `caption` TEXT,
                            `authorName` TEXT,
                            `thumbnailUrl` TEXT,
                            `thumbnailPath` TEXT,
                            `processingStatus` TEXT NOT NULL,
                            `extractionStatus` TEXT NOT NULL,
                            `transcriptionStatus` TEXT NOT NULL,
                            `analysisStatus` TEXT NOT NULL,
                            `mediaAvailable` INTEGER NOT NULL,
                            `nextAttemptAt` INTEGER,
                            `retryCount` INTEGER NOT NULL,
                            `lastError` TEXT,
                            `createdAt` INTEGER NOT NULL,
                            `updatedAt` INTEGER NOT NULL,
                            `lastProcessedAt` INTEGER
                        )
                        """.trimIndent(),
                    )

                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `processing_results` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `savedItemId` TEXT NOT NULL,
                            `stage` TEXT NOT NULL,
                            `summary` TEXT,
                            `transcript` TEXT,
                            `keywords` TEXT,
                            `suggestedTags` TEXT,
                            `category` TEXT,
                            `mainTopic` TEXT,
                            `keyTakeaways` TEXT,
                            `providerId` TEXT,
                            `modelName` TEXT,
                            `generatedAt` INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )

                    db.execSQL(
                        """
                        CREATE VIRTUAL TABLE IF NOT EXISTS `saved_item_fts` USING FTS4(
                            `item_id`, `title`, `caption`, `transcript`, `summary`, `tags`, `category`, `notes`, `keywords`,
                            notindexed=`item_id`, tokenize=unicode61
                        )
                        """.trimIndent(),
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(helperConfig)
        val v1Db = helper.writableDatabase

        // Insert a test item in Schema v1
        v1Db.execSQL(
            """
            INSERT INTO `saved_items` (
                id, originalUrl, normalizedUrl, contentType, copyIndex, source,
                processingStatus, extractionStatus, transcriptionStatus, analysisStatus,
                mediaAvailable, retryCount, createdAt, updatedAt
            ) VALUES (
                'v1_item_1', 'https://www.instagram.com/reel/V1Test123/', 'https://www.instagram.com/reel/V1Test123/',
                'REEL', 0, 'SHARE', 'QUEUED', 'PENDING', 'PENDING', 'PENDING', 0, 0, 1000, 1000
            )
            """.trimIndent(),
        )

        // Execute MIGRATION_1_2
        ReelixDatabase.MIGRATION_1_2.migrate(v1Db)

        // Verify newly added columns exist in saved_items
        val cursor = v1Db.query("SELECT id, mediaPath, mediaMimeType, mediaSizeBytes, mediaDurationMs, mediaFileName, audioPath, audioSizeBytes, audioExtractionStatus FROM `saved_items` WHERE id = 'v1_item_1'")
        assertTrue(cursor.moveToFirst())
        assertEquals("v1_item_1", cursor.getString(0))
        assertEquals("PENDING", cursor.getString(8)) // audioExtractionStatus default
        cursor.close()

        // Verify newly added columns exist in processing_results
        val resCursor = v1Db.query("PRAGMA table_info(`processing_results`)")
        val prColumns = mutableListOf<String>()
        while (resCursor.moveToNext()) {
            prColumns.add(resCursor.getString(1))
        }
        resCursor.close()
        assertTrue(prColumns.contains("detailedSummary"))
        assertTrue(prColumns.contains("importantConcepts"))
        assertTrue(prColumns.contains("actionableSteps"))

        // Verify saved_item_fts exists and can insert/query the new columns
        v1Db.execSQL(
            """
            INSERT INTO `saved_item_fts` (
                item_id, title, caption, transcript, summary, detailedSummary, tags, category, notes, keywords, concepts, actionableSteps
            ) VALUES (
                'v1_item_1', 'Compose State', 'Hoisting state', 'Transcript here', 'Summary here', 'Detailed summary here', 'android', 'Baking', 'Notes', 'kw', 'Concepts here', 'Steps here'
            )
            """.trimIndent(),
        )

        val ftsCursor = v1Db.query("SELECT item_id FROM `saved_item_fts` WHERE saved_item_fts MATCH 'Detailed'")
        assertTrue(ftsCursor.moveToFirst())
        assertEquals("v1_item_1", ftsCursor.getString(0))
        ftsCursor.close()

        // Execute MIGRATION_2_3
        ReelixDatabase.MIGRATION_2_3.migrate(v1Db)

        // Verify newly added 3D projection sync columns in saved_items
        val cursorV3 = v1Db.query("SELECT id, lastSyncedGeneration, lastSyncedAttemptEpoch, lastSyncedProjectionVersion FROM `saved_items` WHERE id = 'v1_item_1'")
        assertTrue(cursorV3.moveToFirst())
        assertEquals("v1_item_1", cursorV3.getString(0))
        assertEquals(1, cursorV3.getInt(1)) // lastSyncedGeneration default 1
        assertEquals(1, cursorV3.getInt(2)) // lastSyncedAttemptEpoch default 1
        assertEquals(0L, cursorV3.getLong(3)) // lastSyncedProjectionVersion default 0
        cursorV3.close()

        val tableInfoCursor = v1Db.query("PRAGMA table_info(`saved_items`)")
        val columns = mutableListOf<String>()
        while (tableInfoCursor.moveToNext()) {
            columns.add(tableInfoCursor.getString(1))
        }
        tableInfoCursor.close()
        assertTrue(columns.contains("lastSyncedGeneration"))
        assertTrue(columns.contains("lastSyncedAttemptEpoch"))
        assertTrue(columns.contains("lastSyncedProjectionVersion"))

        v1Db.close()
    }
}
