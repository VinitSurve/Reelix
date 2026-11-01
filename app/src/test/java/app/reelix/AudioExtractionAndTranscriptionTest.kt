package app.reelix

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.reelix.data.files.ImportedMedia
import app.reelix.data.media.AudioExtractionResult
import app.reelix.domain.model.MediaSaveOutcome
import app.reelix.domain.model.ProcessingStage
import app.reelix.domain.model.StageStatus
import app.reelix.integrations.transcription.ModelImportOutcome
import app.reelix.integrations.transcription.ModelStatus
import app.reelix.integrations.transcription.TranscriptionOutcome
import app.reelix.integrations.transcription.TranscriptionProvider
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
class AudioExtractionAndTranscriptionTest {

    private lateinit var context: Context
    private lateinit var container: TestAppContainer
    private lateinit var testDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testDir = File(context.cacheDir, "test_audio_${System.currentTimeMillis()}").apply { mkdirs() }
        container = TestAppContainer(context)
    }

    @After
    fun tearDown() {
        container.close()
        testDir.deleteRecursively()
    }

    @Test
    fun audioExtractionSavesWavFileAndMarksStageSucceeded() = runTest {
        val videoFile = File(testDir, "test_input.mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val fakeExtractor = FakeAudioExtractor()
        val customContainer = TestAppContainer(
            context = context,
            fakeAudioExtractor = fakeExtractor,
        )

        try {
            val outcome = customContainer.savedItemRepository.saveMediaItem(
                ImportedMedia(
                    file = videoFile,
                    mimeType = "video/mp4",
                    sizeBytes = videoFile.length(),
                    durationMs = 20_000L,
                    originalFileName = "test_input.mp4",
                ),
            ) as MediaSaveOutcome.Saved

            val batch = customContainer.pipeline.runBatch(limit = 10)
            assertEquals(1, batch.completed)

            val details = customContainer.savedItemRepository.observeDetails(outcome.itemId).first()!!
            assertEquals(StageStatus.SUCCEEDED, details.audioExtractionStatus)
            assertNotNull(details.mediaInfo?.audioPath)
            assertTrue(details.mediaInfo!!.audioFileExists)
        } finally {
            customContainer.close()
        }
    }

    @Test
    fun audioExtractionHandlesNoAudioTrackGracefully() = runTest {
        val videoFile = File(testDir, "silent_video.mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val fakeExtractor = FakeAudioExtractor().apply {
            nextResult = { _, _ -> AudioExtractionResult.NoAudioTrack("No audio stream in MP4") }
        }
        val customContainer = TestAppContainer(
            context = context,
            fakeAudioExtractor = fakeExtractor,
        )

        try {
            val outcome = customContainer.savedItemRepository.saveMediaItem(
                ImportedMedia(
                    file = videoFile,
                    mimeType = "video/mp4",
                    sizeBytes = videoFile.length(),
                    durationMs = 5_000L,
                    originalFileName = "silent_video.mp4",
                ),
            ) as MediaSaveOutcome.Saved

            customContainer.pipeline.runBatch(limit = 10)

            val details = customContainer.savedItemRepository.observeDetails(outcome.itemId).first()!!
            assertEquals(StageStatus.SKIPPED, details.audioExtractionStatus)
        } finally {
            customContainer.close()
        }
    }

    @Test
    fun transcriptionReportsNotConfiguredHonestlyWhenNoModelIsInstalled() = runTest {
        val videoFile = File(testDir, "speech_clip.mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

        val outcome = container.savedItemRepository.saveMediaItem(
            ImportedMedia(
                file = videoFile,
                mimeType = "video/mp4",
                sizeBytes = videoFile.length(),
                durationMs = 15_000L,
                originalFileName = "speech_clip.mp4",
            ),
        ) as MediaSaveOutcome.Saved

        container.pipeline.runBatch(limit = 10)

        val details = container.savedItemRepository.observeDetails(outcome.itemId).first()!!
        // Offline transcription model is not configured -> honest NOT_CONFIGURED status, no fake transcript!
        assertEquals(StageStatus.NOT_CONFIGURED, details.transcriptionStatus)
        assertFalse(details.isTranscribed)
        assertEquals(null, details.generatedTranscript)
    }

    @Test
    fun transcriptionPersistsTranscriptWhenModelSucceeds() = runTest {
        val videoFile = File(testDir, "interview.mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val mockTranscriptionProvider = object : TranscriptionProvider {
            override val id = "whisper-local"
            override val displayName = "Whisper Offline"
            override val isAvailable = true
            override suspend fun transcribe(audioFile: File, onProgress: ((Float) -> Unit)?): TranscriptionOutcome =
                TranscriptionOutcome.Success(
                    transcript = "Welcome to the podcast. Today we discuss Jetpack Compose performance optimizations.",
                    modelName = "ggml-tiny.bin",
                )
        }

        val customContainer = TestAppContainer(
            context = context,
            transcriptionProvider = mockTranscriptionProvider,
        )

        try {
            val outcome = customContainer.savedItemRepository.saveMediaItem(
                ImportedMedia(
                    file = videoFile,
                    mimeType = "video/mp4",
                    sizeBytes = videoFile.length(),
                    durationMs = 12_000L,
                    originalFileName = "interview.mp4",
                ),
            ) as MediaSaveOutcome.Saved

            customContainer.pipeline.runBatch(limit = 10)

            val details = customContainer.savedItemRepository.observeDetails(outcome.itemId).first()!!
            assertEquals(StageStatus.SUCCEEDED, details.transcriptionStatus)
            assertTrue(details.isTranscribed)
            assertEquals(
                "Welcome to the podcast. Today we discuss Jetpack Compose performance optimizations.",
                details.generatedTranscript,
            )

            // Verify transcript was stored persistently in ProcessingResultEntity
            val results = customContainer.processingRepository.results(outcome.itemId)
            val transResult = results.firstOrNull { it.stage == ProcessingStage.TRANSCRIPTION }
            assertNotNull(transResult)
            assertEquals("ggml-tiny.bin", transResult!!.modelName)
        } finally {
            customContainer.close()
        }
    }

    @Test
    fun transcriptionModelManagerLifecycleHandlesImportStatusAndDelete() = runTest {
        val modelManager = FakeTranscriptionModelManager()
        assertEquals(ModelStatus.NotInstalled, modelManager.currentStatus())

        // Import model
        val uri = Uri.parse("content://saf/model/ggml-base.bin")
        modelManager.nextImportOutcome = ModelImportOutcome.Success(
            ModelStatus.Ready(
                fileName = "ggml-base.bin",
                sizeBytes = 142_000_000L,
                modelFormat = "GGML",
                localPath = "/fake/models/ggml-base.bin",
            ),
        )

        val outcome = modelManager.importModel(uri, context.contentResolver)
        assertTrue(outcome is ModelImportOutcome.Success)
        assertTrue(modelManager.currentStatus() is ModelStatus.Ready)
        val ready = modelManager.currentStatus() as ModelStatus.Ready
        assertEquals("ggml-base.bin", ready.fileName)
        assertEquals(142_000_000L, ready.sizeBytes)

        // Delete model
        val deleted = modelManager.deleteModel()
        assertTrue(deleted)
        assertEquals(ModelStatus.NotInstalled, modelManager.currentStatus())
    }
}
