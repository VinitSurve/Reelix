package app.reelix

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.reelix.data.db.entity.ProcessingResultEntity
import app.reelix.domain.model.ContentType
import app.reelix.domain.model.ItemQuery
import app.reelix.domain.model.ProcessingStage
import app.reelix.integrations.analysis.AnalysisInput
import app.reelix.integrations.analysis.AnalysisOutcome
import app.reelix.integrations.analysis.ConnectionTestResult
import app.reelix.integrations.analysis.OpenAiCompatibleAnalysisProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiEnrichmentAndConnectionTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.start()
        httpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun openAiCompatibleProviderParsesAll9StructuredFieldsCorrectly() = runTest {
        val jsonPayload = """
            {
              "choices": [
                {
                  "message": {
                    "content": "{\n  \"summary\": \"Brief overview of clean architecture in Android.\",\n  \"detailed_summary\": \"Comprehensive breakdown of separation between Domain, Data, and Presentation layers using dependency inversion.\",\n  \"main_topic\": \"Android Clean Architecture\",\n  \"category\": \"Software Engineering\",\n  \"keywords\": [\"clean architecture\", \"use cases\", \"repository pattern\"],\n  \"tags\": [\"android\", \"architecture\", \"kotlin\"],\n  \"key_takeaways\": [\n    \"Entities contain business logic\",\n    \"Use cases represent single domain actions\",\n    \"Repositories abstract data sources\"\n  ],\n  \"important_concepts\": [\n    \"Dependency Rule\",\n    \"Interface Segregation\"\n  ],\n  \"actionable_steps\": [\n    \"Define repository interface in domain layer\",\n    \"Implement repository in data layer\",\n    \"Inject use case into ViewModel\"\n  ]\n}"
                  }
                }
              ]
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(jsonPayload))

        val provider = OpenAiCompatibleAnalysisProvider(
            client = httpClient,
            baseUrl = server.url("/v1"),
            model = "llama3.2",
            apiKey = "test-key",
        )

        val input = AnalysisInput(
            contentType = ContentType.REEL,
            title = "Clean Architecture Guide",
            caption = "How to structure Android apps properly",
            transcript = null,
            notes = null,
        )

        val outcome = provider.analyze(input)
        assertTrue(outcome is AnalysisOutcome.Success)
        val res = (outcome as AnalysisOutcome.Success).result

        assertEquals("Brief overview of clean architecture in Android.", res.summary)
        assertEquals("Comprehensive breakdown of separation between Domain, Data, and Presentation layers using dependency inversion.", res.detailedSummary)
        assertEquals("Android Clean Architecture", res.mainTopic)
        assertEquals("Software Engineering", res.category)
        assertEquals(listOf("clean architecture", "use cases", "repository pattern"), res.keywords)
        assertEquals(listOf("android", "architecture", "kotlin"), res.tags)
        assertEquals(listOf("Entities contain business logic", "Use cases represent single domain actions", "Repositories abstract data sources"), res.keyTakeaways)
        assertEquals(listOf("Dependency Rule", "Interface Segregation"), res.importantConcepts)
        assertEquals(listOf("Define repository interface in domain layer", "Implement repository in data layer", "Inject use case into ViewModel"), res.actionableSteps)
    }

    @Test
    fun testConnectionSucceedsAgainstOpenAiModelsEndpoint() = runTest {
        val modelsJson = """
            {
              "data": [
                {"id": "llama3.2:latest"},
                {"id": "qwen2.5:7b"},
                {"id": "mistral:latest"}
              ]
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(modelsJson))

        val provider = OpenAiCompatibleAnalysisProvider(
            client = httpClient,
            baseUrl = server.url("/v1"),
            model = "llama3.2:latest",
            apiKey = null,
        )

        val testResult = provider.testConnection()
        assertTrue(testResult is ConnectionTestResult.Success)
        val success = testResult as ConnectionTestResult.Success
        assertTrue(success.message.contains("Connected successfully"))
        assertEquals(listOf("llama3.2:latest", "qwen2.5:7b", "mistral:latest"), success.modelsAvailable)
    }

    @Test
    fun testConnectionFallsBackToOllamaNativeApiTags() = runTest {
        // First request to /v1/models returns 404
        server.enqueue(MockResponse().setResponseCode(404))
        // Second request to /api/tags returns Ollama models
        val ollamaTagsJson = """
            {
              "models": [
                {"name": "llama3.2:latest"},
                {"name": "phi3:mini"}
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(ollamaTagsJson))

        val provider = OpenAiCompatibleAnalysisProvider(
            client = httpClient,
            baseUrl = server.url("/v1"),
            model = "llama3.2:latest",
            apiKey = null,
        )

        val testResult = provider.testConnection()
        assertTrue(testResult is ConnectionTestResult.Success)
        val success = testResult as ConnectionTestResult.Success
        assertEquals(listOf("llama3.2:latest", "phi3:mini"), success.modelsAvailable)
    }

    @Test
    fun testConnectionHandlesFailureGracefully() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))
        // Fallback also 500
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        val provider = OpenAiCompatibleAnalysisProvider(
            client = httpClient,
            baseUrl = server.url("/v1"),
            model = "llama3.2",
            apiKey = null,
        )

        val testResult = provider.testConnection()
        assertTrue(testResult is ConnectionTestResult.Failure)
        val failure = testResult as ConnectionTestResult.Failure
        assertTrue(failure.error.contains("500") || failure.error.contains("Could not reach"))
    }

    @Test
    fun offlineFtsIndexesAndFindsDetailedSummaryConceptsAndActionableSteps() = runTest {
        val container = TestAppContainer(context)
        try {
            val link = app.reelix.core.url.InstagramUrlParser.parse("https://www.instagram.com/reel/FtsPhase2Test/")!!
            val saved = container.savedItemRepository.saveLink(link) as app.reelix.domain.model.LinkSaveOutcome.Saved

            // Save AI analysis with detailed summary, concepts, and steps
            container.processingRepository.saveResult(
                ProcessingResultEntity(
                    savedItemId = saved.itemId,
                    stage = ProcessingStage.ANALYSIS,
                    summary = "Short summary of state hoisting.",
                    detailedSummary = "Detailed guide describing unidirectional data flow architectures.",
                    mainTopic = "Compose State Architecture",
                    category = "Development",
                    keywords = "compose, state",
                    suggestedTags = "android",
                    keyTakeaways = "Keep state hoisted",
                    importantConcepts = "Unidirectional Data Flow\nImmutable State Holder",
                    actionableSteps = "Step 1: Extract state hoisting\nStep 2: Emit events upwards",
                    providerId = "test",
                    modelName = "llama3",
                    generatedAt = container.clock.now(),
                ),
            )

            // Search by detailed summary keyword
            val res1 = container.savedItemRepository.search("unidirectional", ItemQuery()).first()
            assertEquals(1, res1.size)
            assertEquals(saved.itemId, res1.first().id)

            // Search by important concept keyword
            val res2 = container.savedItemRepository.search("Immutable", ItemQuery()).first()
            assertEquals(1, res2.size)
            assertEquals(saved.itemId, res2.first().id)

            // Search by actionable step keyword
            val res3 = container.savedItemRepository.search("hoisting", ItemQuery()).first()
            assertEquals(1, res3.size)
            assertEquals(saved.itemId, res3.first().id)
        } finally {
            container.close()
        }
    }
}
