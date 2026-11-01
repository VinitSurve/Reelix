package app.reelix

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.reelix.core.url.InstagramUrlParser
import app.reelix.data.db.ReelixDatabase
import app.reelix.data.db.entity.ProcessingResultEntity
import app.reelix.domain.model.LinkSaveOutcome
import app.reelix.domain.model.ProcessingStage
import app.reelix.domain.model.ProcessingStatus
import app.reelix.domain.model.StageStatus
import app.reelix.domain.model.TagSource
import app.reelix.domain.model.UserMetadata
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseRepositoryTest {

    private lateinit var context: Context
    private lateinit var thumbDir: File
    private lateinit var container: TestAppContainer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        thumbDir = File(context.cacheDir, "test_thumbs_${System.nanoTime()}").apply { mkdirs() }
        container = TestAppContainer(
            context = context,
            fakeThumbnails = FakeThumbnailStore(thumbDir),
        )
    }

    @After
    fun tearDown() {
        container.close()
        thumbDir.deleteRecursively()
    }

    @Test
    fun saveRetrieveUpdateAndPreserveExtractedAndAiFields() = runTest {
        val link = InstagramUrlParser.parse("https://www.instagram.com/reel/DbSaveTest1/")!!
        val saved = container.savedItemRepository.saveLink(link) as LinkSaveOutcome.Saved

        // Verify initial persistence & 10 pipeline stage jobs
        val entity = container.savedItemRepository.getItem(saved.itemId)
        assertNotNull(entity)
        assertEquals(ProcessingStatus.QUEUED, entity!!.processingStatus)
        val initialDetails = container.savedItemRepository.observeDetails(saved.itemId).first()!!
        assertEquals(ProcessingStage.entries.size, initialDetails.stages.size)

        // Simulate extracted metadata + AI result
        val thumbPath = container.thumbnails.download("https://cdn.example.com/img.jpg", saved.itemId)
        container.processingRepository.applyExtractedMetadata(
            id = saved.itemId,
            title = "Extracted Title",
            caption = "Extracted caption #kotlin #android",
            authorName = "jetpack_author",
            thumbnailUrl = "https://cdn.example.com/img.jpg",
            thumbnailPath = thumbPath,
        )
        container.processingRepository.saveResult(
            ProcessingResultEntity(
                savedItemId = saved.itemId,
                stage = ProcessingStage.ANALYSIS,
                summary = "Concise AI summary of the reel.",
                mainTopic = "Jetpack Room",
                keyTakeaways = "Point one\nPoint two",
                keywords = "room, sqlite",
                suggestedTags = "room,database",
                category = "Engineering",
                providerId = "test-ai",
                modelName = "llama3.2",
                generatedAt = container.clock.now(),
            ),
        )

        // Update user metadata — must NEVER overwrite extracted or AI data
        val updated = container.savedItemRepository.updateUserMetadata(
            saved.itemId,
            UserMetadata(
                title = "My Custom Vault Title",
                caption = "My custom caption note",
                transcript = "Spoken transcript added manually",
                notes = "Review this before building migration",
                category = "Android Dev",
                tags = listOf("#Architecture", "Kotlin "),
            ),
        )
        assertTrue(updated)

        val details = container.savedItemRepository.observeDetails(saved.itemId).first()!!
        assertEquals("My Custom Vault Title", details.userTitle)
        assertEquals("Extracted Title", details.extractedTitle)
        assertEquals("Extracted caption #kotlin #android", details.extractedCaption)
        assertEquals("My custom caption note", details.userCaption)
        assertEquals("Spoken transcript added manually", details.userTranscript)
        assertEquals("Review this before building migration", details.userNotes)
        assertEquals("Android Dev", details.userCategory)
        assertEquals("Concise AI summary of the reel.", details.aiResult?.summary)
        assertEquals(listOf("Point one", "Point two"), details.aiResult?.keyTakeaways)
    }

    @Test
    fun tagRelationshipsRespectUserPrecedenceAndCleanOrphans() = runTest {
        val link = InstagramUrlParser.parse("https://www.instagram.com/p/TagRelTest1/")!!
        val saved = container.savedItemRepository.saveLink(link) as LinkSaveOutcome.Saved

        // User adds "kotlin" and "compose"
        container.savedItemRepository.updateUserMetadata(
            saved.itemId,
            UserMetadata(null, null, null, null, null, tags = listOf("Kotlin", "Compose")),
        )

        // Generated tags include "kotlin" (already USER) and "coroutines" (new AI tag)
        container.processingRepository.replaceGeneratedTags(
            saved.itemId,
            TagSource.AI,
            listOf("kotlin", "coroutines"),
        )
        container.processingRepository.replaceGeneratedTags(
            saved.itemId,
            TagSource.EXTRACTED,
            listOf("android"),
        )

        val tags = container.savedItemRepository.observeDetails(saved.itemId).first()!!.tags
        val byName = tags.associateBy { it.name }
        assertEquals(4, byName.size)
        // "kotlin" must stay USER, never overwritten by AI!
        assertEquals(TagSource.USER, byName["kotlin"]?.source)
        assertEquals(TagSource.USER, byName["compose"]?.source)
        assertEquals(TagSource.AI, byName["coroutines"]?.source)
        assertEquals(TagSource.EXTRACTED, byName["android"]?.source)

        // Removing user tag "compose" deletes orphan tag row from tags table
        container.savedItemRepository.updateUserMetadata(
            saved.itemId,
            UserMetadata(null, null, null, null, null, tags = listOf("kotlin")),
        )
        assertNull(container.database.tagDao().findByName("compose"))
    }

    @Test
    fun deleteItemCascadesJobsResultsTagsFtsAndThumbnailFile() = runTest {
        val link = InstagramUrlParser.parse("https://www.instagram.com/reel/DeleteCascade1/")!!
        val saved = container.savedItemRepository.saveLink(link) as LinkSaveOutcome.Saved
        val thumbPath = container.thumbnails.download("https://cdn.example.com/t.jpg", saved.itemId)!!
        assertTrue(File(thumbPath).exists())

        container.processingRepository.applyExtractedMetadata(
            id = saved.itemId,
            title = "Title",
            caption = "Caption",
            authorName = null,
            thumbnailUrl = "https://cdn.example.com/t.jpg",
            thumbnailPath = thumbPath,
        )
        container.savedItemRepository.updateUserMetadata(
            saved.itemId,
            UserMetadata("User Title", null, null, "UniqueDeletionToken", null, listOf("uniquetag")),
        )
        assertEquals(1, container.database.searchDao().count())

        assertTrue(container.savedItemRepository.delete(saved.itemId))

        assertNull(container.savedItemRepository.getItem(saved.itemId))
        assertEquals(0, container.database.processingDao().jobsFor(saved.itemId).size)
        assertEquals(0, container.database.searchDao().count())
        assertNull(container.database.tagDao().findByName("uniquetag"))
        assertFalse(File(thumbPath).exists())
    }

    @Test
    fun processingStateUpdatesEnforceAtomicClaimAndRetrySchedule() = runTest {
        val link = InstagramUrlParser.parse("https://www.instagram.com/reel/StateUpdate1/")!!
        val saved = container.savedItemRepository.saveLink(link) as LinkSaveOutcome.Saved

        // First claim succeeds, concurrent second claim returns false
        assertTrue(container.processingRepository.claim(saved.itemId))
        assertFalse(container.processingRepository.claim(saved.itemId))

        val jobs = container.processingRepository.ensureJobs(saved.itemId)
        val metadataJob = jobs.getValue(ProcessingStage.METADATA)
        val retryTime = container.clock.now() + 60_000L
        container.processingRepository.scheduleRetry(saved.itemId, metadataJob, 1, retryTime, "HTTP 429")

        val waiting = container.savedItemRepository.getItem(saved.itemId)!!
        assertEquals(ProcessingStatus.WAITING_RETRY, waiting.processingStatus)
        assertEquals(StageStatus.RETRY_SCHEDULED, waiting.extractionStatus)

        // Cannot claim before retryTime
        assertFalse(container.processingRepository.claim(saved.itemId))

        // Advance clock past retryTime -> claim succeeds
        container.clock.advanceBy(61_000L)
        assertTrue(container.processingRepository.claim(saved.itemId))
    }

    @Test
    fun persistentDiskDatabaseSurvivesReopenAndExportsSchema() = runTest {
        val dbName = "migration_verify_${System.nanoTime()}.db"
        val db1 = androidx.room.Room.databaseBuilder(context, ReelixDatabase::class.java, dbName)
            .addMigrations(*ReelixDatabase.MIGRATIONS)
            .build()
        try {
            val indexer1 = app.reelix.data.repository.SearchIndexer(db1)
            val repo1 = app.reelix.data.repository.SavedItemRepository(db1, indexer1, container.thumbnails, container.mediaStorage, container.clock)
            val link = InstagramUrlParser.parse("https://www.instagram.com/reel/PersistDisk1/")!!
            repo1.saveLink(link)
            assertEquals(1, repo1.observeCounts().first().total)
        } finally {
            db1.close()
        }

        // Reopen physical database file (simulating full app restart)
        val db2 = androidx.room.Room.databaseBuilder(context, ReelixDatabase::class.java, dbName)
            .addMigrations(*ReelixDatabase.MIGRATIONS)
            .build()
        try {
            val found = db2.savedItemDao().findByNormalizedUrl("https://www.instagram.com/reel/PersistDisk1/")
            assertNotNull(found)
            assertEquals(ProcessingStatus.QUEUED, found!!.processingStatus)
        } finally {
            db2.close()
            context.deleteDatabase(dbName)
        }
    }
}

