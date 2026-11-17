package app.aaps.ui.compose.foodManagement

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.data.model.FD
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSearchField
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.LocalSnackbarHostState
import app.aaps.core.ui.compose.consumeOverscroll
import app.aaps.core.ui.compose.icons.IcCalculator
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FoodManagementScreen(
    viewModel: FoodManagementViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToWizard: (carbs: Int, name: String) -> Unit = { _, _ -> },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val filteredFoods by viewModel.filteredFoods.collectAsStateWithLifecycle()
    val filteredSubCategories by viewModel.filteredSubCategories.collectAsStateWithLifecycle()
    val snackbarHostState = LocalSnackbarHostState.current
    val undoLabel = stringResource(CoreUiStrings.undo)
    val deletedLabel = stringResource(CoreUiStrings.food_deleted)

    // Show undo snackbar when a food is deleted
    LaunchedEffect(state.undoFood) {
        state.undoFood?.let {
            val result = snackbarHostState.showSnackbar(
                message = deletedLabel,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoDelete()
            } else {
                viewModel.clearUndo()
            }
        }
    }

    Scaffold(
        topBar = {
            AapsTopAppBar(
                title = {
                    AapsSearchField(
                        query = state.searchQuery,
                        onQueryChange = { viewModel.setSearchQuery(it) },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(CoreUiStrings.back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.openImport() }) {
                        Icon(Icons.Filled.CloudDownload, contentDescription = stringResource(UiStrings.food_off_import))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.openEditor() }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(CoreUiStrings.add))
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Filter chips
            if (state.allCategories.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AapsSpacing.extraLarge),
                    horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
                ) {
                    state.allCategories.forEach { cat ->
                        FilterChip(
                            selected = state.filterCategory == cat,
                            onClick = {
                                viewModel.setFilterCategory(if (state.filterCategory == cat) null else cat)
                            },
                            label = { Text(cat) }
                        )
                    }
                }
                if (filteredSubCategories.isNotEmpty() && state.filterCategory != null) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AapsSpacing.extraLarge),
                        horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
                    ) {
                        filteredSubCategories.forEach { sub ->
                            FilterChip(
                                selected = state.filterSubCategory == sub,
                                onClick = {
                                    viewModel.setFilterSubCategory(if (state.filterSubCategory == sub) null else sub)
                                },
                                label = { Text(sub) }
                            )
                        }
                    }
                }
            }

            // Food list
            if (state.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (filteredFoods.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(CoreUiStrings.no_data),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = AapsSpacing.fabClearance),
                    verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)
                ) {
                    items(filteredFoods, key = { it.id }) { food ->
                        FoodItem(
                            food = food,
                            image = state.foodImages[food.id],
                            onEdit = { viewModel.openEditor(food) },
                            onCalculate = { onNavigateToWizard(FoodValues.wizardCarbs(food.carbs), food.name) }
                        )
                    }
                }
            }
        }

        // Editor bottom sheet
        if (state.showEditor) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            val focusManager = LocalFocusManager.current
            ModalBottomSheet(
                onDismissRequest = { focusManager.clearFocus(); viewModel.closeEditor() },
                sheetState = sheetState,
            ) {
                FoodEditorContent(
                    state = state,
                    categories = state.allCategories,
                    subCategories = viewModel.subCategoriesOf(state.editorCategory),
                    onNameChange = viewModel::updateEditorName,
                    onCategoryChange = viewModel::updateEditorCategory,
                    onSubCategoryChange = viewModel::updateEditorSubCategory,
                    onPortionChange = viewModel::updateEditorPortion,
                    onUnitChange = viewModel::updateEditorUnit,
                    onCarbsChange = viewModel::updateEditorCarbs,
                    onFatChange = viewModel::updateEditorFat,
                    onProteinChange = viewModel::updateEditorProtein,
                    onEnergyChange = viewModel::updateEditorEnergy,
                    onNoteChange = viewModel::updateEditorNote,
                    onSave = { focusManager.clearFocus(); viewModel.saveFood() },
                    onCancel = { focusManager.clearFocus(); viewModel.closeEditor() },
                    onDelete = if (state.editorFood != null) {
                        { focusManager.clearFocus(); viewModel.deleteAndCloseEditor() }
                    } else null,
                    isEditing = state.editorFood != null,
                    onOffPerServingChange = viewModel::setOffPerServing,
                    onOpenPhotoCamera = { focusManager.clearFocus(); viewModel.openPhotoCamera() },
                    onClosePhotoCamera = viewModel::closePhotoCamera,
                    onPhoto = viewModel::takePhoto,
                    onRemovePhoto = viewModel::removePhoto,
                    onScaleModeChange = viewModel::setScaleMode,
                )
            }
        }

        // Open Food Facts import
        if (state.showImport) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            val focusManager = LocalFocusManager.current
            ModalBottomSheet(
                onDismissRequest = { focusManager.clearFocus(); viewModel.closeImport() },
                sheetState = sheetState,
            ) {
                OffImportContent(
                    state = state,
                    onQueryChange = viewModel::updateImportQuery,
                    onSearch = { focusManager.clearFocus(); viewModel.runImport() },
                    onOpenScanner = { focusManager.clearFocus(); viewModel.openScanner() },
                    onCloseScanner = viewModel::closeScanner,
                    onScanned = viewModel::onBarcodeScanned,
                    onSelect = viewModel::selectOffProduct,
                )
            }
        }
    }
}

