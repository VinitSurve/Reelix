package app.reelix

import android.content.ClipData
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.reelix.core.url.InstagramUrlParser
import app.reelix.core.url.ShareIntentReader
import app.reelix.domain.model.ContentType
import app.reelix.domain.model.ItemQuery
import app.reelix.domain.model.LinkSaveOutcome
import app.reelix.domain.model.ProcessingStatus
import app.reelix.domain.model.ShareSaveResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareIntentAndUrlTest {

    private lateinit var app: ReelixApplication
    private lateinit var testContainer: TestAppContainer

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        testContainer = TestAppContainer(app)
        app.container = testContainer
    }

    @After
    fun tearDown() {
        testContainer.close()
    }

    @Test
    fun parsesValidReelUrlAndNormalizesTrackingParams() {
        val raw = "https://www.instagram.com/reel/C9xYz_12-Ab/?igsh=MzRlODBiNWFlZA==&utm_source=ig_web"
        val link = InstagramUrlParser.parse(raw)
        assertNotNull(link)
        assertEquals(raw, link!!.originalUrl)
        assertEquals("https://www.instagram.com/reel/C9xYz_12-Ab/", link.normalizedUrl)
        assertEquals(ContentType.REEL, link.contentType)
        assertEquals("C9xYz_12-Ab", link.shortcode)

        // Username-prefixed and /reels/ alias normalize to the exact same canonical URL
        val alias = InstagramUrlParser.parse("https://instagram.com/kotlin_dev/reels/C9xYz_12-Ab")
        assertNotNull(alias)
        assertEquals(link.normalizedUrl, alias!!.normalizedUrl)
        assertEquals(ContentType.REEL, alias.contentType)
    }

    @Test
    fun parsesValidPostAndIgtvUrls() {
        val post = InstagramUrlParser.parse("https://m.instagram.com/p/DB1234abcd/")
        assertNotNull(post)
        assertEquals(ContentType.POST, post!!.contentType)
        assertEquals("https://www.instagram.com/p/DB1234abcd/", post.normalizedUrl)

        val igtv = InstagramUrlParser.parse("https://instagr.am/tv/TV9876zyxw/?hl=en")
        assertNotNull(igtv)
        assertEquals(ContentType.IGTV, igtv!!.contentType)
        assertEquals("https://www.instagram.com/tv/TV9876zyxw/", igtv.normalizedUrl)
    }

    @Test
    fun extractsUrlInsideLongerSharedText() {
        val sharedText = """
            Watch this brilliant explanation on Kotlin coroutines!
            (https://www.instagram.com/reel/CoRoUtInE99/?igsh=abc123).
            Shared from Instagram for Android.
        """.trimIndent()

        val links = InstagramUrlParser.extractAll(sharedText)
        assertEquals(1, links.size)
        assertEquals(ContentType.REEL, links.first().contentType)
        assertEquals("https://www.instagram.com/reel/CoRoUtInE99/", links.first().normalizedUrl)
    }

    @Test
    fun rejectsInvalidAndMaliciousUrls() {
        assertNull(InstagramUrlParser.parse(""))
        assertNull(InstagramUrlParser.parse("   "))
        assertNull(InstagramUrlParser.parse(null))
        assertNull(InstagramUrlParser.parse("https://example.com/reel/AbCdEf123"))
        assertNull(InstagramUrlParser.parse("javascript:alert(1)"))
        assertNull(InstagramUrlParser.parse("file:///data/data/app.reelix/databases/reelix.db"))
        assertNull(InstagramUrlParser.parse("https://evil-instagram.com/reel/AbCdEf123"))
        assertNull(InstagramUrlParser.parse("https://user:pass@instagram.com/reel/AbCdEf123"))
        assertNull(InstagramUrlParser.parse("https://www.instagram.com/accounts/login/"))
    }

    @Test
    fun handlesNullEmptyAndMalformedIntentsGracefully() = runTest {
        assertEquals(ShareSaveResult.NothingShared, testContainer.shareIntakeUseCase.handleIntent(null))

        val wrongAction = Intent(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_TEXT, "https://instagram.com/reel/AbCd1234/")
        assertEquals(ShareSaveResult.NothingShared, testContainer.shareIntakeUseCase.handleIntent(wrongAction))

        val emptySend = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "   ")
        }
        assertEquals(ShareSaveResult.NothingShared, testContainer.shareIntakeUseCase.handleIntent(emptySend))

        val nonIgSend = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Check out https://kotlinlang.org/docs/home.html")
        }
        assertEquals(ShareSaveResult.NoInstagramLink, testContainer.shareIntakeUseCase.handleIntent(nonIgSend))
        assertEquals(0, testContainer.savedItemRepository.observeCounts().first().total)
    }

    @Test
    fun preventsDuplicateShareUnlessExplicitCopyRequested() = runTest {
        val intent1 = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://www.instagram.com/reel/DuPlIcAtE1/?igsh=first")
        }
        val intent2 = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://instagram.com/reels/DuPlIcAtE1?utm_medium=copy_link")
        }

        val first = testContainer.shareIntakeUseCase.handleIntent(intent1) as ShareSaveResult.Handled
        assertEquals(1, first.saved.size)
        assertEquals(0, first.duplicates.size)
        assertEquals(1, testContainer.fakeScheduler.immediateCalls)

        val second = testContainer.shareIntakeUseCase.handleIntent(intent2) as ShareSaveResult.Handled
        assertEquals(0, second.saved.size)
        assertEquals(1, second.duplicates.size)
        assertEquals(first.saved.first().itemId, second.duplicates.first().itemId)
        assertEquals(1, testContainer.savedItemRepository.observeCounts().first().total)

        // Explicit user opt-in creates copy #2 without overwriting original
        val copyOutcome = testContainer.shareIntakeUseCase.saveAnotherCopy(second.duplicates.first().link)
        assertTrue(copyOutcome is LinkSaveOutcome.Saved)
        assertEquals(2, testContainer.savedItemRepository.observeCounts().first().total)
        val copyEntity = testContainer.savedItemRepository.getItem(copyOutcome.itemId)
        assertEquals(1, copyEntity?.copyIndex)
    }

    @Test
    fun savesMultipleIncomingUrlsInSingleAndMultiIntents() = runTest {
        val multiTextIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(
                Intent.EXTRA_TEXT,
                "First https://www.instagram.com/reel/MultiOne11/ and second https://www.instagram.com/p/MultiTwo22/",
            )
            clipData = ClipData.newPlainText("extra", "Third https://www.instagram.com/tv/MultiThr33/")
        }

        val texts = ShareIntentReader.readTexts(multiTextIntent)
        assertEquals(2, texts.size)

        val result = testContainer.shareIntakeUseCase.handleIntent(multiTextIntent) as ShareSaveResult.Handled
        assertEquals(3, result.saved.size)
        assertEquals(3, testContainer.savedItemRepository.observeCounts().first().total)

        val sendMultiple = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putStringArrayListExtra(
                Intent.EXTRA_TEXT,
                arrayListOf(
                    "https://www.instagram.com/reel/MultiOne11/", // already saved
                    "https://www.instagram.com/p/MultiFour44/",  // new
                ),
            )
        }
        val secondBatch = testContainer.shareIntakeUseCase.handleIntent(sendMultiple) as ShareSaveResult.Handled
        assertEquals(1, secondBatch.saved.size)
        assertEquals(1, secondBatch.duplicates.size)
        assertEquals(4, testContainer.savedItemRepository.observeCounts().first().total)
    }

    @Test
    fun coldStartShareReceiverActivityPersistsQueuedItemAndSchedulesWork() = runTest {
        val shareIntent = Intent(app, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Save this https://www.instagram.com/reel/ColdStart99/?igsh=test")
        }

        ActivityScenario.launch<ShareReceiverActivity>(shareIntent).use {
            val counts = testContainer.savedItemRepository.observeCounts().first()
            assertEquals(1, counts.total)
            assertEquals(1, counts.awaiting)

            val items = testContainer.savedItemRepository.observeItems(ItemQuery()).first()
            assertEquals(1, items.size)
            assertEquals(ProcessingStatus.QUEUED, items.first().processingStatus)
            assertEquals("https://www.instagram.com/reel/ColdStart99/?igsh=test", items.first().originalUrl)
            assertTrue(testContainer.fakeScheduler.immediateCalls >= 1)
        }
    }
}
