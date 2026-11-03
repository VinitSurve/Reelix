package app.reelix

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.reelix.cloud.DriveAuthOutcome
import app.reelix.cloud.DriveTokenResult
import app.reelix.cloud.GoogleDriveCloudClient
import app.reelix.data.settings.DataStoreSettingsRepository
import app.reelix.data.settings.InMemorySecretStore
import app.reelix.data.settings.settingsDataStore
import app.reelix.ui.screens.settings.SettingsScreen
import app.reelix.ui.screens.settings.SettingsViewModel
import app.reelix.ui.theme.ReelixTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp")
class GoogleDriveAuthTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var container: TestAppContainer
    private lateinit var secretStore: InMemorySecretStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        secretStore = InMemorySecretStore()
        container = TestAppContainer(context = context)
    }

    @After
    fun tearDown() {
        container.close()
    }

    @Test
    fun connectGoogleDrive_immediateSuccess_storesAccessTokenInSecretStore() = runTest {
        val fakeAuthManager = container.fakeGoogleDriveAuthManager
        fakeAuthManager.initialOutcome = DriveAuthOutcome.Success("test_access_token_12345")

        val viewModel = SettingsViewModel(
            settingsRepository = container.settingsRepository,
            savedItemRepository = container.savedItemRepository,
            scheduler = container.processingScheduler,
            storageInspector = container.storageInspector,
            modelManager = container.transcriptionModelManager,
            cloudSyncRepository = container.cloudSyncRepository,
            googleDriveAuthManager = fakeAuthManager,
            appContext = context,
        )

        var launchedResolution = false
        viewModel.connectGoogleDrive { launchedResolution = true }

        assertTrue(fakeAuthManager.startAuthorizationCalled)
        assertFalse(launchedResolution)

        // Verify token is stored in SecretStore under CLOUD_DRIVE_TOKEN_ALIAS
        assertEquals("test_access_token_12345", container.settingsRepository.getValidDriveAccessToken())
        assertTrue(viewModel.settings.first { it.hasCloudDriveToken }.hasCloudDriveToken)
        assertEquals("Connected to Google Drive successfully.", viewModel.feedback.value)
    }

    @Test
    fun connectGoogleDrive_resolutionRequired_launchesIntentSenderRequestWithoutStoringToken() = runTest {
        val fakeAuthManager = container.fakeGoogleDriveAuthManager
        val dummyIntent = Intent("app.reelix.TEST_AUTH")
        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            0,
            dummyIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val dummySenderRequest = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
        fakeAuthManager.initialOutcome = DriveAuthOutcome.ResolutionRequired(dummySenderRequest)

        val viewModel = SettingsViewModel(
            settingsRepository = container.settingsRepository,
            savedItemRepository = container.savedItemRepository,
            scheduler = container.processingScheduler,
            storageInspector = container.storageInspector,
            modelManager = container.transcriptionModelManager,
            cloudSyncRepository = container.cloudSyncRepository,
            googleDriveAuthManager = fakeAuthManager,
            appContext = context,
        )

        var capturedRequest: IntentSenderRequest? = null
        viewModel.connectGoogleDrive { req ->
            capturedRequest = req
        }

        assertTrue(fakeAuthManager.startAuthorizationCalled)
        assertNotNull(capturedRequest)
        assertEquals(dummySenderRequest.intentSender, capturedRequest?.intentSender)

        // Token must NOT be stored yet
        assertNull(container.settingsRepository.getValidDriveAccessToken())
        assertFalse(viewModel.settings.value.hasCloudDriveToken)
    }

    @Test
    fun onAuthorizationResult_success_storesTokenInSecretStore() = runTest {
        val fakeAuthManager = container.fakeGoogleDriveAuthManager
        fakeAuthManager.initialTokenResult = DriveTokenResult.Success("resolved_access_token_999")

        val viewModel = SettingsViewModel(
            settingsRepository = container.settingsRepository,
            savedItemRepository = container.savedItemRepository,
            scheduler = container.processingScheduler,
            storageInspector = container.storageInspector,
            modelManager = container.transcriptionModelManager,
            cloudSyncRepository = container.cloudSyncRepository,
            googleDriveAuthManager = fakeAuthManager,
            appContext = context,
        )

        val resultIntent = Intent().apply { putExtra("auth_result", "ok") }
        viewModel.onAuthorizationResult(Activity.RESULT_OK, resultIntent)

        assertTrue(fakeAuthManager.extractTokenCalled)
        assertEquals("resolved_access_token_999", container.settingsRepository.getValidDriveAccessToken())
        assertTrue(viewModel.settings.first { it.hasCloudDriveToken }.hasCloudDriveToken)
        assertEquals("Connected to Google Drive successfully.", viewModel.feedback.value)
    }

    @Test
    fun onAuthorizationResult_userCancelled_doesNotStoreTokenAndReportsCancelled() = runTest {
        val fakeAuthManager = container.fakeGoogleDriveAuthManager
        fakeAuthManager.initialTokenResult = DriveTokenResult.Cancelled

        val viewModel = SettingsViewModel(
            settingsRepository = container.settingsRepository,
            savedItemRepository = container.savedItemRepository,
            scheduler = container.processingScheduler,
            storageInspector = container.storageInspector,
            modelManager = container.transcriptionModelManager,
            cloudSyncRepository = container.cloudSyncRepository,
            googleDriveAuthManager = fakeAuthManager,
            appContext = context,
        )

        viewModel.onAuthorizationResult(Activity.RESULT_CANCELED, null)

        assertTrue(fakeAuthManager.extractTokenCalled)
        assertNull(container.settingsRepository.getValidDriveAccessToken())
        assertFalse(viewModel.settings.value.hasCloudDriveToken)
        assertEquals("Google Drive authorization was cancelled.", viewModel.feedback.value)
    }

    @Test
    fun onAuthorizationResult_error_reportsFailureMessageWithoutStoringToken() = runTest {
        val fakeAuthManager = container.fakeGoogleDriveAuthManager
        fakeAuthManager.initialTokenResult = DriveTokenResult.Error("Network error contacting Google servers")

        val viewModel = SettingsViewModel(
            settingsRepository = container.settingsRepository,
            savedItemRepository = container.savedItemRepository,
            scheduler = container.processingScheduler,
            storageInspector = container.storageInspector,
            modelManager = container.transcriptionModelManager,
            cloudSyncRepository = container.cloudSyncRepository,
            googleDriveAuthManager = fakeAuthManager,
            appContext = context,
        )

        viewModel.onAuthorizationResult(Activity.RESULT_OK, Intent())

        assertTrue(fakeAuthManager.extractTokenCalled)
        assertNull(container.settingsRepository.getValidDriveAccessToken())
        assertFalse(viewModel.settings.value.hasCloudDriveToken)
        assertEquals("Google Drive authorization failed: Network error contacting Google servers", viewModel.feedback.value)
    }

    @Test
    fun disconnectGoogleDrive_clearsTokenFromSecretStoreAndUpdatesSettings() = runTest {
        val settingsRepo = container.settingsRepository
        settingsRepo.saveCloudDriveAccessToken("active_drive_token_to_clear")
        assertEquals("active_drive_token_to_clear", settingsRepo.getValidDriveAccessToken())

        val viewModel = SettingsViewModel(
            settingsRepository = settingsRepo,
            savedItemRepository = container.savedItemRepository,
            scheduler = container.processingScheduler,
            storageInspector = container.storageInspector,
            modelManager = container.transcriptionModelManager,
            cloudSyncRepository = container.cloudSyncRepository,
            googleDriveAuthManager = container.fakeGoogleDriveAuthManager,
            appContext = context,
        )

        viewModel.disconnectGoogleDrive()

        assertNull(settingsRepo.getValidDriveAccessToken())
        assertFalse(viewModel.settings.first { !it.hasCloudDriveToken }.hasCloudDriveToken)
        assertEquals("Google Drive disconnected.", viewModel.feedback.value)
    }

    @Test
    fun silentRefresh_updatesStoredTokenViaSettingsRepository() = runTest {
        val fakeAuthManager = container.fakeGoogleDriveAuthManager
        fakeAuthManager.silentRefreshToken = "refreshed_access_token_abc"

        val repo = DataStoreSettingsRepository(
            dataStore = context.settingsDataStore(),
            secretStore = secretStore,
            httpClient = okhttp3.OkHttpClient(),
            context = context,
            googleDriveAuthManager = fakeAuthManager,
        )

        val refreshed = repo.refreshCloudDriveAccessToken()

        assertTrue(fakeAuthManager.silentRefreshCalled)
        assertEquals("refreshed_access_token_abc", refreshed)
        assertEquals("refreshed_access_token_abc", repo.getValidDriveAccessToken())
    }

    @Test
    fun createReelixVault_success_updatesFolderIdInSettingsAndSurfacesFeedback() = runTest {
        val fakeCloudClient = container.fakeCloudClient
        fakeCloudClient.nextVaultFolderId = "vault_test_folder_id_456"

        val viewModel = SettingsViewModel(
            settingsRepository = container.settingsRepository,
            savedItemRepository = container.savedItemRepository,
            scheduler = container.processingScheduler,
            storageInspector = container.storageInspector,
            modelManager = container.transcriptionModelManager,
            cloudSyncRepository = container.cloudSyncRepository,
            cloudSyncClient = fakeCloudClient,
            googleDriveAuthManager = container.fakeGoogleDriveAuthManager,
            appContext = context,
        )

        viewModel.createReelixVault()

        val updatedSettings = viewModel.settings.first { it.cloudDriveFolderId.isNotBlank() }
        assertEquals("vault_test_folder_id_456", updatedSettings.cloudDriveFolderId)
        assertTrue(updatedSettings.cloudProcessingEnabled)
        val feedbackMsg = viewModel.feedback.first { it != null }
        assertEquals("Reelix Vault ready! Folder ID configured: vault_test_folder_id_456", feedbackMsg)
        assertFalse(viewModel.isCreatingVault.value)
    }

    @Test
    fun createReelixVault_failure_doesNotUpdateFolderIdAndSurfacesErrorMessage() = runTest {
        val fakeCloudClient = container.fakeCloudClient
        fakeCloudClient.createVaultResult = Result.failure(IOException("Server returned 500"))

        val viewModel = SettingsViewModel(
            settingsRepository = container.settingsRepository,
            savedItemRepository = container.savedItemRepository,
            scheduler = container.processingScheduler,
            storageInspector = container.storageInspector,
            modelManager = container.transcriptionModelManager,
            cloudSyncRepository = container.cloudSyncRepository,
            cloudSyncClient = fakeCloudClient,
            googleDriveAuthManager = container.fakeGoogleDriveAuthManager,
            appContext = context,
        )

        viewModel.createReelixVault()

        val feedbackMsg = viewModel.feedback.first { it != null }
        assertEquals("", container.settingsRepository.current().cloudDriveFolderId)
        assertEquals("Failed to create Reelix Vault: Server returned 500", feedbackMsg)
        assertFalse(viewModel.isCreatingVault.value)
    }

    @Test
    fun googleDriveCloudClient_createOrGetVaultFolder_findsExistingVaultWithoutCreatingDuplicate() = runTest {
        var postRequestMade = false
        var getQueryMade = false

        val mockHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                if (request.method == "GET" && url.contains("drive/v3/files")) {
                    getQueryMade = true
                    // Verify Authorization header is sent
                    assertEquals("Bearer test_drive_token_xyz", request.header("Authorization"))
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"files":[{"id":"existing_vault_id_111","name":"Reelix_Vault"}]}""".toResponseBody("application/json".toMediaType()))
                        .build()
                } else if (request.method == "POST") {
                    postRequestMade = true
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"id":"should_not_create"}""".toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    chain.proceed(request)
                }
            }
            .build()

        val client = GoogleDriveCloudClient(
            client = mockHttpClient,
            folderIdProvider = { null },
            tokenProvider = { "test_drive_token_xyz" },
        )

        val result = client.createOrGetVaultFolder()

        assertTrue(result.isSuccess)
        assertEquals("existing_vault_id_111", result.getOrNull())
        assertTrue(getQueryMade)
        assertFalse("Existing folder must be reused without creating a duplicate folder!", postRequestMade)
    }

    @Test
    fun googleDriveCloudClient_createOrGetVaultFolder_createsNewVaultWhenNotFound() = runTest {
        var postRequestMade = false
        var getQueryMade = false
        var postedBody: String? = null

        val mockHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                if (request.method == "GET" && url.contains("drive/v3/files")) {
                    getQueryMade = true
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"files":[]}""".toResponseBody("application/json".toMediaType()))
                        .build()
                } else if (request.method == "POST" && url.contains("drive/v3/files")) {
                    postRequestMade = true
                    assertEquals("Bearer test_drive_token_xyz", request.header("Authorization"))
                    val buffer = okio.Buffer()
                    request.body?.writeTo(buffer)
                    postedBody = buffer.readUtf8()
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"id":"new_vault_id_222","name":"Reelix_Vault"}""".toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    chain.proceed(request)
                }
            }
            .build()

        val client = GoogleDriveCloudClient(
            client = mockHttpClient,
            folderIdProvider = { null },
            tokenProvider = { "test_drive_token_xyz" },
        )

        val result = client.createOrGetVaultFolder()

        assertTrue(result.isSuccess)
        assertEquals("new_vault_id_222", result.getOrNull())
        assertTrue(getQueryMade)
        assertTrue(postRequestMade)
        assertNotNull(postedBody)
        assertTrue(postedBody!!.contains("Reelix_Vault"))
        assertTrue(postedBody!!.replace("\\/", "/").contains("application/vnd.google-apps.folder"))
        assertTrue(postedBody!!.contains("root"))
    }

    @Test
    fun googleDriveCloudClient_createOrGetVaultFolder_tokenRefreshOn401() = runTest {
        var callCount = 0
        var tokenRefreshed = false

        val mockHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                callCount++
                if (callCount == 1) {
                    // First call returns 401 Unauthorized
                    assertEquals("Bearer old_expired_token", request.header("Authorization"))
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(401)
                        .message("Unauthorized")
                        .body("""{"error":{"code":401,"message":"Invalid Credentials"}}""".toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    // Second call with refreshed token succeeds
                    assertEquals("Bearer fresh_refreshed_token", request.header("Authorization"))
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"files":[{"id":"vault_refreshed_333","name":"Reelix_Vault"}]}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }
            .build()

        val client = GoogleDriveCloudClient(
            client = mockHttpClient,
            folderIdProvider = { null },
            tokenProvider = { "old_expired_token" },
            tokenRefresher = {
                tokenRefreshed = true
                "fresh_refreshed_token"
            },
        )

        val result = client.createOrGetVaultFolder()

        assertTrue(result.isSuccess)
        assertEquals("vault_refreshed_333", result.getOrNull())
        assertTrue(tokenRefreshed)
        assertEquals(2, callCount)
    }

    @Test
    fun googleDriveCloudClient_createOrGetVaultFolder_handlesNetworkAndApiErrorsSafely() = runTest {
        // 1. Network failure
        val failingClient = OkHttpClient.Builder()
            .addInterceptor {
                throw IOException("Connection timed out")
            }
            .build()

        val client1 = GoogleDriveCloudClient(
            client = failingClient,
            folderIdProvider = { null },
            tokenProvider = { "secret_token_123" },
        )

        val netResult = client1.createOrGetVaultFolder()
        assertTrue(netResult.isFailure)
        val netMsg = netResult.exceptionOrNull()?.message.orEmpty()
        assertTrue(netMsg.contains("Network error"))
        assertFalse("Access token must never appear in error messages!", netMsg.contains("secret_token_123"))

        // 2. API error 500
        val errorClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Internal Server Error")
                    .body("""{"error":{"code":500,"message":"Internal Drive error occurred"}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val client2 = GoogleDriveCloudClient(
            client = errorClient,
            folderIdProvider = { null },
            tokenProvider = { "secret_token_456" },
        )

        val apiResult = client2.createOrGetVaultFolder()
        assertTrue(apiResult.isFailure)
        val apiMsg = apiResult.exceptionOrNull()?.message.orEmpty()
        assertTrue(apiMsg.contains("Internal Drive error occurred"))
        assertFalse("Access token must never appear in error messages!", apiMsg.contains("secret_token_456"))
    }

    @Test
    fun settingsScreenUi_connectAndDisconnectButtonsFlow() {
        val fakeAuthManager = container.fakeGoogleDriveAuthManager
        fakeAuthManager.initialOutcome = DriveAuthOutcome.Success("ui_flow_token_123")
        container.fakeCloudClient.nextVaultFolderId = "ui_vault_folder_id_789"

        val viewModel = SettingsViewModel(
            settingsRepository = container.settingsRepository,
            savedItemRepository = container.savedItemRepository,
            scheduler = container.processingScheduler,
            storageInspector = container.storageInspector,
            modelManager = container.transcriptionModelManager,
            cloudSyncRepository = container.cloudSyncRepository,
            cloudSyncClient = container.fakeCloudClient,
            googleDriveAuthManager = fakeAuthManager,
            appContext = context,
        )

        composeRule.setContent {
            ReelixTheme {
                SettingsScreen(viewModel = viewModel)
            }
        }

        // 1. Initial state: Connect Google Drive button is displayed
        composeRule.onNodeWithTag("connect_google_drive_button")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Connect Google Drive").assertIsDisplayed()

        // 2. Click Connect Google Drive
        composeRule.onNodeWithTag("connect_google_drive_button").performClick()
        composeRule.waitForIdle()

        // 3. Post-connection state: Connected status and Disconnect button are visible
        composeRule.onNodeWithTag("disconnect_google_drive_button")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Google Drive connected").assertIsDisplayed()
        composeRule.onNodeWithText("Reconnect Google Drive").assertIsDisplayed()

        // 4. "Create Reelix Vault" button is displayed when connected and no folder ID is configured
        composeRule.onNodeWithTag("create_reelix_vault_button")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Create Reelix Vault").assertIsDisplayed()

        // 5. Click "Create Reelix Vault"
        composeRule.onNodeWithTag("create_reelix_vault_button").performClick()
        composeRule.waitForIdle()

        // 6. Post-creation state: folder ID is saved, button disappears, text field contains folder ID
        assertEquals("ui_vault_folder_id_789", viewModel.settings.value.cloudDriveFolderId)
        composeRule.onNodeWithTag("create_reelix_vault_button").assertDoesNotExist()

        // 7. Click Disconnect button
        composeRule.onNodeWithTag("disconnect_google_drive_button").performClick()
        composeRule.waitForIdle()

        // 8. Returns to disconnected state
        composeRule.onNodeWithTag("connect_google_drive_button")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Connect Google Drive").assertIsDisplayed()
        assertNull(container.fakeSettings.cloudDriveAccessToken())
    }
}
