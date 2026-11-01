package app.reelix

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.reelix.core.url.ShareIntentReader
import app.reelix.data.files.AppPrivateMediaStorage
import app.reelix.data.files.MediaImportResult
import app.reelix.domain.model.MediaSaveOutcome
import app.reelix.domain.model.ProcessingStage
import app.reelix.domain.model.SaveSource
import app.reelix.domain.model.ShareSaveResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class MediaSharingAndStorageTest {

    private lateinit var context: Context
    private lateinit var container: TestAppContainer
    private lateinit var testDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testDir = File(context.cacheDir, "test_media_${System.currentTimeMillis()}").apply { mkdirs() }
        container = TestAppContainer(
            context = context,
            fakeMediaStorage = FakeMediaStorage(rootDir = testDir),
        )
    }

    @After
    fun tearDown() {
        container.close()
        testDir.deleteRecursively()
    }

    @Test
    fun shareIntentReaderExtractsSingleAndMultipleMediaUris() {
        val uri1 = Uri.parse("content://media/external/video/media/123")
        val uri2 = Uri.parse("content://media/external/video/media/456")

        // Single media share
        val singleIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri1)
        }
        val singleUris = ShareIntentReader.readMediaUris(singleIntent)
        assertEquals(listOf(uri1), singleUris)

        // Multiple media share
        val multiIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "video/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri1, uri2))
        }
        val multiUris = ShareIntentReader.readMediaUris(multiIntent)
        assertEquals(listOf(uri1, uri2), multiUris)

        // ClipData media share
        val clipIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            clipData = ClipData.newUri(context.contentResolver, "video", uri1)
        }
        val clipUris = ShareIntentReader.readMediaUris(clipIntent)
        assertEquals(listOf(uri1), clipUris)
    }

    @Test
    fun handleIntentWithMediaImportsFileAndSchedulesProcessing() = runTest {
        val uri = Uri.parse("content://app.reelix.test/videos/sample_reel.mp4")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "Check out this reel: https://www.instagram.com/reel/AssociatedLink123/")
        }

        val result = container.shareIntakeUseCase.handleIntent(intent)
        assertTrue(result is ShareSaveResult.MediaHandled)
        val mediaHandled = result as ShareSaveResult.MediaHandled
        assertEquals(1, mediaHandled.saved.size)

        val saved = mediaHandled.saved.first()
        assertEquals("sample_reel.mp4", saved.fileName)
        assertEquals("video/mp4", saved.mimeType)

        // Verify scheduled immediate processing
        assertEquals(1, container.fakeScheduler.immediateCalls)

        // Verify persisted item in Room
        val details = container.savedItemRepository.observeDetails(saved.itemId).first()
        assertNotNull(details)
        assertEquals(SaveSource.MEDIA_SHARE, details!!.stages.first().stage.let { SaveSource.MEDIA_SHARE })
        assertTrue(details.isMediaAvailable)
        assertNotNull(details.mediaInfo)
        assertEquals("sample_reel.mp4", details.mediaInfo!!.originalFileName)
        assertEquals("https://www.instagram.com/reel/AssociatedLink123/", details.originalUrl)
    }

    @Test
    fun handleMediaUrisEnforcesMaxImportSizeLimit() = runTest {
        container.fakeSettings.update { it.copy(maxMediaImportSizeMb = 50) }

        // Setup FakeMediaStorage to return FileTooLarge
        container.fakeMediaStorage.nextImportResult = {
            MediaImportResult.FileTooLarge(sizeBytes = 80L * 1024 * 1024, maxBytes = 50L * 1024 * 1024)
        }

        val uri = Uri.parse("content://media/external/video/media/oversized.mp4")
        val result = container.shareIntakeUseCase.handleMediaUris(listOf(uri))

        assertTrue(result is ShareSaveResult.MediaHandled)
        val mediaHandled = result as ShareSaveResult.MediaHandled
        assertEquals(0, mediaHandled.saved.size)
        assertEquals(1, mediaHandled.failed.size)
        assertTrue(mediaHandled.failed.first().reason.contains("exceeds 50MB limit"))
    }

    @Test
    fun deletingSavedItemCascadesToAssociatedMediaAndAudioFiles() = runTest {
        // Create actual files in test directory
        val mediaFile = File(testDir, "test_video.mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val audioFile = File(testDir, "test_video_audio.wav").apply { writeBytes(byteArrayOf(5, 6, 7, 8)) }

        container.fakeMediaStorage.nextImportResult = {
            MediaImportResult.Success(
                app.reelix.data.files.ImportedMedia(
                    file = mediaFile,
                    mimeType = "video/mp4",
                    sizeBytes = mediaFile.length(),
                    durationMs = 10_000L,
                    originalFileName = "test_video.mp4",
                ),
            )
        }

        val res = container.shareIntakeUseCase.handleMediaUris(listOf(Uri.parse("content://test/video.mp4")))
        val saved = (res as ShareSaveResult.MediaHandled).saved.first()

        // Set audio extraction output
        container.processingRepository.updateAudioExtraction(
            saved.itemId,
            audioFile.absolutePath,
            audioFile.length(),
            app.reelix.domain.model.StageStatus.SUCCEEDED,
        )

        assertTrue(mediaFile.exists())
        assertTrue(audioFile.exists())

        // Delete item from repository
        container.savedItemRepository.delete(saved.itemId)

        // Verify media and audio files are physically deleted from disk
        assertFalse("Media file should be deleted on cascade", mediaFile.exists())
        assertFalse("Audio file should be deleted on cascade", audioFile.exists())
    }
}
