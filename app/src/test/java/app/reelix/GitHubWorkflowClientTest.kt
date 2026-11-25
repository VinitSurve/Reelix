package app.reelix

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.reelix.cloud.GitHubWorkflowClient
import app.reelix.cloud.WorkflowDispatchResult
import app.reelix.cloud.WorkflowRunInfo
import app.reelix.ui.screens.settings.SettingsViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.robolectric.shadows.ShadowLooper

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GitHubWorkflowClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: GitHubWorkflowClient
    private lateinit var context: Context
    private lateinit var container: TestAppContainer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val okHttpClient = OkHttpClient.Builder().build()
        client = GitHubWorkflowClient(okHttpClient)
        context = ApplicationProvider.getApplicationContext()
        container = TestAppContainer(context = context)
    }

    @After
    fun tearDown() {
        server.shutdown()
        container.close()
    }

    @Test
    fun `dispatchWorkflow sends correct URL, headers, and body for HTTP 204`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        // Run info response for fetchLatestRun
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                        "total_count": 1,
                        "workflow_runs": [
                            {
                                "id": 45555103536,
                                "html_url": "https://github.com/VinitSurve/Reelix/actions/runs/45555103536",
                                "status": "queued",
                                "conclusion": null,
                                "created_at": "2026-11-25T05:39:35Z"
                            }
                        ]
                    }
                    """.trimIndent()
                )
        )

        val serverClient = GitHubWorkflowClient(
            httpClient = OkHttpClient(),
            baseUrl = server.url("/").toString(),
        )

        val result = serverClient.dispatchWorkflow(
            repo = "VinitSurve/Reelix",
            workflowId = "process-queue.yml",
            ref = "main",
            token = "ghp_validToken12345",
        )

        assertTrue(result is WorkflowDispatchResult.Success)
        val success = result as WorkflowDispatchResult.Success
        assertNotNull(success.runInfo)
        assertEquals(45555103536L, success.runInfo?.id)
        assertEquals("queued", success.runInfo?.status)

        // Verify request 1 (dispatch POST)
        val req1 = server.takeRequest()
        assertEquals("POST", req1.method)
        assertEquals("/repos/VinitSurve/Reelix/actions/workflows/process-queue.yml/dispatches", req1.path)
        assertEquals("Bearer ghp_validToken12345", req1.getHeader("Authorization"))
        assertEquals("application/vnd.github+json", req1.getHeader("Accept"))
        assertEquals("2022-11-28", req1.getHeader("X-GitHub-Api-Version"))
        assertEquals("{\"ref\":\"main\"}", req1.body.readUtf8())

        // Verify request 2 (fetch latest run GET)
        val req2 = server.takeRequest()
        assertEquals("GET", req2.method)
        assertEquals("/repos/VinitSurve/Reelix/actions/workflows/process-queue.yml/runs?per_page=1", req2.path)
        assertEquals("Bearer ghp_validToken12345", req2.getHeader("Authorization"))
    }

    @Test
    fun `dispatchWorkflow returns failure on blank token without network request`() = runTest {
        val result = client.dispatchWorkflow("VinitSurve/Reelix", "process-queue.yml", "main", "   ")
        assertTrue(result is WorkflowDispatchResult.Failure)
        assertEquals(401, (result as WorkflowDispatchResult.Failure).statusCode)
        assertEquals("GitHub Personal Access Token is required.", result.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `dispatchWorkflow returns failure on invalid repository format`() = runTest {
        val result = client.dispatchWorkflow("InvalidRepoFormat", "process-queue.yml", "main", "token123")
        assertTrue(result is WorkflowDispatchResult.Failure)
        assertTrue((result as WorkflowDispatchResult.Failure).message.contains("Invalid repository format"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `dispatchWorkflow returns failure on HTTP 401 unauthorized`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"message\":\"Bad credentials\"}"))

        val serverClient = GitHubWorkflowClient(
            httpClient = OkHttpClient(),
            baseUrl = server.url("/").toString(),
        )

        val result = serverClient.dispatchWorkflow(
            repo = "VinitSurve/Reelix",
            workflowId = "process-queue.yml",
            ref = "main",
            token = "ghp_expiredToken",
        )

        assertTrue(result is WorkflowDispatchResult.Failure)
        val failure = result as WorkflowDispatchResult.Failure
        assertEquals(401, failure.statusCode)
        assertTrue(failure.message.contains("Invalid or expired GitHub Personal Access Token"))
    }

    @Test
    fun `dispatchWorkflow returns failure on HTTP 403 missing permission`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("{\"message\":\"Resource not accessible by personal access token\"}"))

        val serverClient = GitHubWorkflowClient(
            httpClient = OkHttpClient(),
            baseUrl = server.url("/").toString(),
        )

        val result = serverClient.dispatchWorkflow(
            repo = "VinitSurve/Reelix",
            workflowId = "process-queue.yml",
            ref = "main",
            token = "ghp_readOnlyToken",
        )

        assertTrue(result is WorkflowDispatchResult.Failure)
        val failure = result as WorkflowDispatchResult.Failure
        assertEquals(403, failure.statusCode)
        assertTrue(failure.message.contains("actions:write"))
    }

    @Test
    fun `SettingsViewModel runQueueNow requires token when cloud processing enabled`() = runTest {
        container.fakeSettings.update {
            it.copy(
                cloudProcessingEnabled = true,
                hasCloudDriveToken = true,
            )
        }
        val viewModel = SettingsViewModel(
            settingsRepository = container.settingsRepository,
            savedItemRepository = container.savedItemRepository,
            scheduler = container.processingScheduler,
            storageInspector = container.storageInspector,
            modelManager = container.transcriptionModelManager,
            cloudSyncRepository = null,
            gitHubWorkflowClient = client,
            appContext = context,
        )

        viewModel.runQueueNow()
        ShadowLooper.idleMainLooper()

        val feedbackMsg = viewModel.feedback.first { it != null }
        assertEquals(
            "GitHub Personal Access Token required to dispatch cloud worker. Please configure your token in Cloud settings.",
            feedbackMsg,
        )
    }

    @Test
    fun `SettingsViewModel runQueueNow dispatches workflow when token is configured`() = runTest {
        container.fakeSettings.update {
            it.copy(
                cloudProcessingEnabled = true,
                hasCloudDriveToken = true,
            )
        }
        container.fakeSettings.setGitHubToken("ghp_testToken1234567890")

        var dispatchedRepo = ""
        var dispatchedWorkflow = ""
        var dispatchedRef = ""
        var dispatchedToken = ""

        val mockClient = object : GitHubWorkflowClient(OkHttpClient()) {
            override suspend fun dispatchWorkflow(
                repo: String,
                workflowId: String,
                ref: String,
                token: String,
            ): WorkflowDispatchResult {
                dispatchedRepo = repo
                dispatchedWorkflow = workflowId
                dispatchedRef = ref
                dispatchedToken = token
                return WorkflowDispatchResult.Success(
                    WorkflowRunInfo(
                        id = 99887766L,
                        htmlUrl = "https://github.com/VinitSurve/Reelix/actions/runs/99887766",
                        status = "queued",
                        conclusion = null,
                        createdAt = "2026-11-25T05:00:00Z",
                    )
                )
            }
        }

        val viewModel = SettingsViewModel(
            settingsRepository = container.settingsRepository,
            savedItemRepository = container.savedItemRepository,
            scheduler = container.processingScheduler,
            storageInspector = container.storageInspector,
            modelManager = container.transcriptionModelManager,
            cloudSyncRepository = null,
            gitHubWorkflowClient = mockClient,
            appContext = context,
        )

        viewModel.runQueueNow()
        ShadowLooper.idleMainLooper()

        val feedbackMsg = viewModel.feedback.first { it != null }
        assertEquals("VinitSurve/Reelix", dispatchedRepo)
        assertEquals("process-queue.yml", dispatchedWorkflow)
        assertEquals("main", dispatchedRef)
        assertEquals("ghp_testToken1234567890", dispatchedToken)
        assertEquals("Cloud worker dispatched: Run #99887766 (queued)", feedbackMsg)
        assertEquals(99887766L, container.fakeSettings.current().lastDispatchedRunId)
        assertEquals("https://github.com/VinitSurve/Reelix/actions/runs/99887766", container.fakeSettings.current().lastDispatchedRunUrl)
    }

    @Test
    fun `SettingsViewModel runQueueNow reports genuine error when dispatch fails with 403`() = runTest {
        container.fakeSettings.update {
            it.copy(
                cloudProcessingEnabled = true,
                hasCloudDriveToken = true,
            )
        }
        container.fakeSettings.setGitHubToken("ghp_readOnlyToken")

        val mockClient = object : GitHubWorkflowClient(OkHttpClient()) {
            override suspend fun dispatchWorkflow(
                repo: String,
                workflowId: String,
                ref: String,
                token: String,
            ): WorkflowDispatchResult {
                return WorkflowDispatchResult.Failure(
                    "GitHub token lacks required 'actions:write' permission (HTTP 403).",
                    403,
                )
            }
        }

        val viewModel = SettingsViewModel(
            settingsRepository = container.settingsRepository,
            savedItemRepository = container.savedItemRepository,
            scheduler = container.processingScheduler,
            storageInspector = container.storageInspector,
            modelManager = container.transcriptionModelManager,
            cloudSyncRepository = null,
            gitHubWorkflowClient = mockClient,
            appContext = context,
        )

        viewModel.runQueueNow()
        ShadowLooper.idleMainLooper()

        val feedbackMsg = viewModel.feedback.first { it != null }
        assertEquals(
            "Failed to dispatch cloud worker: GitHub token lacks required 'actions:write' permission (HTTP 403).",
            feedbackMsg,
        )
    }

    @Test
    fun `SettingsViewModel runQueueNow runs local processing when cloud offloading is disabled`() = runTest {
        container.fakeSettings.update {
            it.copy(
                cloudProcessingEnabled = false,
            )
        }
        val viewModel = SettingsViewModel(
            settingsRepository = container.settingsRepository,
            savedItemRepository = container.savedItemRepository,
            scheduler = container.processingScheduler,
            storageInspector = container.storageInspector,
            modelManager = container.transcriptionModelManager,
            cloudSyncRepository = null,
            gitHubWorkflowClient = client,
            appContext = context,
        )

        viewModel.runQueueNow()
        ShadowLooper.idleMainLooper()

        val feedbackMsg = viewModel.feedback.first { it != null }
        assertEquals("Background processing triggered locally.", feedbackMsg)
    }
}
