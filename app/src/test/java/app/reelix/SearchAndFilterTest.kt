package app.reelix

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.reelix.core.url.InstagramUrlParser
import app.reelix.data.db.entity.ProcessingResultEntity
import app.reelix.domain.model.ContentType
import app.reelix.domain.model.ItemQuery
import app.reelix.domain.model.LinkSaveOutcome
import app.reelix.domain.model.ProcessingStage
import app.reelix.domain.model.SortOrder
import app.reelix.domain.model.StatusFilter
import app.reelix.domain.model.UserMetadata
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchAndFilterTest {

    private lateinit var context: Context
    private lateinit var container: TestAppContainer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        container = TestAppContainer(context)
    }

    @After
    fun tearDown() {
        container.close()
    }

    @Test
    fun offlineFtsMatchesTitleCaptionTranscriptSummaryTagsCategoryAndNotes() = runTest {
        // Item 1 (REEL, QUEUED -> COMPLETED): user title, notes & user tag
        val id1 = save("https://www.instagram.com/reel/SearchOne11/")
        container.clock.advanceBy(1_000L)
        container.savedItemRepository.updateUserMetadata(
            id1,
            UserMetadata(
                title = "Sourdough Fermentation Schedule",
                caption = null,
                transcript = null,
                notes = "Bulk ferment at 25C using autolyse method",
                category = "Baking",
                tags = listOf("breadmaking"),
            ),
        )
        container.processingRepository.setMediaAvailable(id1, true)
        container.processingRepository.markCompleted(id1)

        // Item 2 (POST, QUEUED): extracted caption & manual transcript
        val id2 = save("https://www.instagram.com/p/SearchTwo22/")
        container.clock.advanceBy(1_000L)
        container.processingRepository.applyExtractedMetadata(
            id = id2,
            title = "Espresso Dialing Guide",
            caption = "Adjust burr grinder finer when extraction runs under twenty seconds",
            authorName = "barista_craft",
            thumbnailUrl = null,
            thumbnailPath = null,
        )
        container.savedItemRepository.updateUserMetadata(
            id2,
            UserMetadata(
                title = null,
                caption = null,
                transcript = "Weigh eighteen grams into the portafilter basket",
                notes = null,
                category = null,
                tags = emptyList(),
            ),
        )

        // Item 3 (IGTV, NEEDS_ATTENTION): AI summary
        val id3 = save("https://www.instagram.com/tv/SearchThr33/")
        container.clock.advanceBy(1_000L)
        container.processingRepository.saveResult(
            ProcessingResultEntity(
                savedItemId = id3,
                stage = ProcessingStage.ANALYSIS,
                summary = "Deep explanation of PostgreSQL B-tree indexing internals.",
                mainTopic = "Database Indexes",
                keyTakeaways = null,
                keywords = "postgresql, btree",
                suggestedTags = null,
                category = "Databases",
                providerId = "test",
                modelName = "llama3",
                generatedAt = container.clock.now(),
            ),
        )
        val jobs3 = container.processingRepository.ensureJobs(id3)
        container.processingRepository.failStage(id3, jobs3.getValue(ProcessingStage.COMPLETION), 1, "Manual review")

        val defaultQuery = ItemQuery()

        // 1. Title match (prefix & full word)
        assertEquals(listOf(id1), searchIds("sourdough", defaultQuery))
        assertEquals(listOf(id2), searchIds("espresso", defaultQuery))

        // 2. Caption match
        assertEquals(listOf(id2), searchIds("burr grinder", defaultQuery))

        // 3. Transcript match
        assertEquals(listOf(id2), searchIds("portafilter", defaultQuery))

        // 4. AI Summary & keyword match
        assertEquals(listOf(id3), searchIds("postgresql", defaultQuery))

        // 5. Tag match
        assertEquals(listOf(id1), searchIds("breadmaking", defaultQuery))

        // 6. Category match
        assertEquals(listOf(id1), searchIds("baking", defaultQuery))

        // 7. User notes match
        assertEquals(listOf(id1), searchIds("autolyse", defaultQuery))

        // 8. Empty / non-matching / punctuation queries
        assertTrue(searchIds("nonexistentkeywordxyz", defaultQuery).isEmpty())
        assertTrue(searchIds("   ", defaultQuery).isEmpty())
        assertTrue(searchIds("!!! ???", defaultQuery).isEmpty())

        // 9. Filtering by status & content type and sorting Newest vs Oldest
        val allNewest = container.savedItemRepository.observeItems(ItemQuery(sortOrder = SortOrder.NEWEST)).first()
        assertEquals(listOf(id3, id2, id1), allNewest.map { it.id })

        val allOldest = container.savedItemRepository.observeItems(ItemQuery(sortOrder = SortOrder.OLDEST)).first()
        assertEquals(listOf(id1, id2, id3), allOldest.map { it.id })

        val mediaOnly = container.savedItemRepository.observeItems(
            ItemQuery(statusFilter = StatusFilter.MEDIA_AVAILABLE),
        ).first()
        assertEquals(listOf(id1), mediaOnly.map { it.id })

        val awaitingOnly = container.savedItemRepository.observeItems(
            ItemQuery(statusFilter = StatusFilter.AWAITING),
        ).first()
        assertEquals(listOf(id2), awaitingOnly.map { it.id })

        val attentionOnly = container.savedItemRepository.observeItems(
            ItemQuery(statusFilter = StatusFilter.ATTENTION),
        ).first()
        assertEquals(listOf(id3), attentionOnly.map { it.id })

        val postsOnly = container.savedItemRepository.observeItems(
            ItemQuery(contentTypes = setOf(ContentType.POST)),
        ).first()
        assertEquals(listOf(id2), postsOnly.map { it.id })
    }

    private suspend fun save(url: String): String {
        val link = InstagramUrlParser.parse(url)!!
        return (container.savedItemRepository.saveLink(link) as LinkSaveOutcome.Saved).itemId
    }

    private suspend fun searchIds(text: String, query: ItemQuery): List<String> =
        container.savedItemRepository.search(text, query).first().map { it.id }
}

