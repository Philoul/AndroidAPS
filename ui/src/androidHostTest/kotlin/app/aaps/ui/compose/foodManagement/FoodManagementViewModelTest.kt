package app.aaps.ui.compose.foodManagement

import app.aaps.core.data.model.FD
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.ui.compose.foodManagement.images.FoodImageStore
import app.aaps.ui.compose.foodManagement.images.FoodImages
import app.aaps.ui.compose.foodManagement.images.NoFoodImageStore
import app.aaps.ui.compose.foodManagement.openfoodfacts.OffImage
import app.aaps.ui.compose.foodManagement.openfoodfacts.OffProduct
import app.aaps.ui.compose.foodManagement.openfoodfacts.OpenFoodFactsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
internal class FoodManagementViewModelTest {

    @Mock private lateinit var persistenceLayer: PersistenceLayer
    @Mock private lateinit var aapsLogger: AAPSLogger
    @Mock private lateinit var openFoodFacts: OpenFoodFactsRepository

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
        sut = FoodManagementViewModel(persistenceLayer, aapsLogger, openFoodFacts, FoodImages(NoFoodImageStore(), openFoodFacts, aapsLogger))
    }

    /** Photo files in memory. */
    private class MemoryImageStore : FoodImageStore {

        val files = mutableMapOf<String, ByteArray>()
        override val available: Boolean = true
        override fun read(key: String): ByteArray? = files[key]
        override fun write(key: String, bytes: ByteArray) {
            files[key] = bytes
        }

        override fun delete(key: String) {
            files.remove(key)
        }

        override fun keys(): List<String> = files.keys.toList()
    }

    private fun screenWith(store: FoodImageStore, vararg foods: FD): FoodManagementViewModel {
        runBlocking { whenever(persistenceLayer.getFoods()).thenReturn(foods.toList()) }
        val vm = FoodManagementViewModel(persistenceLayer, aapsLogger, openFoodFacts, FoodImages(store, openFoodFacts, aapsLogger))
        dispatcher.scheduler.runCurrent()
        return vm
    }

    private fun loadFoods(vararg foods: FD) {
        runBlocking { whenever(persistenceLayer.getFoods()).thenReturn(foods.toList()) }
        dispatcher.scheduler.runCurrent()
    }

    /** Runs the screen until [done]: the photo files are written on the real IO dispatcher. */
    private fun runUntil(done: () -> Boolean) {
        repeat(200) {
            dispatcher.scheduler.runCurrent()
            if (done()) return
            Thread.sleep(10)
        }
    }

    private fun savedFood(): FD = argumentCaptor<FD>().apply { runBlocking { verify(persistenceLayer).insertOrUpdateFood(capture()) } }.firstValue

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

    @Test
    fun `a portion is shown as it is stored, never rounded`() {
        assertThat(FoodValues.formatPortion(125.0)).isEqualTo("125")
        assertThat(FoodValues.formatPortion(0.25)).isEqualTo("0.25")
        assertThat(FoodValues.parsePortion("0")).isNull()
        assertThat(FoodValues.parsePortion("1,5")).isEqualTo(1.5)
    }

    @Test
    fun `saveFood keeps the glycemic index group of a food from Nightscout`() {
        loadFoods()
        sut.openEditor(chocolate.copy(gi = 2))
        sut.updateEditorName("Dark chocolate")

        sut.saveFood()
        runUntil { runCatching { savedFood() }.isSuccess }

        assertThat(savedFood().gi).isEqualTo(2)
    }

    // endregion

    // region editor modes, categories

    @Test
    fun `in the portion mode the values follow the portion, in the values mode they do not`() {
        val yogurt = FD(id = 3, name = "Yogurt", portion = 100.0, unit = "g", carbs = 12.0, fat = 3.0, protein = 4.0, energy = 300.0, fiber = 1.0)
        sut.openEditor(yogurt)
        assertThat(sut.uiState.value.editorScaleMode).isFalse() // a food changed starts in the values mode

        sut.updateEditorPortion("50")
        assertThat(sut.uiState.value.editorCarbs).isEqualTo("12")

        sut.updateEditorPortion("100")
        sut.setScaleMode(true)
        sut.updateEditorPortion("125")
        val state = sut.uiState.value
        assertThat(state.editorCarbs).isEqualTo("15")
        assertThat(state.editorFat).isEqualTo("3.8")
        assertThat(state.editorProtein).isEqualTo("5")
        assertThat(state.editorEnergy).isEqualTo("375")
        assertThat(state.editorFiber).isEqualTo(1.25)

        sut.setScaleMode(false)
        sut.updateEditorPortion("1")
        assertThat(sut.uiState.value.editorCarbs).isEqualTo("15")
    }

    @Test
    fun `a food typed by hand is never recalculated in the values mode`() {
        sut.openEditor()
        sut.updateEditorPortion("100")
        sut.updateEditorCarbs("50")

        sut.updateEditorPortion("200")

        assertThat(sut.uiState.value.editorCarbs).isEqualTo("50")
    }

    @Test
    fun `a category typed with other capitals is saved as the existing one`() {
        val apple = FD(id = 3, name = "Apple", portion = 100.0, carbs = 12.0, category = "Fruits", subCategory = "Fresh")
        loadFoods(apple)
        sut.openEditor()
        sut.updateEditorName("Pear")
        sut.updateEditorCategory(" fruits ")
        sut.updateEditorSubCategory("FRESH")
        sut.updateEditorCarbs("10")

        sut.saveFood()
        runUntil { runCatching { savedFood() }.isSuccess }

        assertThat(savedFood().category).isEqualTo("Fruits")
        assertThat(savedFood().subCategory).isEqualTo("Fresh")
    }

    @Test
    fun `only the subcategories of the chosen category are proposed`() {
        loadFoods(
            FD(id = 3, name = "Apple", portion = 100.0, carbs = 12.0, category = "Fruits", subCategory = "Fresh"),
            FD(id = 4, name = "Raisins", portion = 100.0, carbs = 70.0, category = "fruits", subCategory = "Dried"),
            FD(id = 5, name = "Bean", portion = 100.0, carbs = 5.0, category = "Vegetables", subCategory = "Green"),
        )

        assertThat(sut.subCategoriesOf("FRUITS")).containsExactly("Dried", "Fresh").inOrder()
        assertThat(sut.subCategoriesOf("")).isEmpty()
    }

    @Test
    fun `a long unit is cut`() {
        sut.updateEditorUnit("x".repeat(MAX_UNIT_LENGTH + 5))

        assertThat(sut.uiState.value.editorUnit).hasLength(MAX_UNIT_LENGTH)
    }

    @Test
    fun `a category filter is removed with its subcategory when its last food moves to another category`() {
        val changes = MutableSharedFlow<List<FD>>(extraBufferCapacity = 1)
        whenever(persistenceLayer.observeChanges(FD::class)).thenReturn(changes)
        val cake = FD(id = 40, name = "Cake", category = "Bakery", subCategory = "Sweet", portion = 100.0, unit = "g", carbs = 50.0)
        val bread = FD(id = 41, name = "Bread", category = "Bakery", portion = 100.0, unit = "g", carbs = 48.0)
        val vm = screenWith(NoFoodImageStore(), cake, bread)
        vm.setFilterCategory("Bakery")
        vm.setFilterSubCategory("Sweet")

        runBlocking { whenever(persistenceLayer.getFoods()).thenReturn(listOf(cake.copy(category = "Dessert"), bread.copy(category = "Bread"))) }
        changes.tryEmit(emptyList())
        dispatcher.scheduler.advanceTimeBy(1_100)
        dispatcher.scheduler.runCurrent()

        assertThat(vm.uiState.value.filterCategory).isNull()
        assertThat(vm.uiState.value.filterSubCategory).isNull()
    }

    @Test
    fun `a category filter stays while it still has foods`() {
        val changes = MutableSharedFlow<List<FD>>(extraBufferCapacity = 1)
        whenever(persistenceLayer.observeChanges(FD::class)).thenReturn(changes)
        val cake = FD(id = 40, name = "Cake", category = "Bakery", portion = 100.0, unit = "g", carbs = 50.0)
        val bun = FD(id = 41, name = "Bun", category = "Bakery", portion = 100.0, unit = "g", carbs = 55.0)
        val vm = screenWith(NoFoodImageStore(), cake, bun)
        vm.setFilterCategory("Bakery")

        runBlocking { whenever(persistenceLayer.getFoods()).thenReturn(listOf(cake.copy(category = "Dessert"), bun)) }
        changes.tryEmit(emptyList())
        dispatcher.scheduler.advanceTimeBy(1_100)
        dispatcher.scheduler.runCurrent()

        assertThat(vm.uiState.value.filterCategory).isEqualTo("Bakery")
    }

    // endregion

    // region photos

    @Test
    fun `a photo taken in the editor is saved with the food, and the old file goes when it is removed`() {
        runBlocking { whenever(persistenceLayer.insertOrUpdateFood(any())).thenReturn(PersistenceLayer.TransactionResult()) }
        val yogurt = FD(id = 30, name = "Yogurt", portion = 1.0, unit = "pot", carbs = 6.0)
        val store = MemoryImageStore()
        val vm = screenWith(store, yogurt)
        val photo = byteArrayOf(1, 2, 3)

        vm.openEditor(yogurt)
        vm.openPhotoCamera()
        vm.takePhoto(photo)
        assertThat(vm.uiState.value.showPhotoCamera).isFalse()
        vm.saveFood()
        runUntil { runCatching { savedFood() }.isSuccess }

        val key = savedFood().image!!
        assertThat(key).startsWith(FoodImages.USER_KEY_PREFIX)
        assertThat(store.files[key]).isEqualTo(photo)

        // Removed: the food has no photo any more, and its file is deleted.
        val withPhoto = yogurt.copy(image = key)
        runBlocking { whenever(persistenceLayer.getFoods()).thenReturn(listOf(withPhoto)) }
        clearInvocations(persistenceLayer)
        vm.openEditor(withPhoto)
        runUntil { vm.uiState.value.editorUserPhoto != null }
        vm.removePhoto()
        vm.saveFood()
        runUntil { key !in store.files }

        assertThat(savedFood().image).isNull()
        assertThat(store.files).isEmpty()
    }

    @Test
    fun `the photo files no food uses are deleted when the screen opens`() {
        val store = MemoryImageStore().apply {
            files["user_kept"] = byteArrayOf(1)
            files["user_old"] = byteArrayOf(2)
            files["3017620422003"] = byteArrayOf(3)
            files["111"] = byteArrayOf(4)
        }
        val chocolate = FD(id = 1, name = "Chocolate", portion = 100.0, carbs = 57.0, barcode = "3017620422003", image = "user_kept")

        screenWith(store, chocolate)
        runUntil { "111" !in store.files }

        assertThat(store.files.keys).containsExactly("user_kept", "3017620422003")
    }

    @Test
    fun `no photo file is deleted when no food was read`() {
        val store = MemoryImageStore().apply { files["user_kept"] = byteArrayOf(1) }

        screenWith(store)
        dispatcher.scheduler.runCurrent()

        assertThat(store.files.keys).containsExactly("user_kept")
    }

    // endregion

    // region Open Food Facts import

    private val milk = OffProduct(
        barcode = "123", name = "Milk", brand = null, carbsPer100 = 4.7, sugarsPer100 = 4.7, fiberPer100 = 0.0,
        fatPer100 = 1.6, proteinPer100 = 3.3, energyKjPer100 = 197.0, isLiquid = true
    )

    private val yogurt = OffProduct(
        barcode = "1", name = "Yaourt", brand = null, carbsPer100 = 8.8, sugarsPer100 = null, fiberPer100 = 0.4,
        fatPer100 = 3.2, proteinPer100 = 4.0, energyKjPer100 = 300.0, isLiquid = false,
        servingSize = "1 pot (125 g)", servingQuantity = 125.0, imageUrl = "https://images.openfoodfacts.org/images/products/1/front.200.jpg"
    )

    @Test
    fun `selecting an OFF product fills a new food in the editor without saving it`() {
        sut.openImport()

        sut.selectOffProduct(milk)

        val state = sut.uiState.value
        assertThat(state.showImport).isFalse()
        assertThat(state.showEditor).isTrue()
        assertThat(state.editorFood).isNull() // a new food, nothing saved yet
        assertThat(state.editorName).isEqualTo("Milk")
        assertThat(state.editorPortion).isEqualTo("100")
        assertThat(state.editorUnit).isEqualTo("ml")
        assertThat(state.editorCarbs).isEqualTo("4.7") // decimals are kept now
        assertThat(state.editorCategory).isEqualTo(OFF_CATEGORY)
        assertThat(state.editorOffProduct).isEqualTo(milk)
        assertThat(state.editorScaleMode).isTrue() // an import starts in the portion mode
    }

    @Test
    fun `opening the editor by hand clears the OFF banner`() {
        val empty = milk.copy(carbsPer100 = null, sugarsPer100 = null, fiberPer100 = null, fatPer100 = null, proteinPer100 = null, energyKjPer100 = null)
        sut.selectOffProduct(empty)
        assertThat(sut.uiState.value.editorCarbs).isEmpty() // missing carbs stay empty, not 0

        sut.openEditor()

        assertThat(sut.uiState.value.editorOffProduct).isNull()
    }

    @Test
    fun `an OFF product with a serving opens on that serving, and the user can switch to 100 g`() {
        sut.selectOffProduct(yogurt)
        assertThat(sut.uiState.value.editorPortion).isEqualTo("1")
        assertThat(sut.uiState.value.editorUnit).isEqualTo("pot (125 g)")
        assertThat(sut.uiState.value.editorCarbs).isEqualTo("11")
        assertThat(sut.uiState.value.editorOffPerServing).isTrue()

        sut.setOffPerServing(false)
        assertThat(sut.uiState.value.editorPortion).isEqualTo("100")
        assertThat(sut.uiState.value.editorUnit).isEqualTo("g")
        assertThat(sut.uiState.value.editorCarbs).isEqualTo("8.8")
        assertThat(sut.uiState.value.editorOffPerServing).isFalse()

        sut.setOffPerServing(true)
        assertThat(sut.uiState.value.editorCarbs).isEqualTo("11")
    }

    @Test
    fun `changing the portion of an OFF food recalculates the values, a value typed by the user is kept`() {
        sut.selectOffProduct(yogurt)
        sut.updateEditorCarbs("12")

        sut.updateEditorPortion("2")

        assertThat(sut.uiState.value.editorCarbs).isEqualTo("12") // typed, kept
        assertThat(sut.uiState.value.editorFat).isEqualTo("8")    // computed, follows: 3.2 x 2.5
        assertThat(sut.uiState.value.editorEnergy).isEqualTo("750")
    }

    @Test
    fun `clearing the portion and typing a new one still recalculates`() {
        sut.selectOffProduct(yogurt)
        sut.updateEditorPortion("")
        sut.updateEditorPortion("2")

        assertThat(sut.uiState.value.editorCarbs).isEqualTo("22")
    }

    @Test
    fun `an imported food is saved with its barcode, its fiber and its OFF photo`() {
        runBlocking { whenever(persistenceLayer.insertOrUpdateFood(any())).thenReturn(PersistenceLayer.TransactionResult()) }
        val store = MemoryImageStore()
        val vm = screenWith(store)
        val photo = byteArrayOf(7, 8)
        runBlocking { whenever(openFoodFacts.image(any())).thenReturn(OffImage.Found(photo)) }

        vm.selectOffProduct(yogurt)
        vm.saveFood()
        runUntil { "1" in store.files }

        val saved = savedFood()
        assertThat(saved.barcode).isEqualTo("1")
        assertThat(saved.carbs).isEqualTo(11.0)
        assertThat(saved.fiber!!).isWithin(0.0001).of(0.5)
        assertThat(saved.image).isNull() // no photo of the user
        assertThat(store.files["1"]).isEqualTo(photo)
    }

    @Test
    fun `a barcode read while the camera is closed is ignored`() {
        sut.onBarcodeScanned("3017620422003")

        assertThat(sut.uiState.value.importQuery).isEmpty()
    }

    // endregion
}
