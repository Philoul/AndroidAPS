package app.aaps.ui.compose.foodManagement

import app.aaps.core.data.model.FD
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.ui.compose.foodManagement.book.FoodBookRepository
import app.aaps.ui.compose.foodManagement.book.MemoryFoodBookStore
import app.aaps.ui.compose.foodManagement.book.RecipeKind
import app.aaps.ui.compose.foodManagement.openfoodfacts.OffProduct
import app.aaps.ui.compose.foodManagement.openfoodfacts.OpenFoodFactsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
internal class FoodManagementViewModelTest {

    @Mock private lateinit var persistenceLayer: PersistenceLayer
    @Mock private lateinit var aapsLogger: AAPSLogger
    @Mock private lateinit var openFoodFacts: OpenFoodFactsRepository
    @Mock private lateinit var rh: TextResolver
    @Mock private lateinit var dateUtil: DateUtil

    private lateinit var sut: FoodManagementViewModel

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        // StandardTestDispatcher defers the init{} loadData() launch (no advanceUntilIdle), so construction
        // stays clean and we test the synchronous update methods against the default state. The observeChanges()
        // cold flow is still built synchronously during init, so it must be stubbed non-null.
        Dispatchers.setMain(StandardTestDispatcher())
        whenever(persistenceLayer.observeChanges(FD::class)).thenReturn(emptyFlow())
        sut = FoodManagementViewModel(persistenceLayer, aapsLogger, openFoodFacts, rh, FoodBookRepository(MemoryFoodBookStore(), aapsLogger), dateUtil)
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

    // ---- Meal (proof of concept) ----

    private val bread = FD(id = 1, name = "Bread", portion = 100.0, carbs = 50)

    @Test
    fun `askQuantity proposes the food portion`() {
        sut.askQuantity(bread)

        assertThat(sut.uiState.value.quantityFood).isEqualTo(bread)
        assertThat(sut.uiState.value.quantityText).isEqualTo("100")
    }

    @Test
    fun `confirmQuantity adds the food with carbs for that quantity`() {
        sut.askQuantity(bread)
        sut.updateQuantity("80")
        sut.confirmQuantity()

        val state = sut.uiState.value
        assertThat(state.quantityFood).isNull()
        val item = state.mealItems.single()
        assertThat(item.name).isEqualTo("Bread")
        assertThat(item.quantity).isEqualTo(80.0)
        assertThat(item.unit).isEqualTo("g")
        assertThat(item.carbs).isWithin(0.0001).of(40.0)
    }

    @Test
    fun `an invalid quantity keeps the dialog open and adds nothing`() {
        sut.askQuantity(bread)
        sut.updateQuantity("0")
        sut.confirmQuantity()

        assertThat(sut.uiState.value.quantityFood).isEqualTo(bread)
        assertThat(sut.uiState.value.mealItems).isEmpty()
    }

    @Test
    fun `the same food can be added twice and removed one at a time`() {
        repeat(2) { sut.askQuantity(bread); sut.confirmQuantity() }
        val items = sut.uiState.value.mealItems
        assertThat(items.map { it.key }.distinct()).hasSize(2)

        sut.removeMealItem(items.first().key)

        assertThat(sut.uiState.value.mealItems.map { it.key }).containsExactly(items.last().key)
    }

    @Test
    fun `removing the last item closes the meal sheet`() {
        sut.askQuantity(bread); sut.confirmQuantity()
        sut.openMeal()
        assertThat(sut.uiState.value.showMeal).isTrue()

        sut.removeMealItem(sut.uiState.value.mealItems.single().key)

        assertThat(sut.uiState.value.showMeal).isFalse()
    }

    @Test
    fun `the meal can be saved as a dish, and a line keeps its food`() {
        sut.askQuantity(bread); sut.confirmQuantity()
        sut.openSave()
        assertThat(sut.uiState.value.showSave).isTrue()
        assertThat(sut.uiState.value.saveKind).isEqualTo(RecipeKind.MEAL) // the default

        sut.updateSaveName(" Toast ")
        sut.updateSaveKind(RecipeKind.DISH)
        sut.confirmSave()

        val state = sut.uiState.value
        assertThat(state.showSave).isFalse()
        val saved = state.mealSource!!
        assertThat(saved.name).isEqualTo("Toast")
        assertThat(saved.kind).isEqualTo(RecipeKind.DISH)
        assertThat(saved.items.single().foodId).isEqualTo(1L)
        assertThat(saved.items.single().quantity).isEqualTo(100.0)
    }

    @Test
    fun `a meal without a name is not saved`() {
        sut.askQuantity(bread); sut.confirmQuantity()
        sut.openSave()
        sut.updateSaveName("  ")
        sut.confirmSave()

        assertThat(sut.uiState.value.showSave).isTrue()
        assertThat(sut.uiState.value.mealSource).isNull()
    }

    @Test
    fun `clearing the meal forgets where it came from`() {
        sut.askQuantity(bread); sut.confirmQuantity()
        sut.openSave(); sut.updateSaveName("Toast"); sut.confirmSave()

        sut.clearMeal()

        assertThat(sut.uiState.value.mealSource).isNull()
    }

