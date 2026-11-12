package app.aaps.ui.compose.foodManagement

import app.aaps.core.data.model.FD
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
internal class FoodManagementViewModelTest {

    @Mock private lateinit var persistenceLayer: PersistenceLayer
    @Mock private lateinit var aapsLogger: AAPSLogger

    private lateinit var sut: FoodManagementViewModel
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        // StandardTestDispatcher defers the init{} loadData() launch (no advanceUntilIdle), so construction
        // stays clean and we test the synchronous update methods against the default state. The observeChanges()
        // cold flow is still built synchronously during init, so it must be stubbed non-null.
        Dispatchers.setMain(dispatcher)
        whenever(persistenceLayer.observeChanges(FD::class)).thenReturn(emptyFlow())
        sut = FoodManagementViewModel(persistenceLayer, aapsLogger)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `default uiState is loading with empty filters`() {
        val state = sut.uiState.value
        assertThat(state.isLoading).isTrue()
        assertThat(state.searchQuery).isEmpty()
        assertThat(state.filterCategory).isNull()
        assertThat(state.filterSubCategory).isNull()
        assertThat(state.showEditor).isFalse()
    }

    @Test
    fun `setSearchQuery updates the state`() {
        sut.setSearchQuery("apple")

        assertThat(sut.uiState.value.searchQuery).isEqualTo("apple")
    }

    @Test
    fun `setFilterCategory sets category and clears subcategory`() {
        sut.setFilterSubCategory("Sub")
        sut.setFilterCategory("Fruit")

        val state = sut.uiState.value
        assertThat(state.filterCategory).isEqualTo("Fruit")
        assertThat(state.filterSubCategory).isNull()
    }

    @Test
    fun `setFilterSubCategory updates the state`() {
        sut.setFilterSubCategory("Berries")

        assertThat(sut.uiState.value.filterSubCategory).isEqualTo("Berries")
    }

    @Test
    fun `editor update setters update the state`() {
        sut.updateEditorName("Banana")
        sut.updateEditorCategory("Fruit")
        sut.updateEditorSubCategory("Tropical")
        sut.updateEditorPortion("120")
        sut.updateEditorUnit("g")
        sut.updateEditorCarbs("27")
        sut.updateEditorFat("0")
        sut.updateEditorProtein("1")
        sut.updateEditorEnergy("105")

        val state = sut.uiState.value
        assertThat(state.editorName).isEqualTo("Banana")
        assertThat(state.editorCategory).isEqualTo("Fruit")
        assertThat(state.editorSubCategory).isEqualTo("Tropical")
        assertThat(state.editorPortion).isEqualTo("120")
        assertThat(state.editorUnit).isEqualTo("g")
        assertThat(state.editorCarbs).isEqualTo("27")
        assertThat(state.editorFat).isEqualTo("0")
        assertThat(state.editorProtein).isEqualTo("1")
        assertThat(state.editorEnergy).isEqualTo("105")
    }

    @Test
    fun `openEditor with null resets editor fields to defaults and shows editor`() {
        sut.updateEditorName("stale")
        sut.openEditor(null)

        val state = sut.uiState.value
        assertThat(state.showEditor).isTrue()
        assertThat(state.editorFood).isNull()
        assertThat(state.editorName).isEmpty()
        assertThat(state.editorUnit).isEqualTo("g")
        assertThat(state.editorSaveAttempted).isFalse()
    }

    @Test
    fun `closeEditor hides the editor`() {
        sut.openEditor(null)
        sut.closeEditor()

        assertThat(sut.uiState.value.showEditor).isFalse()
    }

    @Test
    fun `clearUndo clears the undo food`() {
        sut.clearUndo()

        assertThat(sut.uiState.value.undoFood).isNull()
    }

    // region decimals and local fields

    private val chocolate = FD(
        id = 7, name = "Chocolate", portion = 100.0, carbs = 57.5, fat = 31.0, protein = 6.3, energy = 2252.0,
        barcode = "3017620422003", image = "food_1.jpg", note = "for sport", fiber = 3.4
    )

    @Test
    fun `openEditor shows the decimals and the note of the food`() {
        sut.openEditor(chocolate)

        val state = sut.uiState.value
        assertThat(state.editorCarbs).isEqualTo("57.5")
        assertThat(state.editorFat).isEqualTo("31")
        assertThat(state.editorProtein).isEqualTo("6.3")
        assertThat(state.editorEnergy).isEqualTo("2252")
        assertThat(state.editorNote).isEqualTo("for sport")
    }

    @Test
    fun `saveFood keeps decimals typed with a comma, the note, and the fields the editor does not show`() = runTest(dispatcher) {
        whenever(persistenceLayer.getFoods()).thenReturn(emptyList())
        sut.openEditor(chocolate)
        sut.updateEditorCarbs("14,8")
        sut.updateEditorFat("")
        sut.updateEditorNote("  half a bar  ")

        sut.saveFood()
        dispatcher.scheduler.advanceTimeBy(100)
        dispatcher.scheduler.runCurrent()

        val saved = argumentCaptor<FD>().apply { verify(persistenceLayer).insertOrUpdateFood(capture()) }.firstValue
        assertThat(saved.id).isEqualTo(7)
        assertThat(saved.carbs).isEqualTo(14.8)
        assertThat(saved.fat).isNull()
        assertThat(saved.protein).isEqualTo(6.3)
        assertThat(saved.note).isEqualTo("half a bar")
        assertThat(saved.barcode).isEqualTo("3017620422003")
        assertThat(saved.image).isEqualTo("food_1.jpg")
        assertThat(saved.fiber).isEqualTo(3.4)
    }

    @Test
    fun `the wizard gets whole grams`() {
        assertThat(FoodValues.wizardCarbs(14.5)).isEqualTo(15)
        assertThat(FoodValues.wizardCarbs(14.4)).isEqualTo(14)
    }

    @Test
    fun `a value that is negative or not a number is not read`() {
        assertThat(FoodValues.parse("-1")).isNull()
        assertThat(FoodValues.parse("abc")).isNull()
        assertThat(FoodValues.parse("")).isNull()
        assertThat(FoodValues.parse(" 0,4 ")).isEqualTo(0.4)
    }

    // endregion
}