@Composable
private fun FoodItem(
    food: FD,
    image: ByteArray?,
    onEdit: () -> Unit,
    onCalculate: () -> Unit,
) {
    ElevatedCard(
        onClick = onEdit,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AapsSpacing.extraLarge)
            .animateContentSize(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = AapsSpacing.extraLarge, top = AapsSpacing.large, bottom = AapsSpacing.large, end = AapsSpacing.small),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The photo of the package or of the plate tells close foods apart.
            if (image != null) {
                FoodPhoto(bytes = image, size = AapsSpacing.foodThumbnailSize)
                Spacer(modifier = Modifier.width(AapsSpacing.medium))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = food.name,
                    style = MaterialTheme.typography.titleSmall
                )
                val details = buildString {
                    if (food.portion > 0) append(stringResource(CoreUiStrings.value_with_unit, FoodValues.formatPortion(food.portion), food.unit))
                    if (food.category != null) {
                        if (isNotEmpty()) append(" · ")
                        append(food.category)
                        if (food.subCategory != null) append(" / ${food.subCategory}")
                    }
                }
                if (details.isNotEmpty()) {
                    Text(
                        text = details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = stringResource(CoreUiStrings.value_with_unit, FoodValues.format(food.carbs), stringResource(CoreUiStrings.units_grams)),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                val extras = buildList {
                    // F and P were never translated; only the separator and the unit are.
                    val grams = stringResource(CoreUiStrings.units_grams)
                    food.fat?.let { if (it > 0) add(stringResource(InterfacesStrings.confirmation_line, "F", stringResource(CoreUiStrings.value_with_unit, FoodValues.format(it), grams))) }
                    food.protein?.let { if (it > 0) add(stringResource(InterfacesStrings.confirmation_line, "P", stringResource(CoreUiStrings.value_with_unit, FoodValues.format(it), grams))) }
                    food.energy?.let { if (it > 0) add(stringResource(CoreUiStrings.value_with_unit, FoodValues.format(it), "kJ")) }
                }
                if (extras.isNotEmpty()) {
                    Text(
                        text = extras.joinToString(" "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // Name the food, because every row has this same button. Without it a screen reader
            // says "Bolus wizard" once per row with nothing to tell them apart, and picking the
            // wrong one opens the calculator already filled with the wrong carbs.
            //
            // The food name goes on the IconButton and the verb stays on the Icon: a
            // contentDescription on a merging node is inserted BEFORE its children rather than
            // replacing them, so this reads "Chicken salad, Bolus wizard".
            IconButton(
                onClick = onCalculate,
                modifier = Modifier.semantics { contentDescription = food.name }
            ) {
                Icon(
                    imageVector = IcCalculator,
                    contentDescription = stringResource(CoreUiStrings.boluswizard),
                    modifier = Modifier.size(AapsSpacing.chipIconSize),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * A text field that proposes the values already used, so the same group is not typed twice in two
 * ways. The list shows the values that contain what is typed. A new text makes a new value.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SuggestionField(value: String, onValueChange: (String) -> Unit, label: String, suggestions: List<String>, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    // True once the user types in the field. A value set from outside (the "Open Food Facts" category
    // of an import) is not a search: it must not hide the other choices.
    var typedByUser by remember { mutableStateOf(false) }
    val typed = value.trim()
    // A value already chosen or filled in shows every choice, so another one can be picked without
    // clearing it. While a new text is typed, only the values that contain it are shown.
    val filtering = typedByUser && typed.isNotEmpty() && suggestions.none { it.equals(typed, ignoreCase = true) }
    val shown = if (filtering) suggestions.filter { it.contains(typed, ignoreCase = true) } else suggestions
    val open = expanded && shown.isNotEmpty()
    // True while the list was opened with the arrow. ExposedDropdownMenuBox gives the focus to the
    // text field each time the list opens, and the focus brings the keyboard, which hides the list.
    // So the field cannot take the focus until the list is closed. A tap on the text still gives the
    // focus, to type a new value.
    var openedByArrow by remember { mutableStateOf(false) }
    LaunchedEffect(open) { if (!open) openedByArrow = false }
    val focusManager = LocalFocusManager.current
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it); typedByUser = true; expanded = true },
            label = { Text(label) },
            singleLine = true,
            // No arrow when there is nothing to propose: the label keeps the whole width.
            trailingIcon = if (suggestions.isEmpty()) null else {
                {
                    // The whole touch area opens the list, not only the small arrow: a tap next to
                    // the arrow must not go to the text and bring the keyboard.
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    openedByArrow = true
                                    focusManager.clearFocus()
                                }
                            }
                            .menuAnchor(ExposedDropdownMenuAnchorType.SecondaryEditable)
                    ) {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = open)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusProperties { canFocus = !openedByArrow }
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { expanded = false }) {
            shown.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item) },
                    onClick = { onValueChange(item); typedByUser = false; expanded = false },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FoodEditorContent(
    state: FoodManagementUiState,
    categories: List<String> = emptyList(),
    subCategories: List<String> = emptyList(),
    onNameChange: (String) -> Unit,
    onCategoryChange: (String) -> Unit,
    onSubCategoryChange: (String) -> Unit,
    onPortionChange: (String) -> Unit,
    onUnitChange: (String) -> Unit,
    onCarbsChange: (String) -> Unit,
    onFatChange: (String) -> Unit,
    onProteinChange: (String) -> Unit,
    onEnergyChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)?,
    isEditing: Boolean,
    onOffPerServingChange: (Boolean) -> Unit = {},
    onOpenPhotoCamera: () -> Unit = {},
    onClosePhotoCamera: () -> Unit = {},
    onPhoto: (ByteArray) -> Unit = {},
    onRemovePhoto: () -> Unit = {},
    onScaleModeChange: (Boolean) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .consumeOverscroll()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AapsSpacing.xxLarge)
            .padding(bottom = AapsSpacing.xxLarge),
        verticalArrangement = Arrangement.spacedBy(AapsSpacing.large)
    ) {
        Text(
            text = stringResource(if (isEditing) CoreUiStrings.edit_food else CoreUiStrings.add_food),
            style = MaterialTheme.typography.titleLarge
        )

        // The photo of the user, or else the package from OFF, to check the values against it.
        // The OFF photo is downloaded again when the file is missing.
        UserPhotoSection(
            shown = state.editorUserPhoto ?: state.editorImage,
            canRemove = state.editorUserPhoto != null,
            showCamera = state.showPhotoCamera,
            onOpenCamera = onOpenPhotoCamera,
            onCloseCamera = onClosePhotoCamera,
            onPhoto = onPhoto,
            onRemove = onRemovePhoto,
        )

        state.editorOffProduct?.let {
            OffEditorBanner(product = it, perServing = state.editorOffPerServing, onPerServingChange = onOffPerServingChange)
        }

        OutlinedTextField(
            value = state.editorName,
            onValueChange = onNameChange,
            label = { Text(stringResource(CoreUiStrings.name)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = state.editorSaveAttempted && state.editorName.isBlank()
        )

        // One field per row: the list arrow takes width, and a long label must not wrap.
        // Only the subcategories of the chosen category are proposed.
        SuggestionField(
            value = state.editorCategory,
            onValueChange = onCategoryChange,
            label = stringResource(CoreUiStrings.category),
            suggestions = categories,
            modifier = Modifier.fillMaxWidth()
        )
        SuggestionField(
            value = state.editorSubCategory,
            onValueChange = onSubCategoryChange,
            label = stringResource(CoreUiStrings.subcategory),
            suggestions = subCategories,
            modifier = Modifier.fillMaxWidth()
        )

        // Portion mode: the values follow the portion. Values mode: every field is typed, for example
        // to fix an OFF value or to count a food in biscuits instead of grams.
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val modes = listOf(true to UiStrings.food_editor_mode_portion, false to UiStrings.food_editor_mode_values)
            modes.forEachIndexed { index, (scale, label) ->
                SegmentedButton(
                    selected = state.editorScaleMode == scale,
                    onClick = { onScaleModeChange(scale) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size)
                ) { Text(stringResource(label)) }
            }
        }
        Text(
            text = stringResource(if (state.editorScaleMode) UiStrings.food_editor_mode_portion_hint else UiStrings.food_editor_mode_values_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val valuesEditable = !state.editorScaleMode

        Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.large)) {
            OutlinedTextField(
                value = state.editorPortion,
                onValueChange = onPortionChange,
                label = { Text(stringResource(CoreUiStrings.portion)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            OutlinedTextField(
                value = state.editorUnit,
                onValueChange = onUnitChange,
                label = { Text(stringResource(CoreUiStrings.unit_label)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = valuesEditable
            )
        }

        OutlinedTextField(
            value = state.editorCarbs,
            onValueChange = onCarbsChange,
            label = { Text(stringResource(CoreUiStrings.label_carbs_g)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = valuesEditable,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.large)) {
            OutlinedTextField(
                value = state.editorFat,
                onValueChange = onFatChange,
                label = { Text(stringResource(CoreUiStrings.label_fat_g)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = valuesEditable,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            OutlinedTextField(
                value = state.editorProtein,
                onValueChange = onProteinChange,
                label = { Text(stringResource(CoreUiStrings.label_protein_g)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = valuesEditable,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            OutlinedTextField(
                value = state.editorEnergy,
                onValueChange = onEnergyChange,
                label = { Text(stringResource(CoreUiStrings.label_energy_kj)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = valuesEditable,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
        }

        OutlinedTextField(
            value = state.editorNote,
            onValueChange = onNoteChange,
            label = { Text(stringResource(CoreUiStrings.notes_label)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )

        Spacer(Modifier.height(AapsSpacing.medium))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onDelete != null) {
                TextButton(onClick = onDelete) {
                    Text(
                        stringResource(CoreUiStrings.delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                Spacer(Modifier.width(AapsSpacing.extraSmall))
            }
            Row {
                TextButton(onClick = onCancel) {
                    Text(stringResource(CoreUiStrings.cancel))
                }
                Spacer(Modifier.width(AapsSpacing.medium))
                TextButton(
                    onClick = onSave,
                    enabled = !state.editorSaveAttempted || state.editorName.isNotBlank()
                ) {
                    Text(stringResource(CoreUiStrings.save))
                }
            }
        }
    }
}