    @Test
    fun `a meal line carries the food nutrition for its quantity`() {
        val milk = FD(id = 2, name = "Milk", portion = 250.0, unit = "ml", carbs = 12, fat = 4, protein = 8, energy = 600)
        sut.askQuantity(milk); sut.updateQuantity("125"); sut.confirmQuantity()

        val item = sut.uiState.value.mealItems.single()
        assertThat(item.foodId).isEqualTo(2L)
        assertThat(item.fat!!).isWithin(0.0001).of(2.0)
        assertThat(item.energyKj!!).isWithin(0.0001).of(300.0)
    }

    @Test
    fun `an empty meal cannot be opened`() {
        sut.openMeal()

        assertThat(sut.uiState.value.showMeal).isFalse()
    }

    // ---- Open Food Facts import (proof of concept) ----

    @Test
    fun `selecting an OFF product fills a new food in the editor without saving it`() {
        val product = OffProduct(
            barcode = "123", name = "Milk", brand = null, carbsPer100 = 4.7, sugarsPer100 = 4.7, fiberPer100 = 0.0,
            fatPer100 = 1.6, proteinPer100 = 3.3, energyKjPer100 = 197.0, isLiquid = true
        )
        sut.openImport()

        sut.selectOffProduct(product)

        val state = sut.uiState.value
        assertThat(state.showImport).isFalse()
        assertThat(state.showEditor).isTrue()
        assertThat(state.editorFood).isNull() // a new food, nothing saved yet
        assertThat(state.editorName).isEqualTo("Milk")
        assertThat(state.editorPortion).isEqualTo("100")
        assertThat(state.editorUnit).isEqualTo("ml")
        assertThat(state.editorCarbs).isEqualTo("5")
        assertThat(state.editorCategory).isEqualTo(OFF_CATEGORY)
        assertThat(state.editorOffProduct).isEqualTo(product)
    }

    @Test
    fun `opening the editor by hand clears the OFF banner`() {
        val product = OffProduct(
            barcode = "123", name = "Milk", brand = null, carbsPer100 = null, sugarsPer100 = null, fiberPer100 = null,
            fatPer100 = null, proteinPer100 = null, energyKjPer100 = null, isLiquid = false
        )
        sut.selectOffProduct(product)
        assertThat(sut.uiState.value.editorCarbs).isEmpty() // missing carbs stay empty, not 0

        sut.openEditor()

        assertThat(sut.uiState.value.editorOffProduct).isNull()
    }

    private val yogurt = OffProduct(
        barcode = "1", name = "Yaourt", brand = null, carbsPer100 = 8.8, sugarsPer100 = null, fiberPer100 = null,
        fatPer100 = 3.2, proteinPer100 = 4.0, energyKjPer100 = 300.0, isLiquid = false,
        servingSize = "1 pot (125 g)", servingQuantity = 125.0
    )

    @Test
    fun `an OFF product with a serving opens on that serving`() {
        sut.selectOffProduct(yogurt)

        val state = sut.uiState.value
        assertThat(state.editorPortion).isEqualTo("1")
        assertThat(state.editorUnit).isEqualTo("pot (125 g)")
        assertThat(state.editorCarbs).isEqualTo("11")
        assertThat(state.editorOffPerServing).isTrue()
    }

    @Test
    fun `the user can switch between the serving and 100 g`() {
        sut.selectOffProduct(yogurt)
        sut.setOffPerServing(false)
        assertThat(sut.uiState.value.editorPortion).isEqualTo("100")
        assertThat(sut.uiState.value.editorUnit).isEqualTo("g")
        assertThat(sut.uiState.value.editorCarbs).isEqualTo("9")
        assertThat(sut.uiState.value.editorOffPerServing).isFalse()

        sut.setOffPerServing(true)
        assertThat(sut.uiState.value.editorCarbs).isEqualTo("11")
    }

    @Test
    fun `changing the portion of an OFF food recalculates the values`() {
        sut.selectOffProduct(yogurt)
        sut.setOffPerServing(false)

        sut.updateEditorPortion("125")

        assertThat(sut.uiState.value.editorCarbs).isEqualTo("11")
        assertThat(sut.uiState.value.editorEnergy).isEqualTo("375")
    }

    @Test
    fun `a value typed by the user is kept when the portion changes`() {
        sut.selectOffProduct(yogurt)
        sut.updateEditorCarbs("12")

        sut.updateEditorPortion("2")

        assertThat(sut.uiState.value.editorCarbs).isEqualTo("12") // typed, kept
        assertThat(sut.uiState.value.editorFat).isEqualTo("8")    // computed, follows: 3.2 x 2.5
    }

    @Test
    fun `clearing the portion and typing a new one still recalculates`() {
        sut.selectOffProduct(yogurt)
        sut.updateEditorPortion("")
        sut.updateEditorPortion("2")

        assertThat(sut.uiState.value.editorCarbs).isEqualTo("22")
    }

    @Test
    fun `a food typed by hand is never recalculated`() {
        sut.openEditor()
        sut.updateEditorPortion("100")
        sut.updateEditorCarbs("50")

        sut.updateEditorPortion("200")

        assertThat(sut.uiState.value.editorCarbs).isEqualTo("50")
    }

    @Test
    fun `a barcode read while the camera is closed is ignored`() {
        sut.onBarcodeScanned("3017620422003")

        assertThat(sut.uiState.value.importQuery).isEmpty()
    }
}
