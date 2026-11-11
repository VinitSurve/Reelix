package app.reelix

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.reelix.cloud.CloudAnalysisResult
import app.reelix.cloud.CloudJobManifest
import app.reelix.cloud.CloudJobResults
import app.reelix.cloud.CloudMediaInfo
import app.reelix.cloud.CloudStageStates
import app.reelix.data.files.ImportedMedia
import app.reelix.domain.model.ContentType
import app.reelix.domain.model.InstagramLink
import app.reelix.domain.model.ItemQuery
import app.reelix.domain.model.LinkSaveOutcome
import app.reelix.domain.model.MediaSaveOutcome
import app.reelix.domain.model.ProcessingStage
import app.reelix.domain.model.ProcessingStatus
import app.reelix.domain.model.SaveSource
import app.reelix.domain.model.StageStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
            caption = "Amazing pasta tutorial",
            userNotes = "Make this tonight",
            category = "Cooking",
            userTags = listOf("food", "dinner"),
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
                acquisition = "SUCCEEDED",
                audioExtraction = "SUCCEEDED",
                transcription = "SUCCEEDED",
                geminiAnalysis = "SUCCEEDED",
            ),
            results = CloudJobResults(
                audioDriveFileId = "drive_audio_777",
                audioSizeBytes = 1_200_000L,
                transcript = "This is a transcribed tutorial on Kotlin coroutines and structured concurrency.",
                transcriptModel = "base",
                ocrText = "Step 1: Ingredients\nStep 2: Method",
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
        assertEquals("Amazing pasta tutorial", parsed.caption)
        assertEquals("Make this tonight", parsed.userNotes)
        assertEquals("Cooking", parsed.category)
        assertEquals(listOf("food", "dinner"), parsed.userTags)
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

        assertEquals(original.stages.acquisition, parsed.stages.acquisition)
        assertEquals("SUCCEEDED", parsed.stages.audioExtraction)
        assertEquals("SUCCEEDED", parsed.stages.transcription)
        assertEquals("SUCCEEDED", parsed.stages.geminiAnalysis)

        assertEquals("Step 1: Ingredients\nStep 2: Method", parsed.results.ocrText)
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

        // Verify local DB status updated to QUEUED (truly confirmed in Google Drive queue)
        val item = container.savedItemRepository.getItem(itemId)
        assertNotNull(item)
        assertEquals(ProcessingStatus.QUEUED, item!!.processingStatus)
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
                acquisition = "SUCCEEDED",
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
                acquisition = "SUCCEEDED",
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

    @Test
    fun sameItemSameFileRepeatedUploadReusesExistingDriveFile() = runTest {
        val client = app.reelix.cloud.MockCloudSyncClient()
        val testFile = File(testMediaDir, "repeat_video.mp4").apply {
            writeBytes("repeated upload payload".toByteArray())
        }

        val res1 = client.uploadMedia(testFile, "video/mp4", "video.mp4", "item-repeat-1")
        assertTrue(res1 is app.reelix.cloud.CloudUploadResult.Success)
        val fileId1 = (res1 as app.reelix.cloud.CloudUploadResult.Success).driveFileId

        val res2 = client.uploadMedia(testFile, "video/mp4", "video.mp4", "item-repeat-1")
        assertTrue(res2 is app.reelix.cloud.CloudUploadResult.Success)
        val fileId2 = (res2 as app.reelix.cloud.CloudUploadResult.Success).driveFileId

        assertEquals("Same item and same file must reuse the existing Drive file ID", fileId1, fileId2)
        assertEquals(1, client.uploadedFiles.size)
    }

    @Test
    fun differentItemsIdenticalFilenameDifferentContentCreatesSeparateDriveFiles() = runTest {
        val client = app.reelix.cloud.MockCloudSyncClient()
        val file1 = File(testMediaDir, "content1.mp4").apply { writeBytes("video payload alpha".toByteArray()) }
        val file2 = File(testMediaDir, "content2.mp4").apply { writeBytes("completely different payload beta".toByteArray()) }

        val res1 = client.uploadMedia(file1, "video/mp4", "reel.mp4", "item-alpha")
        val res2 = client.uploadMedia(file2, "video/mp4", "reel.mp4", "item-beta")

        assertTrue(res1 is app.reelix.cloud.CloudUploadResult.Success)
        assertTrue(res2 is app.reelix.cloud.CloudUploadResult.Success)

        val id1 = (res1 as app.reelix.cloud.CloudUploadResult.Success).driveFileId
        val id2 = (res2 as app.reelix.cloud.CloudUploadResult.Success).driveFileId

        assertFalse("Different items with identical filename must create separate Drive files", id1 == id2)
        assertEquals(2, client.uploadedFiles.size)
    }

    @Test
    fun differentItemsIdenticalFilenameAndIdenticalSizeCreatesSeparateDriveFiles() = runTest {
        val client = app.reelix.cloud.MockCloudSyncClient()
        val sameBytes = "10_bytes!!".toByteArray()
        val file1 = File(testMediaDir, "f1.mp4").apply { writeBytes(sameBytes) }
        val file2 = File(testMediaDir, "f2.mp4").apply { writeBytes(sameBytes) }

        val res1 = client.uploadMedia(file1, "video/mp4", "reel.mp4", "item-same-size-1")
        val res2 = client.uploadMedia(file2, "video/mp4", "reel.mp4", "item-same-size-2")

        assertTrue(res1 is app.reelix.cloud.CloudUploadResult.Success)
        assertTrue(res2 is app.reelix.cloud.CloudUploadResult.Success)

        val id1 = (res1 as app.reelix.cloud.CloudUploadResult.Success).driveFileId
        val id2 = (res2 as app.reelix.cloud.CloudUploadResult.Success).driveFileId

        assertFalse(
            "Different items with identical filename AND identical file size must still have separate identities",
            id1 == id2,
        )
        assertEquals(2, client.uploadedFiles.size)
    }

    @Test
    fun existingDriveFileWithoutReelixMetadataNotIncorrectlyReused() = runTest {
        val client = app.reelix.cloud.MockCloudSyncClient()
        // Simulate a pre-existing file in Drive without Reelix appProperties
        client.uploadedFiles["un_tagged_id_999"] = app.reelix.cloud.MockUploadedFile(
            name = "shared_reel.mp4",
            sizeBytes = 256,
            mimeType = "video/mp4",
            appProperties = emptyMap(),
        )

        val testFile = File(testMediaDir, "local_shared_reel.mp4").apply {
            writeBytes(ByteArray(256) { 7 })
        }

        val res = client.uploadMedia(testFile, "video/mp4", "shared_reel.mp4", "item-new-tagged")
        assertTrue(res is app.reelix.cloud.CloudUploadResult.Success)
        val createdId = (res as app.reelix.cloud.CloudUploadResult.Success).driveFileId

        assertFalse(
            "An existing Drive file without Reelix appProperties must not be incorrectly reused",
            createdId == "un_tagged_id_999",
        )
        assertEquals(2, client.uploadedFiles.size)
        assertEquals("item-new-tagged", client.uploadedFiles[createdId]?.appProperties?.get("reelix_item_id"))
    }

    @Test
    fun retryAfterInterruptedUploadCorrectItemResumesItsOwnSession() = runTest {
        val client = app.reelix.cloud.MockCloudSyncClient()
        client.simulateInterruptionOnce = true
        val testFile = File(testMediaDir, "interrupted_demo.mp4").apply {
            writeBytes(ByteArray(2048) { (it % 256).toByte() })
        }

        // 1. First attempt for item-interrupted-A fails due to simulated interruption
        val firstResultA = client.uploadMedia(testFile, "video/mp4", "demo.mp4", "item-interrupted-A")
        assertTrue(firstResultA is app.reelix.cloud.CloudUploadResult.Failure)

        // 2. Upload for a different item (item-B) with the exact same file
        val resultB = client.uploadMedia(testFile, "video/mp4", "demo.mp4", "item-B")
        assertTrue(resultB is app.reelix.cloud.CloudUploadResult.Success)
        assertEquals("Item B must start a fresh upload and not resume Item A's session", 0L, client.lastUploadedOffset)

        // 3. Retry upload for item-interrupted-A
        val retryResultA = client.uploadMedia(testFile, "video/mp4", "demo.mp4", "item-interrupted-A")
        assertTrue(retryResultA is app.reelix.cloud.CloudUploadResult.Success)
        assertEquals("Item A retry must resume from its own saved offset", testFile.length() / 2, client.lastUploadedOffset)

        val idA = (retryResultA as app.reelix.cloud.CloudUploadResult.Success).driveFileId
        val idB = (resultB as app.reelix.cloud.CloudUploadResult.Success).driveFileId
        assertFalse("Item A and Item B must have separate Drive files", idA == idB)
        assertEquals(2, client.uploadedFiles.size)
    }

    @Test
    fun oldManifestWithoutAcquisitionStageIsParsedBackwardCompatibly() {
        // 1. Old manifest with media present
        val oldMediaJson = """
            {
              "version": 1,
              "item_id": "legacy-item-123",
              "source": "MEDIA_SHARE",
              "status": "QUEUED",
              "created_at": 1730000000000,
              "updated_at": 1730000000000,
              "media": {
                "file_name": "video.mp4",
                "mime_type": "video/mp4",
                "drive_file_id": "drive_123",
                "size_bytes": 100000
              },
              "stages": {
                "audio_extraction": "PENDING",
                "transcription": "PENDING",
                "gemini_analysis": "PENDING"
              },
              "results": {}
            }
        """.trimIndent()
        val parsedMedia = CloudJobManifest.fromJson(oldMediaJson)
        assertEquals("SKIPPED", parsedMedia.stages.acquisition)
        assertEquals("PENDING", parsedMedia.stages.audioExtraction)
        assertEquals("PENDING", parsedMedia.stages.transcription)

        // 2. Old manifest without media present (URL-only)
        val oldUrlJson = """
            {
              "version": 1,
              "item_id": "legacy-url-456",
              "source": "SHARE",
              "original_url": "https://www.instagram.com/reel/Legacy456/",
              "status": "QUEUED",
              "created_at": 1730000000000,
              "updated_at": 1730000000000,
              "stages": {
                "audio_extraction": "SKIPPED",
                "transcription": "SKIPPED",
                "gemini_analysis": "PENDING"
              },
              "results": {}
            }
        """.trimIndent()
        val parsedUrl = CloudJobManifest.fromJson(oldUrlJson)
        assertEquals("PENDING", parsedUrl.stages.acquisition)
        assertEquals("SKIPPED", parsedUrl.stages.audioExtraction)
    }

    @Test
    fun syncCompletedJobsSynchronizesCloudAcquiredMedia() = runTest {
        // 1. Create a link-only item (no local media)
        val linkOutcome = container.savedItemRepository.saveLink(
            InstagramLink(
                originalUrl = "https://www.instagram.com/reel/CloudAcquired123/",
                normalizedUrl = "https://www.instagram.com/reel/CloudAcquired123/",
                contentType = ContentType.REEL,
                shortcode = "CloudAcquired123",
            ),
            source = SaveSource.SHARE,
        )
        assertTrue(linkOutcome is LinkSaveOutcome.Saved)
        val itemId = (linkOutcome as LinkSaveOutcome.Saved).itemId

        // Verify initial state: no media
        val initialItem = container.savedItemRepository.getItem(itemId)
        assertNotNull(initialItem)
        assertFalse("Initial link item must not have media available", initialItem!!.mediaAvailable)
        assertNull(initialItem.mediaFileName)

        // Enable cloud in settings
        container.settingsRepository.update {
            it.copy(
                cloudProcessingEnabled = true,
                cloudDriveFolderId = "folder_root_123",
            )
        }
        container.settingsRepository.setCloudDriveCredentials(accessToken = "token_abc")

        // 2. Manifest returned by cloud processor where media was acquired by yt-dlp
        val completedManifest = CloudJobManifest(
            itemId = itemId,
            source = "SHARE",
            originalUrl = "https://www.instagram.com/reel/CloudAcquired123/",
            caption = "Amazing cloud acquired reel caption",
            status = "COMPLETED",
            createdAt = container.clock.now() - 50_000L,
            updatedAt = container.clock.now(),
            media = CloudMediaInfo(
                fileName = "cloud_acquired_123.mp4",
                mimeType = "video/mp4",
                driveFileId = "cloud_media_drive_id_999",
                sizeBytes = 12_345_678L,
                durationMs = 28_000L,
            ),
            stages = CloudStageStates(
                acquisition = "SUCCEEDED",
                audioExtraction = "SUCCEEDED",
                transcription = "SUCCEEDED",
                geminiAnalysis = "SUCCEEDED",
            ),
            results = CloudJobResults(
                audioDriveFileId = "cloud_audio_drive_id_888",
                audioSizeBytes = 1_000_000L,
                transcript = "This speech was extracted from the cloud acquired Instagram reel video.",
                transcriptModel = "base",
                analysis = CloudAnalysisResult(
                    summary = "Cloud acquired summary",
                    detailedSummary = "Detailed insights from acquired media",
                    mainTopic = "Cloud Media Ingestion",
                    category = "Technology",
                    keywords = listOf("cloud", "yt-dlp", "media"),
                    tags = listOf("automation", "cloud"),
                    keyTakeaways = listOf("Acquired without local device download"),
                    importantConcepts = listOf("Headless Acquisition"),
                    actionableSteps = listOf("Streamline sync"),
                    modelName = "gemini-3.6-flash",
                    generatedAt = container.clock.now(),
                ),
            ),
        )
        container.fakeCloudClient.jobs[itemId] = completedManifest

        // 3. Sync completed jobs
        val syncedCount = container.cloudSyncRepository.syncCompletedJobs()
        assertEquals(1, syncedCount)

        // 4. Verify item now reflects cloud-acquired media
        val updatedItem = container.savedItemRepository.getItem(itemId)
        assertNotNull(updatedItem)
        assertEquals(ProcessingStatus.COMPLETED, updatedItem!!.processingStatus)
        assertTrue("mediaAvailable must be true after cloud acquisition sync", updatedItem.mediaAvailable)
        assertEquals("cloud_acquired_123.mp4", updatedItem.mediaFileName)
        assertEquals(12_345_678L, updatedItem.mediaSizeBytes)
        assertEquals(28_000L, updatedItem.mediaDurationMs)
        assertEquals("video/mp4", updatedItem.mediaMimeType)

        // Verify transcript and analysis stored
        val results = container.savedItemRepository.latestResults(itemId)
        val transcriptRes = results.firstOrNull { it.stage == ProcessingStage.TRANSCRIPTION }
        assertNotNull(transcriptRes)
        assertTrue(transcriptRes!!.transcript!!.contains("cloud acquired Instagram reel"))
    }

    @Test
    fun repeatedSyncIsIdempotentAndDoesNotDuplicateResultsOrCorruptState() = runTest {
        val linkOutcome = container.savedItemRepository.saveLink(
            InstagramLink(
                originalUrl = "https://www.instagram.com/reel/IdempotentSyncTest/",
                normalizedUrl = "https://www.instagram.com/reel/IdempotentSyncTest/",
                contentType = ContentType.REEL,
                shortcode = "IdempotentSyncTest",
            ),
            source = SaveSource.SHARE,
        )
        val itemId = (linkOutcome as LinkSaveOutcome.Saved).itemId

        container.settingsRepository.update {
            it.copy(
                cloudProcessingEnabled = true,
                cloudDriveFolderId = "folder_root_123",
            )
        }
        container.settingsRepository.setCloudDriveCredentials(accessToken = "token_abc")

        val completedManifest = CloudJobManifest(
            itemId = itemId,
            source = "SHARE",
            originalUrl = "https://www.instagram.com/reel/IdempotentSyncTest/",
            status = "COMPLETED",
            createdAt = container.clock.now() - 50_000L,
            updatedAt = container.clock.now(),
            media = CloudMediaInfo(
                fileName = "idempotent.mp4",
                mimeType = "video/mp4",
                driveFileId = "cloud_id_idempotent",
                sizeBytes = 3_000_000L,
                durationMs = 15_000L,
            ),
            stages = CloudStageStates(
                acquisition = "SUCCEEDED",
                audioExtraction = "SUCCEEDED",
                transcription = "SUCCEEDED",
                geminiAnalysis = "SUCCEEDED",
            ),
            results = CloudJobResults(
                transcript = "Idempotent transcript test text.",
                analysis = CloudAnalysisResult(
                    summary = "Idempotent summary",
                    detailedSummary = "Detailed idempotency validation",
                    mainTopic = "Idempotency",
                    category = "Testing",
                    keywords = listOf("idempotent"),
                    tags = listOf("testing"),
                    keyTakeaways = listOf("Must not duplicate results"),
                    importantConcepts = listOf("Idempotent Operations"),
                    actionableSteps = listOf("Verify counts"),
                    modelName = "gemini-3.6-flash",
                    generatedAt = container.clock.now(),
                ),
            ),
        )
        container.fakeCloudClient.jobs[itemId] = completedManifest

        // Sync 1: Processes the completed job
        val sync1 = container.cloudSyncRepository.syncCompletedJobs()
        assertEquals(1, sync1)

        val resultsAfterSync1 = container.savedItemRepository.latestResults(itemId)
        val transcriptsCount1 = resultsAfterSync1.count { it.stage == ProcessingStage.TRANSCRIPTION }
        val analysisCount1 = resultsAfterSync1.count { it.stage == ProcessingStage.ANALYSIS }
        assertEquals(1, transcriptsCount1)
        assertEquals(1, analysisCount1)

        // Sync 2: Immediate re-sync
        val sync2 = container.cloudSyncRepository.syncCompletedJobs()
        assertEquals("Subsequent sync must not re-process already completed items", 0, sync2)

        val resultsAfterSync2 = container.savedItemRepository.latestResults(itemId)
        val transcriptsCount2 = resultsAfterSync2.count { it.stage == ProcessingStage.TRANSCRIPTION }
        val analysisCount2 = resultsAfterSync2.count { it.stage == ProcessingStage.ANALYSIS }
        assertEquals("Transcript rows must not be duplicated", 1, transcriptsCount2)
        assertEquals("Analysis rows must not be duplicated", 1, analysisCount2)

        // Status remains COMPLETED
        val item = container.savedItemRepository.getItem(itemId)
        assertEquals(ProcessingStatus.COMPLETED, item!!.processingStatus)
    }

    @Test
    fun syncPreservesExistingDeviceUploadedMediaAndNeverOverwrites() = runTest {
        // 1. Create item with actual device-uploaded media
        val localFile = File(testMediaDir, "device_original.mp4").apply {
            writeBytes("device original video payload".toByteArray())
        }
        val media = ImportedMedia(
            file = localFile,
            mimeType = "video/mp4",
            sizeBytes = localFile.length(),
            durationMs = 12_000L,
            originalFileName = "device_original.mp4",
        )
        val outcome = container.savedItemRepository.saveMediaItem(
            media = media,
            associatedUrl = "https://www.instagram.com/reel/DeviceOriginalReel/",
            source = SaveSource.MEDIA_SHARE,
        )
        val itemId = (outcome as MediaSaveOutcome.Saved).itemId

        val initialItem = container.savedItemRepository.getItem(itemId)
        assertNotNull(initialItem)
        assertEquals("device_original.mp4", initialItem!!.mediaFileName)
        assertEquals(localFile.absolutePath, initialItem.mediaPath)

        container.settingsRepository.update {
            it.copy(
                cloudProcessingEnabled = true,
                cloudDriveFolderId = "folder_root_123",
            )
        }
        container.settingsRepository.setCloudDriveCredentials(accessToken = "token_abc")

        // 2. Cloud manifest reports cloud file name
        val completedManifest = CloudJobManifest(
            itemId = itemId,
            source = "MEDIA_SHARE",
            originalUrl = "https://www.instagram.com/reel/DeviceOriginalReel/",
            status = "COMPLETED",
            createdAt = container.clock.now() - 50_000L,
            updatedAt = container.clock.now(),
            media = CloudMediaInfo(
                fileName = "cloud_renamed_or_converted.mp4",
                mimeType = "video/mp4",
                driveFileId = "cloud_media_id_777",
                sizeBytes = 999_999L,
                durationMs = 12_000L,
            ),
            stages = CloudStageStates(
                acquisition = "SKIPPED",
                audioExtraction = "SUCCEEDED",
                transcription = "SUCCEEDED",
                geminiAnalysis = "SUCCEEDED",
            ),
            results = CloudJobResults(
                transcript = "Transcribed text for device uploaded media.",
                analysis = CloudAnalysisResult(
                    summary = "Summary for device media",
                    detailedSummary = "Detailed device media analysis",
                    mainTopic = "Device Media",
                    category = "Media",
                    keywords = listOf("device"),
                    tags = listOf("device"),
                    keyTakeaways = listOf("Preserve local files"),
                    importantConcepts = listOf("Device Preservation"),
                    actionableSteps = listOf("Do not overwrite"),
                    modelName = "gemini-3.6-flash",
                    generatedAt = container.clock.now(),
                ),
            ),
        )
        container.fakeCloudClient.jobs[itemId] = completedManifest

        // 3. Sync
        val synced = container.cloudSyncRepository.syncCompletedJobs()
        assertEquals(1, synced)

        // 4. Verify local mediaPath and mediaFileName were PRESERVED and not overwritten
        val updatedItem = container.savedItemRepository.getItem(itemId)
        assertNotNull(updatedItem)
        assertEquals(localFile.absolutePath, updatedItem!!.mediaPath)
        assertEquals("device_original.mp4", updatedItem.mediaFileName)
        assertEquals(localFile.length(), updatedItem.mediaSizeBytes)
        assertEquals(ProcessingStatus.COMPLETED, updatedItem.processingStatus)
    }

    @Test
    fun endToEnd_saveReelWithCaptionAndNotes_cloudOffload_syncBackWithOcrAndGemini_ftsSearchable() = runTest {
        // 1. User shares or enters an Instagram Reel URL with caption and notes
        val sharedText = "Authentic Roman Carbonara recipe https://www.instagram.com/reel/C8carbonara123/"
        val saveResult = container.shareIntakeUseCase.handleSharedTexts(
            texts = listOf(sharedText),
            source = SaveSource.SHARE,
            initialNotes = "Remember: strictly guanciale, never bacon",
            initialCategory = "Culinary",
        )
        assertTrue(saveResult is app.reelix.domain.model.ShareSaveResult.Handled)
        val handled = saveResult as app.reelix.domain.model.ShareSaveResult.Handled
        assertEquals(1, handled.saved.size)
        val itemId = handled.saved.first().itemId

        // Verify initial state in Room database
        val item = container.savedItemRepository.getItem(itemId)
        assertNotNull(item)
        assertEquals("https://www.instagram.com/reel/C8carbonara123/", item!!.originalUrl)
        assertEquals("https://www.instagram.com/reel/C8carbonara123/", item.normalizedUrl)
        assertEquals("Authentic Roman Carbonara recipe", item.caption)
        assertEquals("Remember: strictly guanciale, never bacon", item.userNotes)
        assertEquals("Culinary", item.category)
        assertEquals(ProcessingStatus.QUEUED, item.processingStatus)

        // 2. Offload to Cloud Queue (Google Drive)
        container.fakeCloudClient.isEnabled = true
        val uploaded = container.cloudSyncRepository.uploadPendingItem(itemId)
        assertTrue(uploaded)

        // Verify manifest registered in Drive queue
        val queuedManifest = container.fakeCloudClient.jobs[itemId]
        assertNotNull(queuedManifest)
        assertEquals(itemId, queuedManifest!!.itemId)
        assertEquals("Authentic Roman Carbonara recipe", queuedManifest.caption)
        assertEquals("Remember: strictly guanciale, never bacon", queuedManifest.userNotes)
        assertEquals("Culinary", queuedManifest.category)
        assertEquals("QUEUED", queuedManifest.status)

        // 3. Worker completes all stages in cloud (including Whisper transcription, Visual Text OCR, and Gemini)
        val completedManifest = queuedManifest.copy(
            status = "COMPLETED",
            claimedBy = "worker_client_b",
            claimedAt = container.clock.now(),
            updatedAt = container.clock.now(),
            stages = CloudStageStates(
                acquisition = "SUCCEEDED",
                audioExtraction = "SUCCEEDED",
                transcription = "SUCCEEDED",
                geminiAnalysis = "SUCCEEDED",
            ),
            results = CloudJobResults(
                audioDriveFileId = "audio_carbonara_drive_id",
                audioSizeBytes = 850_000L,
                transcript = "Whisk eggs with grated pecorino cheese and coarse black pepper until creamy",
                transcriptModel = "base",
                ocrText = "Step 1: Crisp guanciale in pan\nStep 2: No heavy cream ever!",
                analysis = CloudAnalysisResult(
                    summary = "Traditional Roman carbonara technique",
                    detailedSummary = "Step by step guide to creating an authentic egg and pecorino emulsion with pasta water.",
                    mainTopic = "Italian Cooking",
                    category = "Culinary",
                    keywords = listOf("carbonara", "pecorino", "guanciale", "emulsion"),
                    tags = listOf("pasta", "roman", "dinner"),
                    keyTakeaways = listOf("Never add heavy cream", "Stir egg mixture off heat"),
                    importantConcepts = listOf("Emulsification", "Guanciale Rendered Fat"),
                    actionableSteps = listOf("Crisp guanciale", "Toss pasta in fat", "Mix egg off heat"),
                    modelName = "gemini-3.6-flash",
                    generatedAt = container.clock.now(),
                ),
            ),
        )
        container.fakeCloudClient.jobs[itemId] = completedManifest

        // 4. Synchronize completed jobs back into local Room database
        val synced = container.cloudSyncRepository.syncCompletedJobs()
        assertEquals(1, synced)

        // Verify updated database state
        val syncedItem = container.savedItemRepository.getItem(itemId)
        assertNotNull(syncedItem)
        assertEquals(ProcessingStatus.COMPLETED, syncedItem!!.processingStatus)
        assertEquals(StageStatus.SUCCEEDED, syncedItem.transcriptionStatus)
        assertEquals(StageStatus.SUCCEEDED, syncedItem.audioExtractionStatus)
        assertEquals(StageStatus.SUCCEEDED, syncedItem.analysisStatus)

        // 5. Verify ItemDetails contains all enriched knowledge
        val details = container.savedItemRepository.observeDetails(itemId).first()
        assertNotNull(details)
        assertEquals("Traditional Roman carbonara technique", details!!.aiResult?.summary)
        assertEquals("Whisk eggs with grated pecorino cheese and coarse black pepper until creamy", details.generatedTranscript)
        assertEquals("Step 1: Crisp guanciale in pan\nStep 2: No heavy cream ever!", details.ocrText)
        assertEquals("https://www.instagram.com/reel/C8carbonara123/", details.originalUrl)
        assertTrue(details.tags.any { it.name == "pasta" })

        // 6. Verify Room FTS4 Search matches all aspects of knowledge
        // Keyword from caption:
        val searchByCaption = container.savedItemRepository.search("recipe", ItemQuery()).first()
        assertEquals(1, searchByCaption.size)
        assertEquals(itemId, searchByCaption.first().id)

        // Keyword from user notes:
        val searchByNotes = container.savedItemRepository.search("guanciale", ItemQuery()).first()
        assertEquals(1, searchByNotes.size)
        assertEquals(itemId, searchByNotes.first().id)

        // Keyword from Whisper transcript:
        val searchByTranscript = container.savedItemRepository.search("pecorino", ItemQuery()).first()
        assertEquals(1, searchByTranscript.size)
        assertEquals(itemId, searchByTranscript.first().id)

        // Keyword from on-screen OCR visual text:
        val searchByOcr = container.savedItemRepository.search("cream", ItemQuery()).first()
        assertEquals(1, searchByOcr.size)
        assertEquals(itemId, searchByOcr.first().id)

        // Keyword from Gemini concept:
        val searchByConcept = container.savedItemRepository.search("emulsification", ItemQuery()).first()
        assertEquals(1, searchByConcept.size)
        assertEquals(itemId, searchByConcept.first().id)

        // Search by tag:
        val searchByTag = container.savedItemRepository.search("roman", ItemQuery()).first()
        assertEquals(1, searchByTag.size)
        assertEquals(itemId, searchByTag.first().id)
    }

    @Test
    fun resubmissionPreservesRemoteProcessingStatusAndStages() = runTest {
        // Save initial item
        val linkOutcome = container.savedItemRepository.saveLink(
            InstagramLink(
                originalUrl = "https://www.instagram.com/reel/ResubmitTest123/",
                normalizedUrl = "https://www.instagram.com/reel/ResubmitTest123/",
                contentType = ContentType.REEL,
                shortcode = "ResubmitTest123",
            ),
            source = SaveSource.SHARE,
            initialNotes = "Initial Notes",
        )
        assertTrue(linkOutcome is LinkSaveOutcome.Saved)
        val itemId = (linkOutcome as LinkSaveOutcome.Saved).itemId

        // Enable cloud
        container.settingsRepository.update {
            it.copy(
                cloudProcessingEnabled = true,
                cloudDriveFolderId = "folder_root_123",
            )
        }
        container.settingsRepository.setCloudDriveCredentials(accessToken = "token_abc")

        // First upload submits as QUEUED
        val uploaded = container.cloudSyncRepository.uploadPendingItem(itemId)
        assertTrue(uploaded)

        val firstManifest = container.fakeCloudClient.fetchJobManifest(itemId)
        assertNotNull(firstManifest)
        assertEquals("QUEUED", firstManifest!!.status)
        assertEquals(1L, firstManifest.recordRevision)

        // Simulate cloud worker actively claiming and running stages
        val activeManifest = firstManifest.copy(
            status = "PROCESSING",
            claimedBy = "worker-alpha",
            claimedAt = container.clock.now(),
            recordRevision = 2L,
            stages = CloudStageStates(
                acquisition = "SUCCEEDED",
                audioExtraction = "SUCCEEDED",
                transcription = "PENDING",
                ocr = "PENDING",
                geminiAnalysis = "PENDING",
            ),
        )
        container.fakeCloudClient.submitJobManifest(activeManifest)

        // User updates local notes and triggers resubmit
        container.savedItemRepository.updateUserMetadata(
            id = itemId,
            metadata = app.reelix.domain.model.UserMetadata(
                title = null,
                caption = null,
                transcript = null,
                notes = "Updated Notes While Processing",
                category = null,
                tags = emptyList(),
            ),
        )
        val resubmitted = container.cloudSyncRepository.uploadPendingItem(itemId)
        assertTrue(resubmitted)

        // Authoritative cloud manifest must PRESERVE active status and stages!
        val afterResubmit = container.fakeCloudClient.fetchJobManifest(itemId)
        assertNotNull(afterResubmit)
        assertEquals("PROCESSING", afterResubmit!!.status)
        assertEquals("worker-alpha", afterResubmit.claimedBy)
        assertEquals("SUCCEEDED", afterResubmit.stages.audioExtraction)
        assertEquals("Updated Notes While Processing", afterResubmit.userNotes)
        assertEquals(3L, afterResubmit.recordRevision)
    }

    @Test
    fun schemaVersionAndRecordRevisionDecoupling() {
        val manifest = CloudJobManifest(
            schemaVersion = 1,
            recordRevision = 42L,
            itemId = "item_rev_test",
            source = "SHARE",
            createdAt = 1000L,
            updatedAt = 2000L,
        )
        val json = manifest.toJson()
        val parsed = CloudJobManifest.fromJson(json)

        assertEquals(1, parsed.schemaVersion)
        assertEquals(42L, parsed.recordRevision)
        assertEquals("item_rev_test", parsed.itemId)
    }

    @Test
    fun testSyncManifestProcessedWithoutAiAnalysisMarksCompleted() = runTest {
        // 1. Create initial item
        val linkOutcome = container.savedItemRepository.saveLink(
            InstagramLink(
                originalUrl = "https://www.instagram.com/reel/PhotoTip123/",
                normalizedUrl = "https://www.instagram.com/reel/PhotoTip123/",
                contentType = ContentType.REEL,
                shortcode = "PhotoTip123",
            ),
            source = SaveSource.SHARE,
            initialNotes = "Rule of thirds explanation",
        )
        assertTrue(linkOutcome is LinkSaveOutcome.Saved)
        val itemId = (linkOutcome as LinkSaveOutcome.Saved).itemId

        // 2. Enable cloud sync and upload
        container.fakeCloudClient.isEnabled = true
        val uploaded = container.cloudSyncRepository.uploadPendingItem(itemId)
        assertTrue(uploaded)

        val queuedManifest = container.fakeCloudClient.jobs[itemId]
        assertNotNull(queuedManifest)

        // 3. Worker completes media + Whisper transcription, but skips Gemini analysis (unconfigured or skipped)
        val completedManifest = queuedManifest!!.copy(
            status = "COMPLETED",
            claimedBy = "worker_gha_no_ai",
            claimedAt = container.clock.now(),
            updatedAt = container.clock.now(),
            stages = CloudStageStates(
                acquisition = "SUCCEEDED",
                audioExtraction = "SUCCEEDED",
                transcription = "SUCCEEDED",
                geminiAnalysis = "SKIPPED",
            ),
            results = CloudJobResults(
                audioDriveFileId = "audio_photo_drive_id",
                audioSizeBytes = 450_000L,
                transcript = "Mastering photography composition requires balancing light and shadows across the frame",
                transcriptModel = "base",
                ocrText = "Tip 1: Leading lines",
                analysis = null,
            ),
        )
        container.fakeCloudClient.jobs[itemId] = completedManifest

        // 4. Synchronize from cloud
        val synced = container.cloudSyncRepository.syncCompletedJobs()
        assertEquals(1, synced)

        // 5. Verify local database marks item COMPLETED, transcription SUCCEEDED, and analysis SKIPPED
        val syncedItem = container.savedItemRepository.getItem(itemId)
        assertNotNull(syncedItem)
        assertEquals(ProcessingStatus.COMPLETED, syncedItem!!.processingStatus)
        assertEquals(StageStatus.SUCCEEDED, syncedItem.transcriptionStatus)
        assertEquals(StageStatus.SKIPPED, syncedItem.analysisStatus)

        // 6. Verify item details has transcript and OCR
        val details = container.savedItemRepository.observeDetails(itemId).first()
        assertNotNull(details)
        assertNull(details!!.aiResult)
        assertEquals("Mastering photography composition requires balancing light and shadows across the frame", details.generatedTranscript)
        assertEquals("Tip 1: Leading lines", details.ocrText)

        // 7. Verify FTS4 search finds the item via transcript
        val searchResults = container.savedItemRepository.search("photography", ItemQuery()).first()
        assertEquals(1, searchResults.size)
        assertEquals(itemId, searchResults.first().id)
    }

    @Test
    fun testSyncManifestWaitingRetrySetsLocalStatusAndRecovers() = runTest {
        // 1. Create initial item
        val linkOutcome = container.savedItemRepository.saveLink(
            InstagramLink(
                originalUrl = "https://www.instagram.com/reel/RetryTest123/",
                normalizedUrl = "https://www.instagram.com/reel/RetryTest123/",
                contentType = ContentType.REEL,
                shortcode = "RetryTest123",
            ),
            source = SaveSource.SHARE,
            initialNotes = "Distributed queue resilience test",
        )
        assertTrue(linkOutcome is LinkSaveOutcome.Saved)
        val itemId = (linkOutcome as LinkSaveOutcome.Saved).itemId

        // 2. Enable cloud sync and upload
        container.fakeCloudClient.isEnabled = true
        val uploaded = container.cloudSyncRepository.uploadPendingItem(itemId)
        assertTrue(uploaded)

        val queuedManifest = container.fakeCloudClient.jobs[itemId]
        assertNotNull(queuedManifest)

        // 3. Worker experiences transient Gemini 503 failure: marks WAITING_RETRY
        val retryTime = container.clock.now() + 60_000L
        val retryManifest = queuedManifest!!.copy(
            status = "WAITING_RETRY",
            claimedBy = null,
            claimedAt = null,
            updatedAt = container.clock.now(),
            retryCount = 1,
            nextRetryAt = retryTime,
            errorMessage = "HTTP 503: The model is overloaded. Please try again later.",
            stages = CloudStageStates(
                acquisition = "SUCCEEDED",
                audioExtraction = "SUCCEEDED",
                transcription = "SUCCEEDED",
                geminiAnalysis = "FAILED",
            ),
            results = CloudJobResults(
                audioDriveFileId = "audio_resilience_id",
                audioSizeBytes = 320_000L,
                transcript = "Architecting resilient distributed systems requires exponential backoff with jitter",
                transcriptModel = "base",
                ocrText = "Reliability pattern: Backoff",
                analysis = null,
            ),
        )
        container.fakeCloudClient.jobs[itemId] = retryManifest

        // 4. Synchronize WAITING_RETRY state to Android
        val syncedRetry = container.cloudSyncRepository.syncCompletedJobs()
        assertEquals(1, syncedRetry)

        val itemDuringRetry = container.savedItemRepository.getItem(itemId)
        assertNotNull(itemDuringRetry)
        assertEquals(ProcessingStatus.WAITING_RETRY, itemDuringRetry!!.processingStatus)
        assertEquals("HTTP 503: The model is overloaded. Please try again later.", itemDuringRetry.lastError)
        assertEquals(retryTime, itemDuringRetry.nextAttemptAt)
        assertEquals(StageStatus.SUCCEEDED, itemDuringRetry.transcriptionStatus)

        // 5. Cloud worker resumes after timer matures and successfully completes Gemini analysis
        val recoveredManifest = retryManifest.copy(
            status = "COMPLETED",
            claimedBy = "worker_recovered",
            claimedAt = container.clock.now(),
            updatedAt = container.clock.now(),
            stages = retryManifest.stages.copy(geminiAnalysis = "SUCCEEDED"),
            results = retryManifest.results.copy(
                analysis = CloudAnalysisResult(
                    summary = "Distributed resilience with jittered backoff",
                    detailedSummary = "Detailed guide on preventing thundering herd problems with randomized exponential backoff.",
                    mainTopic = "Distributed Systems",
                    category = "Engineering",
                    keywords = listOf("backoff", "jitter", "resilience"),
                    tags = listOf("architecture", "systems"),
                    keyTakeaways = listOf("Avoid synchronous retries", "Add full jitter"),
                    importantConcepts = listOf("Thundering Herd"),
                    actionableSteps = listOf("Implement truncated binary backoff"),
                    modelName = "gemini-3.6-flash",
                    generatedAt = container.clock.now(),
                ),
            ),
        )
        container.fakeCloudClient.jobs[itemId] = recoveredManifest

        // 6. Synchronize COMPLETED recovery state to Android
        val syncedRecovery = container.cloudSyncRepository.syncCompletedJobs()
        assertEquals(1, syncedRecovery)

        val finalItem = container.savedItemRepository.getItem(itemId)
        assertNotNull(finalItem)
        assertEquals(ProcessingStatus.COMPLETED, finalItem!!.processingStatus)
        assertEquals(StageStatus.SUCCEEDED, finalItem.transcriptionStatus)
        assertEquals(StageStatus.SUCCEEDED, finalItem.analysisStatus)

        // 7. Verify FTS4 search indexes both transcript and recovered AI takeaways
        val searchTranscript = container.savedItemRepository.search("jitter", ItemQuery()).first()
        assertEquals(1, searchTranscript.size)
        assertEquals(itemId, searchTranscript.first().id)

        val searchTakeaway = container.savedItemRepository.search("thundering", ItemQuery()).first()
        assertEquals(1, searchTakeaway.size)
        assertEquals(itemId, searchTakeaway.first().id)
    }

    @Test
    fun honestLifecycle_cloudEnabledSavesAsWaitingUpload_andUploadSetsQueued() = runTest {
        // Enable cloud processing
        container.fakeSettings.update { it.copy(cloudProcessingEnabled = true) }

        val link = InstagramLink(
            originalUrl = "https://www.instagram.com/reel/DfgFcSzlTae/",
            normalizedUrl = "https://www.instagram.com/reel/DfgFcSzlTae/",
            shortcode = "DfgFcSzlTae",
            contentType = ContentType.REEL,
        )
        val outcome = container.savedItemRepository.saveLink(link)
        assertTrue(outcome is LinkSaveOutcome.Saved)
        val itemId = (outcome as LinkSaveOutcome.Saved).itemId

        // 1. Must be WAITING_UPLOAD when saved, NOT QUEUED or PROCESSING
        val savedItem = container.savedItemRepository.getItem(itemId)
        assertNotNull(savedItem)
        assertEquals(ProcessingStatus.WAITING_UPLOAD, savedItem!!.processingStatus)

        // 2. If client is unconfigured, upload fails and status remains WAITING_UPLOAD
        container.fakeCloudClient.isEnabled = false
        val uploadFailed = container.cloudSyncRepository.uploadPendingItem(itemId)
        assertFalse(uploadFailed)
        val itemAfterFail = container.savedItemRepository.getItem(itemId)
        assertEquals(ProcessingStatus.WAITING_UPLOAD, itemAfterFail!!.processingStatus)

        // 3. When upload succeeds, status becomes QUEUED (confirmed on Google Drive)
        container.fakeCloudClient.isEnabled = true
        val uploadSuccess = container.cloudSyncRepository.uploadPendingItem(itemId)
        assertTrue(uploadSuccess)
        val itemAfterSuccess = container.savedItemRepository.getItem(itemId)
        assertEquals(ProcessingStatus.QUEUED, itemAfterSuccess!!.processingStatus)

        // 4. When cloud worker claims and sets remote manifest to PROCESSING, sync updates local status to PROCESSING
        val remoteManifest = container.fakeCloudClient.jobs[itemId]!!
        container.fakeCloudClient.jobs[itemId] = remoteManifest.copy(
            status = "PROCESSING",
            claimedBy = "gha-worker-99",
            claimedAt = container.clock.now(),
        )
        val synced = container.cloudSyncRepository.syncCompletedJobs()
        assertEquals(1, synced)
        val itemClaimed = container.savedItemRepository.getItem(itemId)
        assertEquals(ProcessingStatus.PROCESSING, itemClaimed!!.processingStatus)
    }

    @Test
    fun reprocessItemFromScratchIncrementsGenerationAndResetsRoomStages() = runTest {
        // 1. Create a link item
        val link = InstagramLink(
            originalUrl = "https://www.instagram.com/p/DfgFcSzlTae/",
            normalizedUrl = "https://www.instagram.com/p/DfgFcSzlTae",
            shortcode = "DfgFcSzlTae",
            contentType = ContentType.REEL,
        )
        val outcome = container.savedItemRepository.saveLink(link, source = SaveSource.SHARE)
        val itemId = (outcome as LinkSaveOutcome.Saved).itemId

        // 2. Put generation 1 COMPLETED manifest in fakeCloudClient
        val gen1Manifest = CloudJobManifest(
            itemId = itemId,
            source = "SHARE",
            originalUrl = link.originalUrl,
            status = "COMPLETED",
            generation = 1,
            recordRevision = 3L,
            createdAt = container.clock.now() - 50_000L,
            updatedAt = container.clock.now() - 10_000L,
            stages = CloudStageStates(
                acquisition = "SUCCEEDED",
                audioExtraction = "SUCCEEDED",
                transcription = "SUCCEEDED",
                geminiAnalysis = "SUCCEEDED",
            ),
            results = CloudJobResults(
                transcript = "Gen 1 transcript",
                analysis = CloudAnalysisResult(
                    summary = "Gen 1 summary",
                    detailedSummary = "Gen 1 details",
                    mainTopic = "Gen 1 topic",
                    category = "Tech",
                    keywords = listOf("vulnerability"),
                    tags = listOf("security"),
                    keyTakeaways = listOf("Takeaway 1"),
                    importantConcepts = listOf("Concept 1"),
                    actionableSteps = listOf("Step 1"),
                    modelName = "gemini-3.6-flash",
                    generatedAt = container.clock.now(),
                ),
            ),
        )
        container.fakeCloudClient.jobs[itemId] = gen1Manifest

        // 3. Sync completed to Room
        val synced = container.cloudSyncRepository.syncCompletedJobs()
        assertEquals(1, synced)
        var item = container.savedItemRepository.getItem(itemId)!!
        assertEquals(ProcessingStatus.COMPLETED, item.processingStatus)
        assertEquals(StageStatus.SUCCEEDED, item.analysisStatus)
        assertTrue(container.database.processingDao().resultsFor(itemId).isNotEmpty())

        // 4. Request full reprocessing from scratch
        val reprocessResult = container.cloudSyncRepository.reprocessItemFromScratch(itemId)
        assertTrue(reprocessResult.isSuccess)

        // 5. Verify remote Drive manifest in fakeCloudClient
        val gen2Manifest = container.fakeCloudClient.jobs[itemId]!!
        assertEquals(2, gen2Manifest.generation)
        assertEquals("QUEUED", gen2Manifest.status)
        assertNull(gen2Manifest.media) // Fresh acquisition from scratch
        assertEquals("PENDING", gen2Manifest.stages.acquisition)
        assertEquals("PENDING", gen2Manifest.stages.audioExtraction)
        assertEquals("PENDING", gen2Manifest.stages.transcription)
        assertEquals("PENDING", gen2Manifest.stages.geminiAnalysis)
        assertEquals(1, gen2Manifest.historicalGenerations.size)

        // 6. Verify Android Room state reset
        item = container.savedItemRepository.getItem(itemId)!!
        assertEquals(ProcessingStatus.QUEUED, item.processingStatus)
        assertEquals(StageStatus.PENDING, item.audioExtractionStatus)
        assertEquals(StageStatus.PENDING, item.transcriptionStatus)
        assertEquals(StageStatus.PENDING, item.analysisStatus)
        assertTrue("Previous generation results must be cleared in Room", container.database.processingDao().resultsFor(itemId).isEmpty())
    }

    @Test
    fun reprocessItemFromScratchIsIdempotentWhenAlreadyQueuedOrProcessing() = runTest {
        val link = InstagramLink(
            originalUrl = "https://www.instagram.com/p/Dfe1veLx4OP/",
            normalizedUrl = "https://www.instagram.com/p/Dfe1veLx4OP",
            shortcode = "Dfe1veLx4OP",
            contentType = ContentType.REEL,
        )
        val outcome = container.savedItemRepository.saveLink(link, source = SaveSource.SHARE)
        val itemId = (outcome as LinkSaveOutcome.Saved).itemId

        // Already QUEUED in generation 2
        val gen2Queued = CloudJobManifest(
            itemId = itemId,
            source = "SHARE",
            originalUrl = link.originalUrl,
            status = "QUEUED",
            generation = 2,
            recordRevision = 4L,
            createdAt = container.clock.now(),
            updatedAt = container.clock.now(),
        )
        container.fakeCloudClient.jobs[itemId] = gen2Queued

        val res = container.cloudSyncRepository.reprocessItemFromScratch(itemId)
        assertTrue(res.isSuccess)
        // Generation must remain 2, not incremented to 3
        assertEquals(2, container.fakeCloudClient.jobs[itemId]!!.generation)
    }

    @Test
    fun reprocessItemFromScratchPreservesRoomWhenDriveSubmissionFails() = runTest {
        val link = InstagramLink(
            originalUrl = "https://www.instagram.com/p/fail/",
            normalizedUrl = "https://www.instagram.com/p/fail",
            shortcode = "fail",
            contentType = ContentType.REEL,
        )
        val outcome = container.savedItemRepository.saveLink(link, source = SaveSource.SHARE)
        val itemId = (outcome as LinkSaveOutcome.Saved).itemId

        val gen1Manifest = CloudJobManifest(
            itemId = itemId,
            source = "SHARE",
            originalUrl = link.originalUrl,
            status = "COMPLETED",
            generation = 1,
            stages = CloudStageStates(
                acquisition = "SKIPPED",
                audioExtraction = "SKIPPED",
                transcription = "SKIPPED",
                geminiAnalysis = "SKIPPED",
            ),
            createdAt = container.clock.now(),
            updatedAt = container.clock.now(),
        )
        container.fakeCloudClient.jobs[itemId] = gen1Manifest
        container.cloudSyncRepository.syncCompletedJobs()

        // Simulate submission failure
        container.fakeCloudClient.submitResult = false
        val res = container.cloudSyncRepository.reprocessItemFromScratch(itemId)
        assertFalse(res.isSuccess)

        // Room must NOT be mutated to QUEUED
        val item = container.savedItemRepository.getItem(itemId)!!
        assertEquals(ProcessingStatus.COMPLETED, item.processingStatus)
    }

    @Test
    fun concurrentReprocessRequestsSerializedByMutex() = runTest {
        val link = InstagramLink(
            originalUrl = "https://www.instagram.com/p/concurrent/",
            normalizedUrl = "https://www.instagram.com/p/concurrent",
            shortcode = "concurrent",
            contentType = ContentType.REEL,
        )
        val outcome = container.savedItemRepository.saveLink(link, source = SaveSource.SHARE)
        val itemId = (outcome as LinkSaveOutcome.Saved).itemId

        val gen1Manifest = CloudJobManifest(
            itemId = itemId,
            source = "SHARE",
            originalUrl = link.originalUrl,
            status = "COMPLETED",
            generation = 1,
            etag = "mock_etag_gen1",
            stages = CloudStageStates(
                acquisition = "SKIPPED",
                audioExtraction = "SKIPPED",
                transcription = "SKIPPED",
                geminiAnalysis = "SKIPPED",
            ),
            createdAt = container.clock.now(),
            updatedAt = container.clock.now(),
        )
        container.fakeCloudClient.jobs[itemId] = gen1Manifest
        container.cloudSyncRepository.syncCompletedJobs()

        // Launch concurrent reprocess calls simultaneously within a coroutine scope
        val results = coroutineScope {
            (1..5).map {
                async {
                    container.cloudSyncRepository.reprocessItemFromScratch(itemId)
                }
            }.awaitAll()
        }

        // All should succeed (first one performs reprocess, subsequent ones hit idempotent guard)
        assertTrue(results.all { it.isSuccess })

        // Generation must be exactly 2, not incremented repeatedly
        val finalManifest = container.fakeCloudClient.jobs[itemId]!!
        assertEquals(2, finalManifest.generation)
        assertEquals("QUEUED", finalManifest.status)

        // Room must be QUEUED
        val item = container.savedItemRepository.getItem(itemId)!!
        assertEquals(ProcessingStatus.QUEUED, item.processingStatus)
    }

    @Test
    fun processRecreationIdempotencyReconcilesRoomWhenManifestAlreadyQueued() = runTest {
        val link = InstagramLink(
            originalUrl = "https://www.instagram.com/p/process-recreate/",
            normalizedUrl = "https://www.instagram.com/p/process-recreate",
            shortcode = "process-recreate",
            contentType = ContentType.REEL,
        )
        val outcome = container.savedItemRepository.saveLink(link, source = SaveSource.SHARE)
        val itemId = (outcome as LinkSaveOutcome.Saved).itemId

        // Simulate state where remote Drive already has Generation 2 QUEUED (from previous process),
        // but local Room still shows previous COMPLETED state due to process recreation before Room update.
        val gen2Queued = CloudJobManifest(
            itemId = itemId,
            source = "SHARE",
            originalUrl = link.originalUrl,
            status = "QUEUED",
            generation = 2,
            etag = "etag-gen2",
            createdAt = container.clock.now(),
            updatedAt = container.clock.now(),
        )
        container.fakeCloudClient.jobs[itemId] = gen2Queued

        val res = container.cloudSyncRepository.reprocessItemFromScratch(itemId)
        assertTrue(res.isSuccess)

        // Generation must remain 2
        val manifest = container.fakeCloudClient.jobs[itemId]!!
        assertEquals(2, manifest.generation)

        // Room must be reconciled to QUEUED
        val item = container.savedItemRepository.getItem(itemId)!!
        assertEquals(ProcessingStatus.QUEUED, item.processingStatus)
    }

    @Test
    fun reprocessItemFailsSafelyWhenEtagMismatchOccurs() = runTest {
        val link = InstagramLink(
            originalUrl = "https://www.instagram.com/p/etag-mismatch/",
            normalizedUrl = "https://www.instagram.com/p/etag-mismatch",
            shortcode = "etag-mismatch",
            contentType = ContentType.REEL,
        )
        val outcome = container.savedItemRepository.saveLink(link, source = SaveSource.SHARE)
        val itemId = (outcome as LinkSaveOutcome.Saved).itemId

        val gen1Manifest = CloudJobManifest(
            itemId = itemId,
            source = "SHARE",
            originalUrl = link.originalUrl,
            status = "COMPLETED",
            generation = 1,
            etag = "stale_etag_v1",
            stages = CloudStageStates(
                acquisition = "SKIPPED",
                audioExtraction = "SKIPPED",
                transcription = "SKIPPED",
                geminiAnalysis = "SKIPPED",
            ),
            createdAt = container.clock.now(),
            updatedAt = container.clock.now(),
        )
        container.fakeCloudClient.jobs[itemId] = gen1Manifest
        container.cloudSyncRepository.syncCompletedJobs()

        // Simulate another client concurrently updating the remote manifest's ETag right after Android fetched it
        container.fakeCloudClient.onBeforeSubmit = {
            val curr = container.fakeCloudClient.jobs[itemId]!!
            container.fakeCloudClient.jobs[itemId] = curr.copy(etag = "newer_concurrent_etag_v2")
        }

        val res = container.cloudSyncRepository.reprocessItemFromScratch(itemId)
        assertFalse("Must fail safely when remote ETag changed concurrently", res.isSuccess)

        // Room must remain unchanged
        val item = container.savedItemRepository.getItem(itemId)!!
        assertEquals(ProcessingStatus.COMPLETED, item.processingStatus)
    }
}
