package app.aaps.ui.compose.foodManagement

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import app.aaps.core.ui.R as CoreUiR
import androidx.compose.material3.R as MaterialR
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput

/** Robolectric composable test for [FoodEditorContent]: renders the editor fields + save/cancel actions. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FoodEditorContentTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var saveLabel: String
    private lateinit var cancelLabel: String
    private lateinit var toggleLabel: String

    @Before
    fun setUp() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        saveLabel = ctx.getString(CoreUiR.string.save)
        cancelLabel = ctx.getString(CoreUiR.string.cancel)
        toggleLabel = ctx.getString(MaterialR.string.m3c_dropdown_menu_toggle)
    }

    @Test
    fun rendersEditorWithSaveAndCancel() {
        compose.setContent {
            MaterialTheme {
                FoodEditorContent(
                    state = FoodManagementUiState(),
                    onNameChange = {},
                    onCategoryChange = {},
                    onSubCategoryChange = {},
                    onPortionChange = {},
                    onUnitChange = {},
                    onCarbsChange = {},
                    onFatChange = {},
                    onProteinChange = {},
                    onEnergyChange = {},
                    onNoteChange = {},
                    onSave = {},
                    onCancel = {},
                    onDelete = null,
                    isEditing = false
                )
            }
        }
        // The editor scrolls: the note field pushes the buttons below the test screen.
        compose.onNodeWithText(saveLabel).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(cancelLabel).performScrollTo().assertIsDisplayed()
    }

    /**
     * An import fills the category with "Open Food Facts". That value is not a search: opening the
     * list must still show every category.
     */
    @Test
    fun aFilledInCategoryStillShowsEveryChoice() {
        showEditorWithCategories(category = OFF_CATEGORY)

        compose.onNodeWithText(OFF_CATEGORY).performClick()

        compose.onNodeWithText("Dairy").assertIsDisplayed()
        compose.onNodeWithText("Fruits").assertIsDisplayed()
    }

    /**
     * The arrow opens the list without the focus: the focus brings the keyboard, which hides the
     * list. A real touch is used, not a click action, because the bug was in the touch path.
     */
    @Test
    fun theArrowOpensTheListWithoutTheKeyboard() {
        showEditorWithCategories(category = "Dairy")

        compose.onNodeWithContentDescription(toggleLabel, useUnmergedTree = true).performTouchInput { click() }

        compose.onNodeWithText("Fruits").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("Dairy")).assertIsNotFocused()
    }

    /** A tap on the text still gives the focus, to type a new category. */
    @Test
    fun aTapOnTheTextGivesTheFocus() {
        showEditorWithCategories(category = "Dairy")

        compose.onNode(hasSetTextAction() and hasText("Dairy")).performTouchInput { click(centerLeft + Offset(width / 4f, 0f)) }

        compose.onNode(hasSetTextAction() and hasText("Dairy")).assertIsFocused()
    }

    private fun showEditorWithCategories(category: String) {
        compose.setContent {
            MaterialTheme {
                FoodEditorContent(
                    state = FoodManagementUiState(editorCategory = category),
                    categories = listOf("Dairy", "Fruits"),
                    onNameChange = {},
                    onCategoryChange = {},
                    onSubCategoryChange = {},
                    onPortionChange = {},
                    onUnitChange = {},
                    onCarbsChange = {},
                    onFatChange = {},
                    onProteinChange = {},
                    onEnergyChange = {},
                    onNoteChange = {},
                    onSave = {},
                    onCancel = {},
                    onDelete = null,
                    isEditing = false
                )
            }
        }
    }
}
