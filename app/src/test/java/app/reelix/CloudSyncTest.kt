package app.reelix

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.reelix.cloud.CloudAnalysisResult
import app.reelix.cloud.CloudJobManifest
import app.reelix.cloud.CloudJobResults
import app.reelix.cloud.CloudMediaInfo
import app.reelix.cloud.CloudStageStates
import app.reelix.data.files.ImportedMedia
import app.reelix.domain.model.ItemQuery
import app.reelix.domain.model.MediaSaveOutcome
import app.reelix.domain.model.ProcessingStage
import app.reelix.domain.model.ProcessingStatus
import app.reelix.domain.model.SaveSource
import app.reelix.domain.model.StageStatus
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
class CloudSyncTest {

    private lateinit var context: Context
    private lateinit var container: TestAppContainer
    private lateinit var testMediaDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testMediaDir = File(context.cacheDir, "cloud_sync_tests_${System.currentTimeMillis()}").apply { mkdirs() }
        container = TestAppContainer(context)
    }

    @After
    fun tearDown() {
        container.close()
        testMediaDir.deleteRecursively()
    }

    @Test
    fun cloudJobManifestSerializesAndDeserializesRoundTrip() {
        val original = CloudJobManifest(
            version = 1,
            itemId = "item_cloud_test_123",
            source = "MEDIA_SHARE",
            originalUrl = "https://www.instagram.com/reel/C8xyz123/",
            status = "COMPLETED",
            claimedBy = "github-runner-1",
            claimedAt = 1730000000000L,
            createdAt = 1729990000000L,
            updatedAt = 1730000500000L,
            retryCount = 0,
            maxRetries = 3,
            errorMessage = null,
            media = CloudMediaInfo(
                fileName = "reel_video.mp4",
                mimeType = "video/mp4",
                driveFileId = "drive_file_vid_999",
                sizeBytes = 15_000_000L,
                durationMs = 45_000L,
            ),
            stages = CloudStageStates(
                audioExtraction = "SUCCEEDED",
                transcription = "SUCCEEDED",
                geminiAnalysis = "SUCCEEDED",
            ),
            results = CloudJobResults(
                audioDriveFileId = "drive_audio_777",
                audioSizeBytes = 1_200_000L,
                transcript = "This is a transcribed tutorial on Kotlin coroutines and structured concurrency.",
                transcriptModel = "base",
                analysis = CloudAnalysisResult(
                    summary = "Overview of coroutines",
                    detailedSummary = "Deep dive into structured concurrency, coroutine scopes, and cancellation.",
                    mainTopic = "Kotlin Coroutines",
                    category = "Software Engineering",
                    keywords = listOf("coroutines", "kotlin", "async"),
                    tags = listOf("android", "kotlin", "concurrency"),
                    keyTakeaways = listOf("Use Dispatchers.IO for disk operations", "Always respect cancellation"),
                    importantConcepts = listOf("Structured Concurrency", "CoroutineScope"),
                    actionableSteps = listOf("Wrap calls in viewModelScope.launch", "Avoid GlobalScope"),
                    modelName = "gemini-3.6-flash",
                    generatedAt = 1730000500000L,
                ),
            ),
        )

        val json = original.toJson()
        val parsed = CloudJobManifest.fromJson(json)

        assertEquals(original.version, parsed.version)
        assertEquals(original.itemId, parsed.itemId)
        assertEquals(original.source, parsed.source)
        assertEquals(original.originalUrl, parsed.originalUrl)
        assertEquals(original.status, parsed.status)
        assertEquals(original.claimedBy, parsed.claimedBy)
        assertEquals(original.claimedAt, parsed.claimedAt)
        assertEquals(original.createdAt, parsed.createdAt)
        assertEquals(original.updatedAt, parsed.updatedAt)
        assertEquals(original.retryCount, parsed.retryCount)
        assertEquals(original.maxRetries, parsed.maxRetries)

        assertNotNull(parsed.media)
        assertEquals(original.media!!.fileName, parsed.media!!.fileName)
        assertEquals(original.media!!.mimeType, parsed.media!!.mimeType)
        assertEquals(original.media!!.driveFileId, parsed.media!!.driveFileId)
        assertEquals(original.media!!.sizeBytes, parsed.media!!.sizeBytes)
        assertEquals(original.media!!.durationMs, parsed.media!!.durationMs)

        assertEquals("SUCCEEDED", parsed.stages.audioExtraction)
        assertEquals("SUCCEEDED", parsed.stages.transcription)
        assertEquals("SUCCEEDED", parsed.stages.geminiAnalysis)

        assertNotNull(parsed.results.analysis)
        assertEquals("Overview of coroutines", parsed.results.analysis!!.summary)
        assertEquals("Kotlin Coroutines", parsed.results.analysis!!.mainTopic)
        assertEquals(listOf("android", "kotlin", "concurrency"), parsed.results.analysis!!.tags)
        assertEquals(listOf("Structured Concurrency", "CoroutineScope"), parsed.results.analysis!!.importantConcepts)
        assertEquals("gemini-3.6-flash", parsed.results.analysis!!.modelName)
    }

    @Test
    fun uploadPendingItemOffloadsMediaAndRegistersManifestInCloudQueue() = runTest {
        val testVideo = File(testMediaDir, "sample_clip.mp4").apply {
            writeBytes("test video data content".toByteArray())
        }
        val media = ImportedMedia(
            file = testVideo,
            mimeType = "video/mp4",
            sizeBytes = testVideo.length(),
            durationMs = 15_000L,
            originalFileName = "sample_clip.mp4",
        )
        val outcome = container.savedItemRepository.saveMediaItem(
            media = media,
            associatedUrl = "https://www.instagram.com/reel/C_test123/",
            source = SaveSource.MEDIA_SHARE,
        )
        assertTrue(outcome is MediaSaveOutcome.Saved)
        val itemId = (outcome as MediaSaveOutcome.Saved).itemId

        // Enable cloud in settings
        container.settingsRepository.update {
            it.copy(
                cloudProcessingEnabled = true,
                cloudDriveFolderId = "folder_root_123",
            )
        }
        container.settingsRepository.setCloudDriveCredentials(
            accessToken = "token_abc",
        )

        val uploaded = container.cloudSyncRepository.uploadPendingItem(itemId)
        assertTrue("Pending item should be successfully uploaded to mock cloud", uploaded)

        // Verify manifest registered in mock client
        val manifest = container.fakeCloudClient.jobs[itemId]
        assertNotNull("Manifest must exist in cloud client", manifest)
        assertEquals("QUEUED", manifest!!.status)
        assertEquals("PENDING", manifest.stages.audioExtraction)
        assertEquals("PENDING", manifest.stages.transcription)
        assertEquals("PENDING", manifest.stages.geminiAnalysis)
        assertNotNull("Media info must be populated in cloud manifest", manifest.media)
        assertEquals("sample_clip.mp4", manifest.media!!.fileName)
        assertTrue(container.fakeCloudClient.uploadedFiles.isNotEmpty())

        // Verify local DB status updated to PROCESSING
        val item = container.savedItemRepository.getItem(itemId)
        assertNotNull(item)
        assertEquals(ProcessingStatus.PROCESSING, item!!.processingStatus)
    }

    @Test
    fun syncCompletedJobsIntegratesTranscriptAnalysisTagsAndSearchIndex() = runTest {
        // 1. Create local item
        val testVideo = File(testMediaDir, "tutorial.mp4").apply {
            writeBytes("tutorial binary data".toByteArray())
        }
        val media = ImportedMedia(
            file = testVideo,
            mimeType = "video/mp4",
            sizeBytes = testVideo.length(),
            durationMs = 30_000L,
            originalFileName = "tutorial.mp4",
        )
        val outcome = container.savedItemRepository.saveMediaItem(
            media = media,
            associatedUrl = "https://www.instagram.com/p/C_tutorial/",
            source = SaveSource.MEDIA_SHARE,
        )
        val itemId = (outcome as MediaSaveOutcome.Saved).itemId

        // 2. Mock cloud processor completing the job
        val completedManifest = CloudJobManifest(
            itemId = itemId,
            source = "MEDIA_SHARE",
            originalUrl = "https://www.instagram.com/p/C_tutorial/",
            status = "COMPLETED",
            createdAt = container.clock.now() - 100_000L,
            updatedAt = container.clock.now(),
            stages = CloudStageStates(
                audioExtraction = "SUCCEEDED",
                transcription = "SUCCEEDED",
                geminiAnalysis = "SUCCEEDED",
            ),
            results = CloudJobResults(
                audioDriveFileId = "audio_cloud_id_999",
                audioSizeBytes = 800_000L,
                transcript = "Step one: configure room database with fts4 virtual tables for fast offline search.",
                transcriptModel = "base",
                analysis = CloudAnalysisResult(
                    summary = "Configuring Room with FTS4 full-text search",
                    detailedSummary = "Detailed guide on creating virtual tables in Room and indexing documents.",
                    mainTopic = "Room Database FTS4",
                    category = "Android Development",
                    keywords = listOf("room", "fts4", "sqlite", "full-text search"),
                    tags = listOf("database", "android", "fts"),
                    keyTakeaways = listOf("Use FTS4 for fast local search", "Index transcripts and summaries"),
                    importantConcepts = listOf("Virtual Table", "Match Query"),
                    actionableSteps = listOf("Create entity extending Fts4", "Call reindex after inserts"),
                    modelName = "gemini-3.6-flash",
                    generatedAt = container.clock.now(),
                ),
            ),
        )
        container.fakeCloudClient.jobs[itemId] = completedManifest

        // 3. Sync completed jobs
        val syncedCount = container.cloudSyncRepository.syncCompletedJobs()
        assertEquals("Should sync 1 completed job", 1, syncedCount)

        // 4. Verify item updated to COMPLETED in Room
        val updatedItem = container.savedItemRepository.getItem(itemId)
        assertNotNull(updatedItem)
        assertEquals(ProcessingStatus.COMPLETED, updatedItem!!.processingStatus)
        assertEquals(StageStatus.SUCCEEDED, updatedItem.audioExtractionStatus)
        assertEquals(StageStatus.SUCCEEDED, updatedItem.transcriptionStatus)
        assertEquals(StageStatus.SUCCEEDED, updatedItem.analysisStatus)

        // 5. Verify results stored
        val results = container.savedItemRepository.latestResults(itemId)
        val transcriptRes = results.firstOrNull { it.stage == ProcessingStage.TRANSCRIPTION }
        assertNotNull("Transcript result should be stored", transcriptRes)
        assertTrue(transcriptRes!!.transcript!!.contains("fts4 virtual tables"))

        val analysisRes = results.firstOrNull { it.stage == ProcessingStage.ANALYSIS }
        assertNotNull("Analysis result should be stored", analysisRes)
        assertEquals("Configuring Room with FTS4 full-text search", analysisRes!!.summary)
        assertEquals("Room Database FTS4", analysisRes.mainTopic)
        assertEquals("Android Development", analysisRes.category)
        assertTrue(analysisRes.keyTakeaways!!.contains("Use FTS4"))

        // 6. Verify tags normalized and applied
        val details = container.savedItemRepository.observeDetails(itemId).first()
        val tagNames = details?.tags?.map { it.name } ?: emptyList()
        assertTrue("Should contain tag 'database'", tagNames.contains("database"))
        assertTrue("Should contain tag 'android'", tagNames.contains("android"))
        assertTrue("Should contain tag 'fts'", tagNames.contains("fts"))

        // 7. Verify searchable in Room FTS4 index
        val searchResults = container.savedItemRepository.search("virtual tables", ItemQuery()).first()
        assertEquals("Search query should find item by transcribed content", 1, searchResults.size)
        assertEquals(itemId, searchResults.first().id)

        val tagSearch = container.savedItemRepository.search("sqlite", ItemQuery()).first()
        assertEquals("Search query should find item by keyword/topic", 1, tagSearch.size)
    }

    @Test
    fun cloudSyncSkipsGracefullyWhenClientIsDisabledOrUnconfigured() = runTest {
        container.fakeCloudClient.isEnabled = false

        val uploadSuccess = container.cloudSyncRepository.uploadPendingItem("non_existent_item")
        assertFalse("Upload should return false when client is disabled", uploadSuccess)

        val synced = container.cloudSyncRepository.syncCompletedJobs()
        assertEquals("Sync should return 0 when client is disabled", 0, synced)
    }

    @Test
    fun resumableUploadRecoverySavesSessionAndResumesAfterInterruption() = runTest {
        val testFile = File(testMediaDir, "resumable_test.mp4").apply {
            writeBytes(ByteArray(2048) { (it % 256).toByte() })
        }
        val client = container.fakeCloudClient
        client.simulateInterruptionOnce = true
        val itemId = "test-item-resumable-1"

        // First attempt: interrupted
        val firstResult = client.uploadMedia(testFile, "video/mp4", testFile.name, itemId)
        assertTrue("First attempt should fail with simulated network failure", firstResult is app.reelix.cloud.CloudUploadResult.Failure)

        val uploadKey = app.reelix.cloud.buildResumableUploadKey(testFile, itemId)
        val savedSession = client.resumableStore.getSessionUri(uploadKey)
        assertNotNull("Resumable session URI should be preserved after interruption", savedSession)

        // Second attempt: resumes from preserved session URI
        val secondResult = client.uploadMedia(testFile, "video/mp4", testFile.name, itemId)
        assertTrue("Second attempt should succeed by recovering session", secondResult is app.reelix.cloud.CloudUploadResult.Success)
        assertEquals("Should have resumed from byte offset", testFile.length() / 2, client.lastUploadedOffset)
        assertEquals("Session URI should be cleaned up on completion", null, client.resumableStore.getSessionUri(uploadKey))
    }

    @Test
    fun partialProcessingPreservesTranscriptAndIndexesInFts4() = runTest {
        val testFile = File(testMediaDir, "partial_video.mp4").apply { writeBytes("video bytes".toByteArray()) }
        val outcome = container.savedItemRepository.saveMediaItem(
            media = ImportedMedia(testFile, "video/mp4", testFile.length(), 10000L, testFile.name),
            associatedUrl = "https://www.instagram.com/p/partial_test/",
            source = SaveSource.MEDIA_SHARE,
        )
        val itemId = (outcome as MediaSaveOutcome.Saved).itemId

        // Cloud worker produced transcript, but Gemini failed after retries -> manifest is PARTIAL
        val partialManifest = CloudJobManifest(
            itemId = itemId,
            source = "MEDIA_SHARE",
            status = "PARTIAL",
            errorMessage = "Gemini enrichment failed: Rate limit exceeded",
            createdAt = container.clock.now() - 50_000L,
            updatedAt = container.clock.now(),
            stages = CloudStageStates(
                audioExtraction = "SUCCEEDED",
                transcription = "SUCCEEDED",
                geminiAnalysis = "FAILED",
            ),
            results = CloudJobResults(
                transcript = "This offline transcript discusses algorithmic complexity and big-O notation.",
                transcriptModel = "base",
                analysis = null,
            ),
        )
        container.fakeCloudClient.jobs[itemId] = partialManifest

        val synced = container.cloudSyncRepository.syncCompletedJobs()
        assertEquals(1, synced)

        val item = container.savedItemRepository.getItem(itemId)
        assertNotNull(item)
        // Honest state: not falsely marked COMPLETED
        assertEquals(ProcessingStatus.NEEDS_ATTENTION, item!!.processingStatus)
        assertEquals(StageStatus.SUCCEEDED, item.transcriptionStatus)
        assertEquals(StageStatus.FAILED, item.analysisStatus)
        assertTrue(item.lastError!!.contains("Rate limit"))

        // Transcript is preserved in Room
        val results = container.savedItemRepository.latestResults(itemId)
        val transcriptRes = results.firstOrNull { it.stage == ProcessingStage.TRANSCRIPTION }
        assertNotNull(transcriptRes)
        assertTrue(transcriptRes!!.transcript!!.contains("algorithmic complexity"))

        // Partial transcript is indexed and searchable offline in FTS4!
        val search = container.savedItemRepository.search("complexity", ItemQuery()).first()
        assertEquals(1, search.size)
        assertEquals(itemId, search.first().id)
    }

    @Test
    fun cloudSyncRejectsEmptyAnalysisAsIncomplete() = runTest {
        val testFile = File(testMediaDir, "incomplete_video.mp4").apply { writeBytes("video bytes".toByteArray()) }
        val outcome = container.savedItemRepository.saveMediaItem(
            media = ImportedMedia(testFile, "video/mp4", testFile.length(), 10000L, testFile.name),
            associatedUrl = "https://www.instagram.com/p/incomplete_test/",
            source = SaveSource.MEDIA_SHARE,
        )
        val itemId = (outcome as MediaSaveOutcome.Saved).itemId

        // Cloud manifest marked COMPLETED but analysis is missing/empty
        val emptyAnalysisManifest = CloudJobManifest(
            itemId = itemId,
            source = "MEDIA_SHARE",
            status = "COMPLETED",
            createdAt = container.clock.now() - 50_000L,
            updatedAt = container.clock.now(),
            stages = CloudStageStates(
                audioExtraction = "SUCCEEDED",
                transcription = "SUCCEEDED",
                geminiAnalysis = "SUCCEEDED",
            ),
            results = CloudJobResults(
                transcript = "Some audio text",
                transcriptModel = "base",
                analysis = null, // Missing analysis!
            ),
        )
        container.fakeCloudClient.jobs[itemId] = emptyAnalysisManifest

        val synced = container.cloudSyncRepository.syncCompletedJobs()
        assertEquals(1, synced)

        val item = container.savedItemRepository.getItem(itemId)
        assertNotNull(item)
        // Must NOT be marked COMPLETED when analysis is missing
        assertEquals(ProcessingStatus.NEEDS_ATTENTION, item!!.processingStatus)
        assertTrue(item.lastError!!.contains("Missing or empty analysis"))
    }

    @Test
    fun sharedPreferencesResumableUploadStoreSurvivesProcessRecreation() {
        val testFile = File(testMediaDir, "persistent_video.mp4").apply {
            writeBytes("test file content for persistence".toByteArray())
        }
        val key = app.reelix.cloud.buildResumableUploadKey(testFile, "item-persist-123")
        val dummySessionUri = "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&upload_id=abc123session"

        // 1. Initial process instance writes session URI to persistent store
        val store1 = app.reelix.cloud.SharedPreferencesResumableUploadStore(context)
        store1.saveSessionUri(key, dummySessionUri)
        assertEquals(dummySessionUri, store1.getSessionUri(key))

        // 2. Simulate process termination and recreation: instantiate fresh store with application context
        val store2 = app.reelix.cloud.SharedPreferencesResumableUploadStore(context)
        val recoveredUri = store2.getSessionUri(key)
        assertEquals("Session URI must survive process recreation in SharedPreferences", dummySessionUri, recoveredUri)

        // 3. Removal cleans up session
        store2.removeSession(key)
        val store3 = app.reelix.cloud.SharedPreferencesResumableUploadStore(context)
        assertEquals("Session URI must be null after removal", null, store3.getSessionUri(key))
    }

    @Test
    fun stableUploadKeyGenerationDifferentiatesDifferentFilesWithSameName() {
        val dir1 = File(testMediaDir, "dir1").apply { mkdirs() }
        val dir2 = File(testMediaDir, "dir2").apply { mkdirs() }
        val f1 = File(dir1, "video.mp4").apply { writeBytes("video 1".toByteArray()) }
        val f2 = File(dir2, "video.mp4").apply { writeBytes("longer video 2 payload".toByteArray()) }

        val key1 = app.reelix.cloud.buildResumableUploadKey(f1, "item-same")
        val key2 = app.reelix.cloud.buildResumableUploadKey(f2, "item-same")
        assertFalse("Different file paths/lengths must produce distinct keys even with identical filenames", key1 == key2)
    }

    @Test
    fun differentItemsWithIdenticalFileProduceDistinctUploadKeys() {
        val testFile = File(testMediaDir, "shared_source.mp4").apply {
            writeBytes("shared video payload".toByteArray())
        }
        val keyItemA = app.reelix.cloud.buildResumableUploadKey(testFile, "item-alpha")
        val keyItemB = app.reelix.cloud.buildResumableUploadKey(testFile, "item-beta")

        assertFalse(
            "Two different Reelix items sharing the same physical file must have distinct upload keys",
            keyItemA == keyItemB,
        )
    }

    @Test
    fun retriesForSameItemAndUnchangedFileReuseSameSession() {
        val testFile = File(testMediaDir, "retry_source.mp4").apply {
            writeBytes("payload for retry test".toByteArray())
        }
        val keyAttempt1 = app.reelix.cloud.buildResumableUploadKey(testFile, "item-retry-1")
        val keyAttempt2 = app.reelix.cloud.buildResumableUploadKey(testFile, "item-retry-1")

        assertEquals(
            "Retries for the same item and unchanged file must produce identical keys to resume session",
            keyAttempt1,
            keyAttempt2,
        )
    }

    @Test
    fun changedFileForSameItemProducesDistinctUploadKey() {
        val testFile = File(testMediaDir, "modifiable.mp4").apply {
            writeBytes("initial bytes".toByteArray())
        }
        val key1 = app.reelix.cloud.buildResumableUploadKey(testFile, "item-mod-1")

        // Modify file length and modification time
        testFile.appendText(" appended more bytes")
        val key2 = app.reelix.cloud.buildResumableUploadKey(testFile, "item-mod-1")

        assertFalse(
            "A modified file for the same item must produce a distinct key to prevent resuming old wrong session",
            key1 == key2,
        )
    }

    @Test
    fun legacySessionsInvalidatedOnStoreMigration() {
        // Populate a legacy store with v1 data (no schema version set)
        val legacyPrefs = context.applicationContext.getSharedPreferences(
            app.reelix.cloud.SharedPreferencesResumableUploadStore.PREFS_NAME,
            android.content.Context.MODE_PRIVATE,
        )
        legacyPrefs.edit()
            .putString("legacy_key_without_item_id", "https://drive.google.com/upload/legacy_session")
            .remove(app.reelix.cloud.SharedPreferencesResumableUploadStore.KEY_SCHEMA_VERSION)
            .commit()

        // Initializing the store should detect schema_version < 2 and clear legacy ambiguous sessions
        val migratedStore = app.reelix.cloud.SharedPreferencesResumableUploadStore(context)
        val legacyVal = migratedStore.getSessionUri("legacy_key_without_item_id")
        assertNull("Legacy session keys without item ID isolation must be invalidated on upgrade", legacyVal)
    }

    @Test
    fun failedJobWithPartialResultsPreservesTranscriptAndIndexesInFts4() = runTest {
        val testFile = File(testMediaDir, "failed_with_transcript.mp4").apply {
            writeBytes("sample video for failed job".toByteArray())
        }
        val outcome = container.savedItemRepository.saveMediaItem(
            media = ImportedMedia(testFile, "video/mp4", testFile.length(), 10000L, testFile.name),
            associatedUrl = "https://www.instagram.com/p/failed_test_url/",
            source = SaveSource.MEDIA_SHARE,
        )
        val itemId = (outcome as MediaSaveOutcome.Saved).itemId

        // Cloud manifest is FAILED, but audio extraction and transcription had already succeeded
        val failedManifest = CloudJobManifest(
            itemId = itemId,
            source = "MEDIA_SHARE",
            status = "FAILED",
            errorMessage = "Gemini API 500: Server internal error during analysis",
            createdAt = container.clock.now() - 30_000L,
            updatedAt = container.clock.now(),
            stages = CloudStageStates(
                audioExtraction = "SUCCEEDED",
                transcription = "SUCCEEDED",
                geminiAnalysis = "FAILED",
            ),
            results = CloudJobResults(
                transcript = "Astrophysics and gravitational wave astronomy detection principles",
                transcriptModel = "base",
                analysis = null,
            ),
        )
        container.fakeCloudClient.jobs[itemId] = failedManifest

        val synced = container.cloudSyncRepository.syncCompletedJobs()
        assertEquals(1, synced)

        val item = container.savedItemRepository.getItem(itemId)
        assertNotNull(item)
        assertEquals(ProcessingStatus.NEEDS_ATTENTION, item!!.processingStatus)
        assertEquals(StageStatus.SUCCEEDED, item.transcriptionStatus)
        assertEquals(StageStatus.FAILED, item.analysisStatus)
        assertTrue(item.lastError!!.contains("Gemini API 500"))

        // Verify transcript is preserved in Room
        val results = container.savedItemRepository.latestResults(itemId)
        val transcriptRes = results.firstOrNull { it.stage == ProcessingStage.TRANSCRIPTION }
        assertNotNull(transcriptRes)
        assertTrue(transcriptRes!!.transcript!!.contains("gravitational wave"))

        // Verify FTS4 offline search finds the item via the preserved transcript!
        val search = container.savedItemRepository.search("astrophysics", ItemQuery()).first()
        assertEquals(1, search.size)
        assertEquals(itemId, search.first().id)
    }

    @Test
    fun failedJobWithoutResultsDoesNotCauseIncorrectSearchEntries() = runTest {
        val testFile = File(testMediaDir, "failed_empty.mp4").apply {
            writeBytes("dummy content".toByteArray())
        }
        val outcome = container.savedItemRepository.saveMediaItem(
            media = ImportedMedia(testFile, "video/mp4", testFile.length(), 10000L, testFile.name),
            associatedUrl = "https://www.instagram.com/p/failed_empty_url/",
            source = SaveSource.MEDIA_SHARE,
        )
        val itemId = (outcome as MediaSaveOutcome.Saved).itemId

        // Item has title set by user
        container.savedItemRepository.updateUserMetadata(
            id = itemId,
            metadata = app.reelix.domain.model.UserMetadata(
                title = "Kubernetes Cluster Architecture Guide",
                caption = null,
                transcript = null,
                notes = null,
                category = null,
                tags = emptyList(),
            ),
        )

        // Cloud manifest is FAILED with ZERO results produced
        val failedManifest = CloudJobManifest(
            itemId = itemId,
            source = "MEDIA_SHARE",
            status = "FAILED",
            errorMessage = "Download media failed: 404 Not Found in storage",
            createdAt = container.clock.now() - 30_000L,
            updatedAt = container.clock.now(),
            stages = CloudStageStates(
                audioExtraction = "FAILED",
                transcription = "PENDING",
                geminiAnalysis = "PENDING",
            ),
            results = CloudJobResults(),
        )
        container.fakeCloudClient.jobs[itemId] = failedManifest

        val synced = container.cloudSyncRepository.syncCompletedJobs()
        assertEquals(1, synced)

        val item = container.savedItemRepository.getItem(itemId)
        assertNotNull(item)
        assertEquals(ProcessingStatus.NEEDS_ATTENTION, item!!.processingStatus)

        // FTS4 should index its legitimate user metadata cleanly
        val legitSearch = container.savedItemRepository.search("Kubernetes", ItemQuery()).first()
        assertEquals(1, legitSearch.size)
        assertEquals(itemId, legitSearch.first().id)

        // Non-existent keywords should NOT return any search hits
        val straySearch = container.savedItemRepository.search("Astrophysics", ItemQuery()).first()
        assertTrue(straySearch.none { it.id == itemId })
    }
}
