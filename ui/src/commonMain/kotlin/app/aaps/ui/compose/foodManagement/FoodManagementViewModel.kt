package app.aaps.ui.compose.foodManagement

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.model.FD
import app.aaps.core.data.model.IDs
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.observeChanges
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.ui.compose.foodManagement.images.FoodImages
import app.aaps.ui.compose.foodManagement.openfoodfacts.OffProduct
import app.aaps.ui.compose.foodManagement.openfoodfacts.OffResult
import app.aaps.ui.compose.foodManagement.openfoodfacts.OpenFoodFactsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Registers itself: @ViewModelKey infers the key from the class. No graph entry, and deliberately
// unscoped so each screen gets its own.
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
@Inject
class FoodManagementViewModel(
    private val persistenceLayer: PersistenceLayer,
    private val aapsLogger: AAPSLogger,
    private val openFoodFacts: OpenFoodFactsRepository,
    private val foodImages: FoodImages,
) : ViewModel() {

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

    /** The photo files no food uses any more are deleted once, when the screen opens. */
    private var unusedPhotosDeleted = false

    init {
        loadData()
        observeChanges()
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
                // Only when the screen opens: a food just deleted can still be brought back with
                // "undo", with its photo.
                if (!unusedPhotosDeleted) {
                    unusedPhotosDeleted = true
                    foodImages.deleteUnused(foods)
                }
                val images = foodImages.shown(foods)
                val categories = foods.mapNotNull { it.category }.filter { it.isNotBlank() }.distinct().sorted()
                val subCategories = foods.mapNotNull { it.subCategory }.filter { it.isNotBlank() }.distinct().sorted()
                _uiState.update {
                    // A filter on a group that no longer has any food (its last food was moved to
                    // another group, or deleted) would hide every food, with no filter chip shown to
                    // clear it. It is removed, so the whole list comes back.
                    val category = it.filterCategory?.takeIf { c -> foods.any { f -> f.category == c } }
                    // A subcategory belongs to its category: it goes with it.
                    val subCategory = it.filterSubCategory
                        ?.takeIf { _ -> it.filterCategory == null || category != null }
                        ?.takeIf { s -> foods.any { f -> f.subCategory == s && (category == null || f.category == category) } }
                    it.copy(
                        foods = foods,
                        foodImages = images,
                        allCategories = categories,
                        allSubCategories = subCategories,
                        filterCategory = category,
                        filterSubCategory = subCategory,
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

    /**
     * Opens the editor for [food], or for a new food. A food changed starts in the values mode: every
     * field is free.
     */
    fun openEditor(food: FD? = null) {
        _uiState.update {
            it.copy(
                editorFood = food,
                editorName = food?.name ?: "",
                editorCategory = food?.category ?: "",
                editorSubCategory = food?.subCategory ?: "",
                editorPortion = food?.let { f -> FoodValues.formatPortion(f.portion) } ?: "",
                editorUnit = food?.unit ?: "g",
                editorCarbs = food?.let { f -> FoodValues.format(f.carbs) } ?: "",
                editorFat = food?.fat?.let { v -> FoodValues.format(v) } ?: "",
                editorProtein = food?.protein?.let { v -> FoodValues.format(v) } ?: "",
                editorEnergy = food?.energy?.let { v -> FoodValues.format(v) } ?: "",
                editorFiber = food?.fiber,
                editorNote = food?.note ?: "",
                editorSaveAttempted = false,
                editorOffProduct = null,
                editorScaleMode = false,
                editorScaleBase = null,
                editorImage = null,
                editorUserPhoto = null,
                editorPhotoChanged = false,
                showPhotoCamera = false,
                showEditor = true
            )
        }
        if (food != null) loadEditorPhotos(food)
    }

    /**
     * The photos of the food opened in the editor: the photo of the user, and the OFF photo kept under
     * it. A food imported from OFF whose photo file is missing (new phone, cleared data) gets it again
     * here. Nothing is downloaded for a food typed by hand or a product without a photo.
     */
    private fun loadEditorPhotos(food: FD) {
        if (!foodImages.available) return
        viewModelScope.launch {
            val user = foodImages.userPhoto(food)
            val off = foodImages.offPhoto(food)
            _uiState.update {
                // The user may have taken or removed a photo while the files were read: that wins.
                if (it.editorFood?.id != food.id) it
                else if (it.editorPhotoChanged) it.copy(editorImage = off)
                else it.copy(editorUserPhoto = user, editorImage = off)
            }
            // A downloaded OFF photo is shown on the list too, unless the food shows a photo of the user there.
            if (off != null) _uiState.update { if (food.id in it.foodImages) it else it.copy(foodImages = it.foodImages + (food.id to off)) }
        }
    }

    // ---- Photo of the user ----

    fun openPhotoCamera() = _uiState.update { it.copy(showPhotoCamera = true) }

    fun closePhotoCamera() = _uiState.update { it.copy(showPhotoCamera = false) }

    /** The photo just taken. It is saved with the food, when the user presses Save. */
    fun takePhoto(bytes: ByteArray) = _uiState.update { it.copy(editorUserPhoto = bytes, editorPhotoChanged = true, showPhotoCamera = false) }

    /** The photo of the user is removed when the food is saved. The OFF photo, if any, is shown again. */
    fun removePhoto() = _uiState.update { it.copy(editorUserPhoto = null, editorPhotoChanged = true) }

    // ---- Open Food Facts import ----

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
            _uiState.update { it.copy(importInProgress = false, importResult = result, importImages = emptyMap()) }
            if (result is OffResult.Found) loadImportImages(result.products)
        }
    }

    /**
     * The photos of the products found, one after the other, shown as they arrive: the package is
     * often what tells two close products apart. They are files of the OFF image server, not API
     * requests, so the OFF request limits are not used.
     */
    private suspend fun loadImportImages(products: List<OffProduct>) {
        if (!foodImages.available) return
        for (product in products.distinctBy { it.barcode }) {
            if (product.imageUrl == null) continue
            val bytes = foodImages.download(product) ?: continue
            _uiState.update { s ->
                // A new search has started: these photos are not for it.
                if ((s.importResult as? OffResult.Found)?.products?.any { it.barcode == product.barcode } != true) s
                else s.copy(
                    importImages = s.importImages + (product.barcode to bytes),
                    editorImage = if (s.editorOffProduct?.barcode == product.barcode) bytes else s.editorImage,
                )
            }
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
                editorName = OffFoodValues.from(product).name,
                editorCategory = OFF_CATEGORY,
                editorOffProduct = product,
                // An OFF product starts in the portion mode: the values follow the portion.
                editorScaleMode = true,
                editorImage = it.importImages[product.barcode],
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
        val food = OffFoodValues.from(product, perServing)
        _uiState.update {
            it.copy(
                editorPortion = FoodValues.formatPortion(food.portion),
                editorUnit = food.unit,
                editorCarbs = text(food.nutrients.carbs),
                editorFat = text(food.nutrients.fat),
                editorProtein = text(food.nutrients.protein),
                editorEnergy = text(food.nutrients.energyKj),
                editorFiber = food.nutrients.fiber,
                editorOffPerServing = perServing && OffFoodValues.servingPortion(product) != null,
                editorOffGramsPerUnit = food.gramsPerUnit,
                editorOffComputedPortion = food.portion,
            )
        }
    }

    fun closeEditor() {
        _uiState.update { it.copy(showEditor = false, showPhotoCamera = false) }
    }

    fun updateEditorName(v: String) = _uiState.update { it.copy(editorName = v) }
    fun updateEditorCategory(v: String) = _uiState.update { it.copy(editorCategory = v) }
    fun updateEditorSubCategory(v: String) = _uiState.update { it.copy(editorSubCategory = v) }

    /**
     * In the portion mode, the values follow the portion.
     *
     * - A food filled from OFF: the values are computed again from the exact OFF values. A value the
     *   user has typed is kept: only a value still equal to the one computed for the old portion is
     *   replaced.
     * - Another food: every value changes in proportion, from the values when the mode was chosen.
     *
     * In the values mode, a new portion changes nothing else.
     */
    fun updateEditorPortion(v: String) = _uiState.update { s ->
        val newPortion = FoodValues.parsePortion(v)
        if (!s.editorScaleMode || newPortion == null) return@update s.copy(editorPortion = v)
        val product = s.editorOffProduct
        if (product == null) {
            val base = s.editorScaleBase ?: return@update s.copy(editorPortion = v)
            val factor = newPortion / base.portion
            fun scaled(value: Double?) = value?.let { FoodValues.format(it * factor) } ?: ""
            return@update s.copy(
                editorPortion = v,
                editorCarbs = scaled(base.carbs),
                editorFat = scaled(base.fat),
                editorProtein = scaled(base.protein),
                editorEnergy = scaled(base.energy),
                editorFiber = base.fiber?.let { it * factor },
            )
        }
        // Compared with the portion the values were last computed for, so clearing the field and
        // typing a new number still works.
        val old = OffFoodValues.nutrientsFor(product, s.editorOffComputedPortion, s.editorOffGramsPerUnit)
        val new = OffFoodValues.nutrientsFor(product, newPortion, s.editorOffGramsPerUnit)
        fun follow(current: String, oldValue: Double?, newValue: Double?) = if (current == text(oldValue)) text(newValue) else current
        s.copy(
            editorPortion = v,
            editorCarbs = follow(s.editorCarbs, old.carbs, new.carbs),
            editorFat = follow(s.editorFat, old.fat, new.fat),
            editorProtein = follow(s.editorProtein, old.protein, new.protein),
            editorEnergy = follow(s.editorEnergy, old.energyKj, new.energyKj),
            // Fiber is not in the editor: it always follows.
            editorFiber = new.fiber,
            editorOffComputedPortion = newPortion,
        )
    }

    /**
     * Portion mode ([on]): only the portion is typed, and the values follow it in proportion, from
     * the values of now. Values mode: every field is typed, as on the package. A food imported from
     * OFF starts in the portion mode, a food changed starts in the values mode.
     */
    fun setScaleMode(on: Boolean) = _uiState.update { s ->
        if (!on) return@update s.copy(editorScaleMode = false, editorScaleBase = null)
        val portion = FoodValues.parsePortion(s.editorPortion)
        // Without a portion there is nothing to follow: the values stay free.
        if (portion == null && s.editorOffProduct == null) return@update s
        s.copy(
            editorScaleMode = true,
            editorScaleBase = portion?.let {
                ScaleBase(it, FoodValues.parse(s.editorCarbs), FoodValues.parse(s.editorFat), FoodValues.parse(s.editorProtein), FoodValues.parse(s.editorEnergy), s.editorFiber)
            },
        )
    }

    /** The unit is shown next to the portion on every card: it is kept short. */
    fun updateEditorUnit(v: String) = _uiState.update { it.copy(editorUnit = v.take(MAX_UNIT_LENGTH)) }
    fun updateEditorCarbs(v: String) = _uiState.update { it.copy(editorCarbs = v) }
    fun updateEditorFat(v: String) = _uiState.update { it.copy(editorFat = v) }
    fun updateEditorProtein(v: String) = _uiState.update { it.copy(editorProtein = v) }
    fun updateEditorEnergy(v: String) = _uiState.update { it.copy(editorEnergy = v) }
    fun updateEditorNote(v: String) = _uiState.update { it.copy(editorNote = v) }

    fun saveFood() {
        _uiState.update { it.copy(editorSaveAttempted = true) }
        val state = uiState.value
        val name = state.editorName.trim()
        if (name.isBlank()) return
        val category = sameAs(state.editorCategory, state.allCategories)
        val product = state.editorOffProduct
        val before = state.editorFood

        viewModelScope.launch {
            try {
                // The photo file is written first: a food never points to a file that is not there.
                val newPhoto = if (state.editorPhotoChanged) state.editorUserPhoto?.let { foodImages.savePhoto(it) } else null
                val image = when {
                    !state.editorPhotoChanged         -> before?.image
                    state.editorUserPhoto == null     -> null
                    // The new photo could not be written: the old one stays.
                    else                              -> newPhoto ?: before?.image
                }
                val food = FD(
                    id = before?.id ?: 0,
                    version = before?.version ?: 0,
                    dateCreated = before?.dateCreated ?: -1,
                    isValid = true,
                    referenceId = before?.referenceId,
                    ids = before?.ids ?: IDs(),
                    name = name,
                    // "fruits " is saved as the existing "Fruits": a typing difference must not make a new group.
                    category = category,
                    subCategory = sameAs(state.editorSubCategory, subCategoriesOf(category.orEmpty())),
                    portion = FoodValues.parse(state.editorPortion) ?: 0.0,
                    unit = state.editorUnit.trim().ifBlank { "g" },
                    carbs = FoodValues.parse(state.editorCarbs) ?: 0.0,
                    fat = FoodValues.parse(state.editorFat),
                    protein = FoodValues.parse(state.editorProtein),
                    energy = FoodValues.parse(state.editorEnergy),
                    note = state.editorNote.trim().ifBlank { null },
                    // Not in the editor: kept, so editing a food does not lose them. A food from
                    // Nightscout can have a glycemic index group.
                    gi = before?.gi,
                    barcode = product?.barcode ?: before?.barcode,
                    image = image,
                    fiber = state.editorFiber,
                )
                persistenceLayer.insertOrUpdateFood(food)
                _uiState.update { it.copy(showEditor = false, showPhotoCamera = false) }
                if (product != null) foodImages.saveOffPhoto(product, state.editorImage)
                // The old photo of the user is no longer used by this food.
                val oldPhoto = before?.image
                if (oldPhoto != null && oldPhoto != image) foodImages.deletePhoto(oldPhoto)
                // The list shows the new photo. The id of a new food is only known from the database.
                loadData()
            } catch (e: Exception) {
                aapsLogger.error(LTag.UI, "Failed to save food", e)
            }
        }
    }

    // ---- Category and subcategory suggestions ----

    /** [typed] trimmed, written as in [existing] when it is there with other capitals. Null when empty. */
    private fun sameAs(typed: String, existing: List<String>): String? {
        val text = typed.trim()
        if (text.isEmpty()) return null
        return existing.firstOrNull { it.trim().equals(text, ignoreCase = true) }?.trim() ?: text
    }

    /** The subcategories already used with [category] (same letters, any case). */
    fun subCategoriesOf(category: String): List<String> {
        val wanted = category.trim()
        if (wanted.isEmpty()) return emptyList()
        return uiState.value.foods
            .filter { it.category?.trim().equals(wanted, ignoreCase = true) }
            .mapNotNull { it.subCategory?.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }
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

    private fun text(value: Double?): String = value?.let { FoodValues.format(it) } ?: ""
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
    /** Not shown in the editor (only kept for later, see the requirements): it follows the portion. */
    val editorFiber: Double? = null,
    val editorNote: String = "",
    val editorSaveAttempted: Boolean = false,
    /** The OFF product the editor was filled from, or null for a food typed by hand. */
    val editorOffProduct: OffProduct? = null,
    /** True when the portion is the serving written on the package, false for 100 g. */
    val editorOffPerServing: Boolean = false,
    /** Weight in g (or ml) of one unit of the OFF portion: 125 for "1 pot (125 g)", 1 for grams. */
    val editorOffGramsPerUnit: Double = 1.0,
    /** The portion the values were last computed for. */
    val editorOffComputedPortion: Double = 0.0,
    /** True in the portion mode: only the portion is typed, the values follow it. */
    val editorScaleMode: Boolean = false,
    /** The values the portion mode started from, for a food that does not come from OFF. */
    val editorScaleBase: ScaleBase? = null,
    /** The OFF photo of the food in the editor. */
    val editorImage: ByteArray? = null,
    /** The photo of the user for the food in the editor. Shown instead of [editorImage]. */
    val editorUserPhoto: ByteArray? = null,
    /** True when the user took or removed a photo since the editor opened. */
    val editorPhotoChanged: Boolean = false,
    val showPhotoCamera: Boolean = false,
    /** The photo shown on the list, by food id. */
    val foodImages: Map<Long, ByteArray> = emptyMap(),
    // Open Food Facts import
    val showImport: Boolean = false,
    val importQuery: String = "",
    val importInProgress: Boolean = false,
    val importResult: OffResult? = null,
    /** Photos of the products found, by barcode. */
    val importImages: Map<String, ByteArray> = emptyMap(),
    val showScanner: Boolean = false,
    // Undo
    val undoFood: FD? = null,
)

/** The unit is shown next to the portion on every card. */
internal const val MAX_UNIT_LENGTH = 16

/** The category given to a food imported from OFF. The user can change it before saving. */
internal const val OFF_CATEGORY = "Open Food Facts"
