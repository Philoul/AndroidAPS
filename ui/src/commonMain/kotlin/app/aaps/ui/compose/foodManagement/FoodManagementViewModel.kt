package app.aaps.ui.compose.foodManagement

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.model.FD
import app.aaps.core.data.model.IDs
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.observeChanges
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.foodManagement.book.BolusLink
import app.aaps.ui.compose.foodManagement.book.EatenItem
import app.aaps.ui.compose.foodManagement.book.EatenMeal
import app.aaps.ui.compose.foodManagement.book.FoodBookMath
import app.aaps.ui.compose.foodManagement.book.FoodBookRepository
import app.aaps.ui.compose.foodManagement.book.Nutrition
import app.aaps.ui.compose.foodManagement.book.Recipe
import app.aaps.ui.compose.foodManagement.book.RecipeItem
import app.aaps.ui.compose.foodManagement.book.RecipeKind
import app.aaps.ui.compose.foodManagement.openfoodfacts.OffProduct
import app.aaps.ui.compose.foodManagement.openfoodfacts.OffResult
import app.aaps.ui.compose.foodManagement.openfoodfacts.OpenFoodFactsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// Registers itself: @ViewModelKey infers the key from the class. No graph entry, and deliberately
// unscoped so each screen gets its own.
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
@Inject
class FoodManagementViewModel(
    private val persistenceLayer: PersistenceLayer,
    private val aapsLogger: AAPSLogger,
    private val openFoodFacts: OpenFoodFactsRepository,
    private val rh: TextResolver,
    private val foodBook: FoodBookRepository,
    private val dateUtil: DateUtil,
) : ViewModel() {

    /** Gives every meal item its own key, so the same food can be in the meal twice. */
    private var nextMealKey = 1L

    private val _uiState = MutableStateFlow(FoodManagementUiState())
    val uiState: StateFlow<FoodManagementUiState> = _uiState.asStateFlow()

    /** Derived: foods filtered by current search query and category/subcategory filters */
    val filteredFoods: StateFlow<List<FD>> = uiState
        .map { s ->
            s.foods.filter { food ->
                (s.searchQuery.isBlank() || food.name.contains(s.searchQuery, ignoreCase = true))
                    && (s.filterCategory == null || food.category == s.filterCategory)
                    && (s.filterSubCategory == null || food.subCategory == s.filterSubCategory)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Derived: subcategories available for the currently selected category filter */
    val filteredSubCategories: StateFlow<List<String>> = uiState
        .map { s ->
            val foods = s.foods
            val cat = s.filterCategory
            val relevant = if (cat != null) foods.filter { it.category == cat } else foods
            relevant.mapNotNull { it.subCategory }.filter { it.isNotBlank() }.distinct().sorted()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        loadData()
        observeChanges()
        viewModelScope.launch {
            foodBook.load()
            foodBook.book.collect { book -> _uiState.update { it.copy(recipes = book.recipes, eatenMeals = book.eaten) } }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeChanges() {
        persistenceLayer.observeChanges<FD>()
            .debounce(1000L)
            .onEach { loadData() }
            .launchIn(viewModelScope)
    }

    private fun loadData() {
        viewModelScope.launch {
            try {
                val foods = persistenceLayer.getFoods()
                val categories = foods.mapNotNull { it.category }.filter { it.isNotBlank() }.distinct().sorted()
                val subCategories = foods.mapNotNull { it.subCategory }.filter { it.isNotBlank() }.distinct().sorted()
                _uiState.update {
                    it.copy(
                        foods = foods,
                        allCategories = categories,
                        allSubCategories = subCategories,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                aapsLogger.error(LTag.UI, "Failed to load foods", e)
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setFilterCategory(category: String?) {
        _uiState.update { it.copy(filterCategory = category, filterSubCategory = null) }
    }

    fun setFilterSubCategory(subCategory: String?) {
        _uiState.update { it.copy(filterSubCategory = subCategory) }
    }

    fun openEditor(food: FD? = null) {
        if (food != null) {
            _uiState.update {
                it.copy(
                    editorFood = food,
                    editorName = food.name,
                    editorCategory = food.category ?: "",
                    editorSubCategory = food.subCategory ?: "",
                    editorPortion = food.portion.toString(),
                    editorUnit = food.unit,
                    editorCarbs = food.carbs.toString(),
                    editorFat = food.fat?.toString() ?: "",
                    editorProtein = food.protein?.toString() ?: "",
                    editorEnergy = food.energy?.toString() ?: "",
                    editorSaveAttempted = false,
                    editorOffProduct = null,
                    showEditor = true
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    editorFood = null,
                    editorName = "",
                    editorCategory = "",
                    editorSubCategory = "",
                    editorPortion = "",
                    editorUnit = "g",
                    editorCarbs = "",
                    editorFat = "",
                    editorProtein = "",
                    editorEnergy = "",
                    editorSaveAttempted = false,
                    editorOffProduct = null,
                    showEditor = true
                )
            }
        }
    }

    // ---- Open Food Facts import (proof of concept) ----

    fun openImport() = _uiState.update { it.copy(showImport = true, importResult = null, showScanner = false) }

    fun closeImport() = _uiState.update { it.copy(showImport = false, showScanner = false, importInProgress = false) }

    fun updateImportQuery(v: String) = _uiState.update { it.copy(importQuery = v) }

    fun openScanner() = _uiState.update { it.copy(showScanner = true) }

    fun closeScanner() = _uiState.update { it.copy(showScanner = false) }

    /** The camera reads the same code many times a second: only the first read is used. */
    fun onBarcodeScanned(code: String) {
        if (!uiState.value.showScanner) return
        _uiState.update { it.copy(showScanner = false, importQuery = code) }
        runImport()
    }

    /** Sent only when the user asks: OFF allows about 10 searches per minute. */
    fun runImport() {
        val state = uiState.value
        val query = state.importQuery.trim()
        if (query.isEmpty() || state.importInProgress) return
        _uiState.update { it.copy(importInProgress = true, importResult = null) }
        viewModelScope.launch {
            val result = openFoodFacts.lookup(query)
            _uiState.update { it.copy(importInProgress = false, importResult = result) }
        }
    }

    /**
     * Fills the food editor with an OFF product. Nothing is saved: the user checks the values and
     * presses Save, as for a food typed by hand.
     */
    fun selectOffProduct(product: OffProduct) {
        openEditor()
        _uiState.update {
            it.copy(
                showImport = false,
                showScanner = false,
                editorName = MealMath.foodValuesFrom(product).name,
                editorCategory = OFF_CATEGORY,
                editorOffProduct = product,
            )
        }
        setOffPerServing(true)
    }

    /**
     * Fills portion, unit and values from the OFF product: for its serving ("1 pot (125 g)") when
     * [perServing] and OFF knows one, else for 100 g. Name and category are left as they are.
     */
    fun setOffPerServing(perServing: Boolean) {
        val product = uiState.value.editorOffProduct ?: return
        val values = MealMath.foodValuesFrom(product, perServing)
        _uiState.update {
            it.copy(
                editorPortion = MealMath.formatAmount(values.portion),
                editorUnit = values.unit,
                editorCarbs = values.carbs?.toString() ?: "",
                editorFat = values.fat?.toString() ?: "",
                editorProtein = values.protein?.toString() ?: "",
                editorEnergy = values.energyKj?.toString() ?: "",
                editorOffPerServing = perServing && MealMath.servingPortion(product) != null,
                editorOffGramsPerUnit = values.gramsPerUnit,
                editorOffComputedPortion = values.portion,
            )
        }
    }

    // ---- Meal (proof of concept, in memory only) ----

    fun askQuantity(food: FD) = _uiState.update {
        it.copy(quantityFood = food, quantityText = if (food.portion > 0) MealMath.formatAmount(food.portion) else "1")
    }

    fun updateQuantity(v: String) = _uiState.update { it.copy(quantityText = v) }

    fun dismissQuantity() = _uiState.update { it.copy(quantityFood = null, quantityRecipe = null) }

    fun confirmQuantity() {
        val state = uiState.value
        val quantity = MealMath.parseAmount(state.quantityText) ?: return
        val item = state.quantityFood?.let { foodLine(it, quantity) }
            ?: state.quantityRecipe?.let { recipeLine(it, quantity) ?: return showMessage(UiStrings.food_book_missing) }
            ?: return
        _uiState.update { it.copy(quantityFood = null, quantityRecipe = null, mealItems = it.mealItems + item) }
    }

    /** Carbs of [quantity] in the open quantity dialog, or null when they cannot be computed. */
    fun quantityCarbs(quantity: Double): Double? {
        val state = uiState.value
        state.quantityFood?.let { return MealMath.carbsFor(it, quantity) }
        return state.quantityRecipe?.let { recipe -> nutritionOf(recipe)?.carbs?.times(quantity) }
    }

    private fun foodLine(food: FD, quantity: Double): MealItem {
        val nutrition = FoodBookMath.nutritionOf(food, quantity)
        return MealItem(
            key = nextMealKey++,
            name = food.name,
            quantity = quantity,
            unit = if (food.portion > 0) food.unit else rh.gs(UiStrings.food_meal_portions),
            carbs = nutrition.carbs,
            fat = nutrition.fat,
            protein = nutrition.protein,
            energyKj = nutrition.energyKj,
            foodId = food.id,
        )
    }

    fun removeMealItem(key: Long) = _uiState.update { s ->
        val items = s.mealItems.filterNot { it.key == key }
        s.copy(mealItems = items, showMeal = s.showMeal && items.isNotEmpty())
    }

    fun clearMeal() = _uiState.update { it.copy(mealItems = emptyList(), showMeal = false, mealSource = null) }

    fun openMeal() = _uiState.update { it.copy(showMeal = it.mealItems.isNotEmpty()) }

    fun closeMeal() = _uiState.update { it.copy(showMeal = false) }

    // ---- Dishes and standard meals (proof of concept 2) ----

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Short messages for a snackbar. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private fun showMessage(text: TextRef, vararg args: Any) {
        _messages.tryEmit(rh.gs(text, *args))
    }

    fun selectTab(tab: FoodTab) = _uiState.update { it.copy(tab = tab) }

    private fun foodsById(): Map<Long, FD> = uiState.value.foods.associateBy { it.id }
    private fun recipesById(): Map<String, Recipe> = uiState.value.recipes.associateBy { it.id }

    /** Nutrition of a whole dish or meal, or null when a food or a dish in it is missing. */
    fun nutritionOf(recipe: Recipe): Nutrition? = FoodBookMath.nutritionOf(recipe, foodsById(), recipesById())

    /** Nutrition of one line of a dish or meal, or null when its food or dish is missing. */
    fun nutritionOf(item: RecipeItem): Nutrition? = FoodBookMath.nutritionOf(item, foodsById(), recipesById())

    /** Asks how many servings of a dish go into the meal. */
    fun askQuantity(recipe: Recipe) = _uiState.update { it.copy(quantityRecipe = recipe, quantityText = "1") }

    private fun recipeLine(recipe: Recipe, servings: Double): MealItem? {
        val nutrition = nutritionOf(recipe)?.times(servings) ?: return null
        return MealItem(
            key = nextMealKey++,
            name = recipe.name,
            quantity = servings,
            unit = rh.gs(UiStrings.food_book_servings),
            carbs = nutrition.carbs,
            fat = nutrition.fat,
            protein = nutrition.protein,
            energyKj = nutrition.energyKj,
            recipeId = recipe.id,
        )
    }

    /** The lines of a dish or meal as meal lines, or null when one of them cannot be computed. */
    private fun linesOf(recipe: Recipe): List<MealItem>? = recipe.items.map { item ->
        val line = item.foodId?.let { id -> foodsById()[id]?.let { foodLine(it, item.quantity) } }
            ?: item.recipeId?.let { id -> recipesById()[id]?.let { recipeLine(it, item.quantity) } }
        line ?: return null
    }

    /**
     * Puts the lines of a standard meal into the meal being put together, so each one can still be
     * removed or changed before the bolus wizard ("breakfast, but croissants instead of bread").
     */
    fun addRecipeLines(recipe: Recipe) {
        val lines = linesOf(recipe) ?: return showMessage(UiStrings.food_book_missing)
        _uiState.update {
            it.copy(mealItems = it.mealItems + lines, mealSource = if (it.mealItems.isEmpty()) recipe else it.mealSource, showMeal = true)
        }
    }

    /** Opens a dish or a meal to change it: its lines become the meal, then "Save" writes it back. */
    fun editRecipe(recipe: Recipe) {
        if (uiState.value.mealItems.isNotEmpty()) return showMessage(UiStrings.food_book_clear_first)
        val lines = linesOf(recipe) ?: return showMessage(UiStrings.food_book_missing)
        _uiState.update { it.copy(mealItems = lines, mealSource = recipe, showMeal = true) }
    }

    fun deleteRecipe(recipe: Recipe) {
        viewModelScope.launch { foodBook.deleteRecipe(recipe.id) }
        showMessage(UiStrings.food_book_deleted, recipe.name)
    }

    fun openSave() = _uiState.update { s ->
        s.copy(showSave = true, saveName = s.mealSource?.name ?: "", saveKind = s.mealSource?.kind ?: RecipeKind.MEAL)
    }

    fun updateSaveName(v: String) = _uiState.update { it.copy(saveName = v) }
    fun updateSaveKind(kind: RecipeKind) = _uiState.update { it.copy(saveKind = kind) }
    fun dismissSave() = _uiState.update { it.copy(showSave = false) }

    /**
     * Saves the meal being put together as a dish or a standard meal. A recipe of the same kind and
     * name is replaced, so opening "Breakfast", changing it and saving updates "Breakfast".
     */
    @OptIn(ExperimentalUuidApi::class)
    fun confirmSave() {
        val state = uiState.value
        val name = state.saveName.trim()
        if (name.isEmpty() || state.mealItems.isEmpty()) return
        val sameName = state.recipes.firstOrNull { it.kind == state.saveKind && it.name.equals(name, ignoreCase = true) }
        val id = sameName?.id ?: Uuid.random().toString()
        val items = state.mealItems.map { RecipeItem(name = it.name, quantity = it.quantity, unit = it.unit, foodId = it.foodId, recipeId = it.recipeId) }
        if (FoodBookMath.makesLoop(id, items, recipesById())) return showMessage(UiStrings.food_book_loop)
        val recipe = Recipe(id = id, name = name, kind = state.saveKind, items = items)
        viewModelScope.launch { foodBook.saveRecipe(recipe) }
        _uiState.update { it.copy(showSave = false, mealSource = recipe) }
        showMessage(UiStrings.food_book_saved, name)
    }

    // ---- Meals eaten (proof of concept 3) ----

    /**
     * Keeps a copy of the meal being put together and returns what the bolus wizard gets: the
     * rounded carbs and the note. The copy is matched later with the bolus calculation the wizard
     * saves (same note, a few minutes later), so the meal detail can be found from the history.
     */
    fun startWizard(): Pair<Int, String> {
        val state = uiState.value
        return record(state.mealItems, state.mealSource?.name)
    }

    /** "Bolus wizard" on a standard meal: the whole meal as it is saved. */
    fun startWizard(recipe: Recipe): Pair<Int, String>? {
        val lines = linesOf(recipe)
        if (lines == null) {
            showMessage(UiStrings.food_book_missing)
            return null
        }
        return record(lines, recipe.name)
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun record(items: List<MealItem>, name: String?): Pair<Int, String> {
        val carbs = MealMath.wizardCarbs(items)
        val note = noteFor(items)
        val meal = EatenMeal(
            id = Uuid.random().toString(),
            timestamp = dateUtil.now(),
            name = name,
            note = note,
            wizardCarbs = carbs,
            items = items.map { EatenItem(it.name, it.quantity, it.unit, it.carbs, it.fat, it.protein, it.energyKj) },
        )
        viewModelScope.launch { foodBook.addEatenMeal(meal) }
        return carbs to note
    }

    fun deleteEatenMeal(meal: EatenMeal) {
        viewModelScope.launch { foodBook.deleteEatenMeal(meal.id) }
    }

    fun timeText(timestamp: Long): String = dateUtil.dateAndTimeString(timestamp)

    /**
     * Finds, for each eaten meal, the bolus calculation the wizard saved for it. The wizard does not
     * know about meals yet, so the link is found afterwards: a calculation made within
     * [LINK_WINDOW_MS] after the meal was sent, with the same note, or else with the same carbs.
     */
    fun refreshBolusLinks() {
        val meals = uiState.value.eatenMeals
        if (meals.isEmpty()) return
        viewModelScope.launch {
            try {
                val from = meals.minOf { it.timestamp } - LINK_SLACK_MS
                val results = persistenceLayer.getBolusCalculatorResultsFromTime(from, true)
                _uiState.update { it.copy(bolusLinks = FoodBookMath.linkBoluses(meals, results, LINK_SLACK_MS, LINK_WINDOW_MS)) }
            } catch (e: Exception) {
                aapsLogger.error(LTag.UI, "Food POC: cannot read the bolus calculations", e)
            }
        }
    }

    /**
     * The note the wizard stores with the bolus and carbs, for example
     * "Bread 80 g (40 g), Milk 250 ml (12 g)".
     *
     * For now this text is the only trace of the meal in the history. Storing the meal itself is the
     * next step of the roadmap.
     */
    fun mealNote(): String = noteFor(uiState.value.mealItems)

    private fun noteFor(items: List<MealItem>): String =
        items
            .map { item ->
                rh.gs(
                    UiStrings.food_meal_note_item,
                    item.name,
                    rh.gs(CoreUiStrings.value_with_unit, MealMath.formatAmount(item.quantity), item.unit),
                    rh.gs(InterfacesStrings.format_carbs, item.carbs.roundToInt())
                )
            }
            .reduceOrNull { acc, next -> rh.gs(UiStrings.food_meal_note_join, acc, next) }
            .orEmpty()

    fun closeEditor() {
        _uiState.update { it.copy(showEditor = false) }
    }

    fun updateEditorName(v: String) = _uiState.update { it.copy(editorName = v) }
    fun updateEditorCategory(v: String) = _uiState.update { it.copy(editorCategory = v) }
    fun updateEditorSubCategory(v: String) = _uiState.update { it.copy(editorSubCategory = v) }
    /**
     * For a food filled from OFF, the values follow the portion: they are computed again from the
     * exact OFF values. A value the user has typed is kept: only a value still equal to the one
     * computed for the old portion is replaced. A food typed by hand is never recalculated.
     */
    fun updateEditorPortion(v: String) = _uiState.update { s ->
        val product = s.editorOffProduct
        val newPortion = MealMath.parseAmount(v)
        // Compared with the portion the values were last computed for, so clearing the field and
        // typing a new number still works.
        if (product == null || newPortion == null) return@update s.copy(editorPortion = v)
        val old = MealMath.nutrientsFor(product, s.editorOffComputedPortion, s.editorOffGramsPerUnit)
        val new = MealMath.nutrientsFor(product, newPortion, s.editorOffGramsPerUnit)
        fun follow(current: String, oldValue: Int?, newValue: Int?) = if (current == (oldValue?.toString() ?: "")) newValue?.toString() ?: "" else current
        s.copy(
            editorPortion = v,
            editorCarbs = follow(s.editorCarbs, old.carbs, new.carbs),
            editorFat = follow(s.editorFat, old.fat, new.fat),
            editorProtein = follow(s.editorProtein, old.protein, new.protein),
            editorEnergy = follow(s.editorEnergy, old.energyKj, new.energyKj),
            editorOffComputedPortion = newPortion,
        )
    }
    fun updateEditorUnit(v: String) = _uiState.update { it.copy(editorUnit = v) }
    fun updateEditorCarbs(v: String) = _uiState.update { it.copy(editorCarbs = v) }
    fun updateEditorFat(v: String) = _uiState.update { it.copy(editorFat = v) }
    fun updateEditorProtein(v: String) = _uiState.update { it.copy(editorProtein = v) }
    fun updateEditorEnergy(v: String) = _uiState.update { it.copy(editorEnergy = v) }

    fun saveFood() {
        _uiState.update { it.copy(editorSaveAttempted = true) }
        val state = uiState.value
        val name = state.editorName.trim()
        if (name.isBlank()) return

        val food = FD(
            id = state.editorFood?.id ?: 0,
            version = state.editorFood?.version ?: 0,
            dateCreated = state.editorFood?.dateCreated ?: -1,
            isValid = true,
            referenceId = state.editorFood?.referenceId,
            ids = state.editorFood?.ids ?: IDs(),
            name = name,
            category = state.editorCategory.trim().ifBlank { null },
            subCategory = state.editorSubCategory.trim().ifBlank { null },
            // "1,5" typed with a comma keyboard must not become 0.
            portion = state.editorPortion.trim().replace(',', '.').toDoubleOrNull() ?: 0.0,
            unit = state.editorUnit.trim().ifBlank { "g" },
            carbs = state.editorCarbs.toIntOrNull() ?: 0,
            fat = state.editorFat.toIntOrNull(),
            protein = state.editorProtein.toIntOrNull(),
            energy = state.editorEnergy.toIntOrNull(),
            // Not editable here, but must survive an edit (a food from Nightscout can have one).
            gi = state.editorFood?.gi,
        )

        viewModelScope.launch {
            try {
                persistenceLayer.insertOrUpdateFood(food)
                _uiState.update { it.copy(showEditor = false) }
            } catch (e: Exception) {
                aapsLogger.error(LTag.UI, "Failed to save food", e)
            }
        }
    }

    fun deleteFood(food: FD) {
        viewModelScope.launch {
            try {
                persistenceLayer.invalidateFood(food.id, Action.FOOD_REMOVED, Sources.Food)
                _uiState.update {
                    it.copy(
                        foods = it.foods.filter { f -> f.id != food.id },
                        undoFood = food
                    )
                }
            } catch (e: Exception) {
                aapsLogger.error(LTag.UI, "Failed to delete food", e)
            }
        }
    }

    fun deleteAndCloseEditor() {
        val food = uiState.value.editorFood ?: return
        deleteFood(food)
        closeEditor()
    }

    fun undoDelete() {
        val food = uiState.value.undoFood ?: return
        viewModelScope.launch {
            try {
                persistenceLayer.insertOrUpdateFood(food.copy(isValid = true))
            } catch (e: Exception) {
                aapsLogger.error(LTag.UI, "Failed to undo food delete", e)
            } finally {
                _uiState.update { it.copy(undoFood = null) }
            }
        }
    }

    fun clearUndo() {
        _uiState.update { it.copy(undoFood = null) }
    }
}

@Immutable
data class FoodManagementUiState(
    val foods: List<FD> = emptyList(),
    val isLoading: Boolean = true,
    // Filters
    val searchQuery: String = "",
    val filterCategory: String? = null,
    val filterSubCategory: String? = null,
    val allCategories: List<String> = emptyList(),
    val allSubCategories: List<String> = emptyList(),
    // Editor
    val showEditor: Boolean = false,
    val editorFood: FD? = null,
    val editorName: String = "",
    val editorCategory: String = "",
    val editorSubCategory: String = "",
    val editorPortion: String = "",
    val editorUnit: String = "g",
    val editorCarbs: String = "",
    val editorFat: String = "",
    val editorProtein: String = "",
    val editorEnergy: String = "",
    val editorSaveAttempted: Boolean = false,
    /** Set when the editor was filled from Open Food Facts: the exact values are shown with a warning. */
    val editorOffProduct: OffProduct? = null,
    /** The editor shows the OFF serving ("1 pot (125 g)"), not 100 g. */
    val editorOffPerServing: Boolean = false,
    /** Weight in g (or ml) of one unit of the editor portion, for a food filled from OFF. */
    val editorOffGramsPerUnit: Double = 1.0,
    /** The portion the editor values were last computed for from OFF. */
    val editorOffComputedPortion: Double = 0.0,
    // Undo
    val undoFood: FD? = null,
    // Open Food Facts import (proof of concept)
    val showImport: Boolean = false,
    val importQuery: String = "",
    val importInProgress: Boolean = false,
    val importResult: OffResult? = null,
    val showScanner: Boolean = false,
    // Meal (proof of concept)
    val mealItems: List<MealItem> = emptyList(),
    val showMeal: Boolean = false,
    /** The food the quantity dialog is open for, or null when it is closed. */
    val quantityFood: FD? = null,
    /** The dish the quantity dialog is open for (quantity in servings). */
    val quantityRecipe: Recipe? = null,
    val quantityText: String = "",
    // Dishes, standard meals and eaten meals (proof of concept 2 and 3)
    val tab: FoodTab = FoodTab.FOODS,
    val recipes: List<Recipe> = emptyList(),
    val eatenMeals: List<EatenMeal> = emptyList(),
    /** The dish or meal the meal being put together was opened from, if any. */
    val mealSource: Recipe? = null,
    val showSave: Boolean = false,
    val saveName: String = "",
    val saveKind: RecipeKind = RecipeKind.MEAL,
    /** Eaten meal id to the bolus calculation found for it. */
    val bolusLinks: Map<String, BolusLink> = emptyMap(),
)

enum class FoodTab { FOODS, DISHES, MEALS, HISTORY }

/** A bolus calculation made this long after the meal was sent to the wizard still belongs to it. */
private const val LINK_WINDOW_MS = 30 * 60 * 1000L

/** Clock differences of a few seconds must not break the link. */
private const val LINK_SLACK_MS = 60 * 1000L

/** Imported foods get this category, so they are easy to find with the category filter. */
internal const val OFF_CATEGORY = "Open Food Facts"
