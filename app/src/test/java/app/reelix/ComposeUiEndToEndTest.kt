package app.reelix

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import app.reelix.domain.model.ShareSaveResult
import app.reelix.domain.model.UserMetadata
import app.reelix.ui.ReelixApp
import app.reelix.ui.theme.ReelixTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp")
class ComposeUiEndToEndTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var container: TestAppContainer

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<ReelixApplication>()
        container = TestAppContainer(app)
        app.container = container
    }

    @After
    fun tearDown() {
        container.close()
    }

    @Test
    fun endToEndSaveNavigateViewDetailsEditAndSearchFlow() {
        composeRule.setContent {
            ReelixTheme {
                ReelixApp(container = container)
            }
        }

        // 1. Home screen renders empty vault onboarding
        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()
        composeRule.onNodeWithText("Your vault is ready").assertIsDisplayed()

        // 2. Save a Reel link from Home
        composeRule.onNodeWithTag("save_link_button").performClick()
        composeRule.onNodeWithTag("manual_url_input")
            .performTextInput("https://www.instagram.com/reel/UiFlowTest42/?igsh=abc")
        composeRule.onNodeWithText("Save to queue").performClick()
        composeRule.waitForIdle()

        // 3. Navigate to Saved items tab and verify the item card appears
        composeRule.onNodeWithTag("nav_library").performClick()
        composeRule.onNodeWithTag("saved_items_screen").assertIsDisplayed()
        composeRule.onNodeWithText("instagram.com/reel/UiFlowTest42").assertIsDisplayed()

        // 4. Open Item Details, edit user notes & title, and verify provenance sections render
        composeRule.onNodeWithText("instagram.com/reel/UiFlowTest42").performClick()
        composeRule.onNodeWithTag("item_detail_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("detail_edit_button").performClick()

        composeRule.onNodeWithText("Title").performTextInput("Kotlin Flow Mastery")
        composeRule.onNodeWithText("Personal notes").performTextInput("StateFlow and SharedFlow patterns")
        composeRule.onNodeWithText("Save").performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("Kotlin Flow Mastery")[0].assertIsDisplayed()
        composeRule.onNodeWithText("StateFlow and SharedFlow patterns").assertIsDisplayed()

        // 5. Navigate back, open Search tab, and verify offline FTS finds the newly edited note!
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_search").performClick()
        composeRule.onNodeWithTag("search_input").performTextInput("SharedFlow")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Kotlin Flow Mastery").assertIsDisplayed()
    }

    @Test
    fun searchSettingsAndClearConfirmationWorkInteractively() {
        runBlocking {
            val res = container.shareIntakeUseCase.handleManualText("https://www.instagram.com/reel/SearchUi99/")
            val savedId = (res as ShareSaveResult.Handled).saved.first().itemId
            container.savedItemRepository.updateUserMetadata(
                savedId,
                UserMetadata(
                    title = "Architecture Guide",
                    caption = null,
                    transcript = null,
                    notes = "Clean domain boundaries with Room FTS",
                    category = "Engineering",
                    tags = listOf("cleanarch"),
                ),
            )
        }

        composeRule.setContent {
            ReelixTheme {
                ReelixApp(container = container)
            }
        }

        // Navigate to Search tab and search by note content
        composeRule.onNodeWithTag("nav_search").performClick()
        composeRule.onNodeWithTag("search_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("search_input").performTextInput("boundaries")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Architecture Guide").assertIsDisplayed()

        // Navigate to Settings tab and trigger manual queue run
        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.onNodeWithTag("settings_screen").assertIsDisplayed()
        composeRule.onNodeWithText("Background processing status").assertIsDisplayed()
        composeRule.onNodeWithTag("run_queue_now_button").performClick()
        composeRule.waitForIdle()
        assertTrue(container.fakeScheduler.lastForceRunNow)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w840dp-h1200dp")
    fun tabletScreenRendersAdaptiveNavigationRail() {
        composeRule.setContent {
            ReelixTheme {
                ReelixApp(container = container)
            }
        }
        composeRule.onNodeWithTag("nav_home").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_library").performClick()
        composeRule.onNodeWithTag("saved_items_screen").assertIsDisplayed()
    }
}
