package app.reelix

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import app.reelix.core.url.InstagramUrlParser
import app.reelix.domain.model.InstagramLink
import app.reelix.domain.model.LinkSaveOutcome
import app.reelix.domain.model.ProcessingStage
import app.reelix.domain.model.ProcessingStatus
import app.reelix.domain.model.StageStatus
import app.reelix.domain.model.TagSource
import app.reelix.integrations.analysis.AnalysisInput
import app.reelix.integrations.analysis.AnalysisOutcome
import app.reelix.integrations.analysis.AnalysisProviderResolver
import app.reelix.integrations.analysis.AnalysisResult
import app.reelix.integrations.analysis.ConnectionTestResult
import app.reelix.integrations.analysis.ContentAnalysisProvider
import app.reelix.integrations.analysis.OpenAiCompatibleAnalysisProvider
import app.reelix.integrations.metadata.ExtractedMetadata
import app.reelix.integrations.metadata.MetadataExtractor
import app.reelix.integrations.metadata.MetadataOutcome
import app.reelix.integrations.metadata.OpenGraphMetadataExtractor
import app.reelix.integrations.metadata.PageResponse
import app.reelix.processing.BackoffPolicy
import app.reelix.processing.ProcessingWorker
import app.reelix.processing.ReelixWorkerFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
class ProcessingPipelineAndWorkerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun urlOnlyFallbackAndMissingAiProviderStillCompletesAndPreservesItem() = runTest {
        val container = TestAppContainer(
            context = context,
            metadataExtractor = object : MetadataExtractor {
                override val id = "none"
                override suspend fun extract(link: InstagramLink): MetadataOutcome =
                    MetadataOutcome.NotAvailable("Login wall encountered")
            },
            analysisResolver = AnalysisProviderResolver { null },
        )
        try {
            val link = InstagramUrlParser.parse("https://www.instagram.com/reel/UrlOnly99/")!!
            val saved = container.savedItemRepository.saveLink(link) as LinkSaveOutcome.Saved

            val batch = container.pipeline.runBatch(limit = 5)
            assertEquals(1, batch.processed)
            assertEquals(1, batch.completed)

            val details = container.savedItemRepository.observeDetails(saved.itemId).first()!!
            assertEquals(ProcessingStatus.COMPLETED, details.processingStatus)
            assertEquals("https://www.instagram.com/reel/UrlOnly99/", details.originalUrl)
            assertEquals(StageStatus.UNAVAILABLE, details.extractionStatus)
            assertEquals(StageStatus.UNAVAILABLE, details.transcriptionStatus)
            assertEquals(StageStatus.NOT_CONFIGURED, details.analysisStatus)
            // No fake summary or transcript fabricated!
            assertNull(details.aiResult)
            assertNull(details.generatedTranscript)
        } finally {
            container.close()
        }
    }

    @Test
    fun successfulMetadataAndAiEnrichmentGeneratesSummaryAndTagsIdempotently() = runTest {
        var metadataCallCount = 0
        var analysisCallCount = 0

        val container = TestAppContainer(
            context = context,
            metadataExtractor = object : MetadataExtractor {
                override val id = "test-og"
                override suspend fun extract(link: InstagramLink): MetadataOutcome {
                    metadataCallCount++
                    return MetadataOutcome.Found(
                        ExtractedMetadata(
                            title = "Compose Tips on Instagram",
                            caption = "Remember state hoisting and immutable UI models in Jetpack Compose! #compose #androiddev",
                            authorName = "Compose Tips",
                            imageUrl = "https://cdn.example.com/preview.jpg",
                        ),
                    )
                }
            },
            analysisResolver = AnalysisProviderResolver {
                object : ContentAnalysisProvider {
                    override val id = "test-llm"
                    override val displayName = "Test LLM"
                    override suspend fun analyze(input: AnalysisInput): AnalysisOutcome {
                        analysisCallCount++
                        return AnalysisOutcome.Success(
                            AnalysisResult(
                                summary = "Explains state hoisting and immutable UI models in Jetpack Compose.",
                                detailedSummary = "Detailed guide on managing Compose UI state with state hoisting.",
                                mainTopic = "Jetpack Compose State Hoisting",
                                keywords = listOf("state hoisting", "immutable"),
                                tags = listOf("jetpack", "ui"),
                                category = "Android",
                                keyTakeaways = listOf("Hoist state to callers", "Keep UI state immutable"),
                                importantConcepts = listOf("Unidirectional data flow", "State hoisting"),
                                actionableSteps = listOf("Pass states down", "Pass events up"),
                                modelName = "llama3.2-local",
                            ),
                        )
                    }
                    override suspend fun testConnection(): ConnectionTestResult =
                        ConnectionTestResult.Success("OK", listOf("llama3.2-local"))
                }
            },
        )
        try {
            val link = InstagramUrlParser.parse("https://www.instagram.com/reel/EnrichSuccess1/")!!
            val saved = container.savedItemRepository.saveLink(link) as LinkSaveOutcome.Saved

            val batch = container.pipeline.runBatch(limit = 5)
            assertEquals(1, batch.completed)
            assertEquals(1, metadataCallCount)
            assertEquals(1, analysisCallCount)

            val details = container.savedItemRepository.observeDetails(saved.itemId).first()!!
            assertEquals(ProcessingStatus.COMPLETED, details.processingStatus)
            assertEquals("Explains state hoisting and immutable UI models in Jetpack Compose.", details.aiResult?.summary)
            assertEquals("llama3.2-local", details.aiResult?.modelName)

            val tagMap = details.tags.associateBy { it.name }
            assertEquals(TagSource.EXTRACTED, tagMap["compose"]?.source)
            assertEquals(TagSource.EXTRACTED, tagMap["androiddev"]?.source)
            assertEquals(TagSource.AI, tagMap["jetpack"]?.source)
            assertEquals(TagSource.AI, tagMap["ui"]?.source)

            // Idempotency: running batch again or processItem again does ZERO duplicate downloads/analysis
            val secondBatch = container.pipeline.runBatch(limit = 5)
            assertEquals(0, secondBatch.processed)
            assertNull(container.pipeline.processItem(saved.itemId))
            assertEquals(1, metadataCallCount)
            assertEquals(1, analysisCallCount)
        } finally {
            container.close()
        }
    }

    @Test
    fun transientFailureRetriesWithExponentialBackoffAndPreservesEarlierStages() = runTest {
        var metadataCalls = 0
        var analysisCalls = 0
        var failAnalysisTransiently = true

        val backoff = BackoffPolicy(baseDelayMs = 10_000L, maxDelayMs = 120_000L, maxAttempts = 3)
        assertEquals(10_000L, backoff.delayFor(1))
        assertEquals(20_000L, backoff.delayFor(2))
        assertEquals(40_000L, backoff.delayFor(3))

        val container = TestAppContainer(
            context = context,
            backoffPolicy = backoff,
            metadataExtractor = object : MetadataExtractor {
                override val id = "og"
                override suspend fun extract(link: InstagramLink): MetadataOutcome {
                    metadataCalls++
                    return MetadataOutcome.Found(
                        ExtractedMetadata(
                            title = "Coroutines Deep Dive",
                            caption = "Structured concurrency prevents leaked background tasks in Kotlin applications.",
                            authorName = "kotlin_author",
                            imageUrl = null,
                        ),
                    )
                }
            },
            analysisResolver = AnalysisProviderResolver {
                object : ContentAnalysisProvider {
                    override val id = "flaky-llm"
                    override val displayName = "Flaky LLM"
                    override suspend fun analyze(input: AnalysisInput): AnalysisOutcome {
                        analysisCalls++
                        return if (failAnalysisTransiently) {
                            AnalysisOutcome.TransientFailure("HTTP 503 Service Unavailable")
                        } else {
                            AnalysisOutcome.Success(
                                AnalysisResult(
                                    summary = "Structured concurrency avoids task leaks.",
                                    detailedSummary = "Detailed guide on structured concurrency.",
                                    mainTopic = "Structured Concurrency",
                                    keywords = listOf("coroutines"),
                                    tags = listOf("coroutines"),
                                    category = "Kotlin",
                                    keyTakeaways = listOf("Use coroutineScope"),
                                    importantConcepts = listOf("Job hierarchy"),
                                    actionableSteps = listOf("Cancel child jobs"),
                                    modelName = "test-model",
                                ),
                            )
                        }
                    }
                    override suspend fun testConnection(): ConnectionTestResult =
                        ConnectionTestResult.Success("OK", listOf("test-model"))
                }
            },
        )
        try {
            val link = InstagramUrlParser.parse("https://www.instagram.com/reel/BackoffRetry1/")!!
            val saved = container.savedItemRepository.saveLink(link) as LinkSaveOutcome.Saved

            // Run 1: Metadata succeeds, Analysis fails transiently -> WAITING_RETRY at +10s
            val run1 = container.pipeline.runBatch(5)
            assertEquals(1, run1.retriesScheduled)
            assertEquals(1, metadataCalls)
            assertEquals(1, analysisCalls)

            val afterRun1 = container.savedItemRepository.observeDetails(saved.itemId).first()!!
            assertEquals(ProcessingStatus.WAITING_RETRY, afterRun1.processingStatus)
            // Earlier metadata stage output is intact!
            assertEquals("Coroutines Deep Dive", afterRun1.extractedTitle)

            // Running before backoff elapses skips the item
            assertEquals(0, container.pipeline.runBatch(5).processed)

            // Advance 10s -> Run 2: Metadata is NOT re-fetched (idempotent!), Analysis retried and fails (#2 -> +20s)
            container.clock.advanceBy(10_000L)
            val run2 = container.pipeline.runBatch(5)
            assertEquals(1, run2.retriesScheduled)
            assertEquals(1, metadataCalls) // Still 1!
            assertEquals(2, analysisCalls)

            // Provider recovers before attempt 3 (+20s)
            failAnalysisTransiently = false
            container.clock.advanceBy(20_000L)
            val run3 = container.pipeline.runBatch(5)
            assertEquals(1, run3.completed)
            assertEquals(1, metadataCalls) // Never repeated
            assertEquals(3, analysisCalls)

            val finalDetails = container.savedItemRepository.observeDetails(saved.itemId).first()!!
            assertEquals(ProcessingStatus.COMPLETED, finalDetails.processingStatus)
            assertEquals("Structured concurrency avoids task leaks.", finalDetails.aiResult?.summary)
        } finally {
            container.close()
        }
    }

    @Test
    fun permanentFailureMarksNeedsAttentionWithoutDiscardingSavedUrlOrMetadata() = runTest {
        val container = TestAppContainer(
            context = context,
            metadataExtractor = object : MetadataExtractor {
                override val id = "og"
                override suspend fun extract(link: InstagramLink): MetadataOutcome =
                    MetadataOutcome.Found(
                        ExtractedMetadata(
                            title = "Preserved Title",
                            caption = "Enough caption text so analysis stage runs and encounters auth error 401.",
                            authorName = null,
                            imageUrl = null,
                        ),
                    )
            },
            analysisResolver = AnalysisProviderResolver {
                object : ContentAnalysisProvider {
                    override val id = "bad-auth"
                    override val displayName = "Bad Auth"
                    override suspend fun analyze(input: AnalysisInput): AnalysisOutcome =
                        AnalysisOutcome.PermanentFailure("Provider rejected credentials (HTTP 401)")
                    override suspend fun testConnection(): ConnectionTestResult =
                        ConnectionTestResult.Failure("HTTP 401 Unauthorized")
                }
            },
        )
        try {
            val link = InstagramUrlParser.parse("https://www.instagram.com/reel/PermFail1/")!!
            val saved = container.savedItemRepository.saveLink(link) as LinkSaveOutcome.Saved

            val batch = container.pipeline.runBatch(5)
            assertEquals(1, batch.needsAttention)

            val details = container.savedItemRepository.observeDetails(saved.itemId).first()!!
            assertEquals(ProcessingStatus.NEEDS_ATTENTION, details.processingStatus)
            assertEquals("https://www.instagram.com/reel/PermFail1/", details.originalUrl)
            assertEquals("Preserved Title", details.extractedTitle)
            assertTrue(details.lastError!!.contains("401"))
        } finally {
            container.close()
        }
    }

    @Test
    fun appRestartRecoversInterruptedProcessingItemsAndWorkManagerRunsCleanly() = runTest {
        val container = TestAppContainer(context = context)
        try {
            val link = InstagramUrlParser.parse("https://www.instagram.com/reel/CrashRecover1/")!!
            val saved = container.savedItemRepository.saveLink(link) as LinkSaveOutcome.Saved

            // Simulate process death right after claiming item and starting METADATA stage
            assertTrue(container.processingRepository.claim(saved.itemId))
            val jobs = container.processingRepository.ensureJobs(saved.itemId)
            container.processingRepository.settleStage(
                saved.itemId,
                jobs.getValue(ProcessingStage.VALIDATION),
                StageStatus.SUCCEEDED,
                "Validated before crash",
            )
            container.processingRepository.markStageRunning(saved.itemId, jobs.getValue(ProcessingStage.METADATA))
            assertEquals(ProcessingStatus.PROCESSING, container.savedItemRepository.getItem(saved.itemId)!!.processingStatus)

            // Execute real WorkManager ProcessingWorker via TestListenableWorkerBuilder
            val worker = TestListenableWorkerBuilder<ProcessingWorker>(context)
                .setWorkerFactory(
                    ReelixWorkerFactory(
                        pipeline = container.pipeline,
                        settings = container.settingsRepository,
                        scheduler = container.processingScheduler,
                        clock = container.clock,
                    ),
                )
                .build()

            val result = worker.doWork()
            assertEquals(ListenableWorker.Result.success(), result)

            val recovered = container.savedItemRepository.observeDetails(saved.itemId).first()!!
            assertEquals(ProcessingStatus.COMPLETED, recovered.processingStatus)
            assertNotNull(container.settingsRepository.current().lastRunAt)
        } finally {
            container.close()
        }
    }

    @Test
    fun openGraphExtractorRejectsLoginWallsAndParsesLegitimateMeta() = runTest {
        val loginWallExtractor = OpenGraphMetadataExtractor { url ->
            PageResponse(
                code = 200,
                finalUrl = "https://www.instagram.com/accounts/login/?next=/reel/xyz/",
                body = """<html><head><meta property="og:title" content="Login • Instagram"></head></html>""",
            )
        }
        val link = InstagramUrlParser.parse("https://www.instagram.com/reel/LoginWall1/")!!
        assertTrue(loginWallExtractor.extract(link) is MetadataOutcome.NotAvailable)

        val publicExtractor = OpenGraphMetadataExtractor { _ ->
            PageResponse(
                code = 200,
                finalUrl = link.normalizedUrl,
                body = """
                    <html><head>
                    <meta property="og:title" content="Android Weekly on Instagram: &quot;Room FTS Tips&quot;" />
                    <meta property="og:description" content="1,200 likes - Android Weekly on Instagram: &quot;Index captions &amp; notes locally with FTS4 #android #sqlite&quot;" />
                    <meta property="og:image" content="https://scontent.cdninstagram.com/v/preview.jpg" />
                    </head></html>
                """.trimIndent(),
            )
        }
        val found = publicExtractor.extract(link) as MetadataOutcome.Found
        assertEquals("Android Weekly", found.metadata.authorName)
        assertTrue(found.metadata.caption!!.contains("Index captions & notes locally with FTS4 #android #sqlite"))
        assertEquals("https://scontent.cdninstagram.com/v/preview.jpg", found.metadata.imageUrl)
    }

    @Test
    fun openAiCompatibleProviderParsesJsonAndHandlesRateLimitsViaMockWebServer() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = OpenAiCompatibleAnalysisProvider.parseBaseUrl(server.url("/v1").toString())!!
            val provider = OpenAiCompatibleAnalysisProvider(
                client = OkHttpClient(),
                baseUrl = baseUrl,
                model = "llama3.2",
                apiKey = "test-secret",
            )
            val input = AnalysisInput(
                contentType = app.reelix.domain.model.ContentType.REEL,
                title = "SQLite FTS Guide",
                caption = "How to build fast offline search on Android using Room and SQLite FTS virtual tables.",
                transcript = null,
                notes = null,
            )

            // 1. HTTP 429 -> TransientFailure
            server.enqueue(MockResponse().setResponseCode(429))
            assertTrue(provider.analyze(input) is AnalysisOutcome.TransientFailure)

            // 2. Valid JSON response -> Success
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "model": "llama3.2",
                      "choices": [{
                        "message": {
                          "content": "{\"summary\":\"Explains offline full-text search on Android with Room and SQLite FTS.\",\"main_topic\":\"Android SQLite FTS\",\"keywords\":[\"sqlite\",\"fts\",\"room\"],\"tags\":[\"android\",\"search\"],\"category\":\"Mobile Dev\",\"key_takeaways\":[\"Works offline\"]}"
                        }
                      }]
                    }
                    """.trimIndent(),
                ),
            )
            val success = provider.analyze(input) as AnalysisOutcome.Success
            assertEquals("Android SQLite FTS", success.result.mainTopic)
            assertEquals(listOf("android", "search"), success.result.tags)
            assertEquals("Bearer test-secret", server.takeRequest().let { server.takeRequest() }.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }
}

