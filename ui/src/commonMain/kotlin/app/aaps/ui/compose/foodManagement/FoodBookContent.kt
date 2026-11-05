package app.aaps.ui.compose.foodManagement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.icons.IcCalculator
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.foodManagement.book.BolusLink
import app.aaps.ui.compose.foodManagement.book.EatenMeal
import app.aaps.ui.compose.foodManagement.book.Nutrition
import app.aaps.ui.compose.foodManagement.book.Recipe
import app.aaps.ui.compose.foodManagement.book.RecipeKind
import kotlin.math.roundToInt

// PROOF OF CONCEPT 2 and 3: dishes, standard meals and the meals eaten. Kept apart from the food
// screen so the experiment is easy to see, review and throw away.

@Composable
internal fun FoodTabsRow(selected: FoodTab, onSelect: (FoodTab) -> Unit) {
    PrimaryTabRow(selectedTabIndex = selected.ordinal) {
        FoodTab.entries.forEach { tab ->
            Tab(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                text = {
                    Text(
                        stringResource(
                            when (tab) {
                                FoodTab.FOODS   -> UiStrings.food_book_tab_foods
                                FoodTab.DISHES  -> UiStrings.food_book_tab_dishes
                                FoodTab.MEALS   -> UiStrings.food_book_tab_meals
                                FoodTab.HISTORY -> CoreUiStrings.history
                            }
                        )
                    )
                }
            )
        }
    }
}

/** The saved dishes or standard meals. A tap opens one to change it. */
@Composable
internal fun RecipeList(
    recipes: List<Recipe>,
    kind: RecipeKind,
    nutritionOf: (Recipe) -> Nutrition?,
    onOpen: (Recipe) -> Unit,
    onAddToMeal: (Recipe) -> Unit,
    onCalculate: (Recipe) -> Unit,
    onDelete: (Recipe) -> Unit,
) {
    val shown = recipes.filter { it.kind == kind }
    if (shown.isEmpty()) {
        EmptyText(stringResource(if (kind == RecipeKind.DISH) UiStrings.food_book_no_dishes else UiStrings.food_book_no_meals))
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = AapsSpacing.medium, bottom = AapsSpacing.fabClearance),
        verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)
    ) {
        items(shown, key = { it.id }) { recipe ->
            RecipeCard(
                recipe = recipe,
                nutrition = nutritionOf(recipe),
                onOpen = { onOpen(recipe) },
                onAddToMeal = { onAddToMeal(recipe) },
                onCalculate = { onCalculate(recipe) },
                onDelete = { onDelete(recipe) },
            )
        }
    }
}

@Composable
private fun RecipeCard(
    recipe: Recipe,
    nutrition: Nutrition?,
    onOpen: () -> Unit,
    onAddToMeal: () -> Unit,
    onCalculate: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AapsSpacing.extraLarge),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = AapsSpacing.extraLarge, top = AapsSpacing.large, bottom = AapsSpacing.large, end = AapsSpacing.small),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = recipe.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = joinLines(recipe.items.map { item ->
                        stringResource(UiStrings.food_book_item, item.name, stringResource(CoreUiStrings.value_with_unit, MealMath.formatAmount(item.quantity), item.unit))
                    }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = nutrition?.let { stringResource(InterfacesStrings.format_carbs, it.carbs.roundToInt()) } ?: stringResource(UiStrings.food_book_unknown_total),
                style = MaterialTheme.typography.titleMedium,
                color = if (nutrition == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
            // Every card has the same buttons: name the dish on each, so a screen reader can tell them apart.
            CardIcon(Icons.Filled.AddCircleOutline, stringResource(UiStrings.food_meal_add), recipe.name, onAddToMeal)
            if (recipe.kind == RecipeKind.MEAL) CardIcon(IcCalculator, stringResource(CoreUiStrings.boluswizard), recipe.name, onCalculate)
            CardIcon(Icons.Filled.Delete, stringResource(CoreUiStrings.delete), recipe.name, onDelete)
        }
    }
}

@Composable
private fun CardIcon(icon: ImageVector, label: String, name: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = name }) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(AapsSpacing.chipIconSize), tint = MaterialTheme.colorScheme.primary)
    }
}

/** "Bread 80 g, Milk 250 ml". */
@Composable
private fun joinLines(lines: List<String>): String =
    lines.reduceOrNull { acc, next -> stringResource(UiStrings.food_meal_note_join, acc, next) }
        .orEmpty()

/** The meals sent to the bolus wizard, newest first, each with the calculation found for it. */
@Composable
internal fun EatenMealList(
    meals: List<EatenMeal>,
    links: Map<String, BolusLink>,
    timeText: (Long) -> String,
    onDelete: (EatenMeal) -> Unit,
) {
    if (meals.isEmpty()) {
        EmptyText(stringResource(UiStrings.food_book_no_history))
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = AapsSpacing.medium, bottom = AapsSpacing.fabClearance),
        verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)
    ) {
        item {
            Text(
                text = stringResource(UiStrings.food_book_history_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = AapsSpacing.extraLarge)
            )
        }
        items(meals, key = { it.id }) { meal -> EatenMealCard(meal, links[meal.id], timeText, onDelete = { onDelete(meal) }) }
    }
}

@Composable
private fun EatenMealCard(meal: EatenMeal, link: BolusLink?, timeText: (Long) -> String, onDelete: () -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AapsSpacing.extraLarge),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(AapsSpacing.extraLarge), verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = timeText(meal.timestamp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    meal.name?.let { Text(text = it, style = MaterialTheme.typography.titleSmall) }
                }
                Text(
                    text = stringResource(InterfacesStrings.format_carbs, meal.wizardCarbs),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                CardIcon(Icons.Filled.Delete, stringResource(CoreUiStrings.delete), timeText(meal.timestamp), onDelete)
            }
            meal.items.forEach { item ->
                Row {
                    Text(
                        text = stringResource(
                            UiStrings.food_meal_note_item,
                            item.name,
                            stringResource(CoreUiStrings.value_with_unit, MealMath.formatAmount(item.quantity), item.unit),
                            stringResource(InterfacesStrings.format_carbs, item.carbs.roundToInt())
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (link != null) {
                Text(
                    text = stringResource(
                        UiStrings.food_book_bolus_link,
                        timeText(link.timestamp),
                        stringResource(InterfacesStrings.format_insulin_units, link.insulin),
                        stringResource(InterfacesStrings.format_carbs, link.carbs.roundToInt())
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                if (!link.sameNote) {
                    Text(stringResource(UiStrings.food_book_bolus_link_weak), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            } else {
                Text(stringResource(UiStrings.food_book_no_bolus_link), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EmptyText(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(AapsSpacing.xxLarge), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** Asks for the name, and whether the meal is saved as a dish or as a standard meal. */
@Composable
internal fun SaveRecipeDialog(
    state: FoodManagementUiState,
    onNameChange: (String) -> Unit,
    onKindChange: (RecipeKind) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!state.showSave) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(UiStrings.food_book_save_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.large)) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val kinds = listOf(RecipeKind.DISH to UiStrings.food_book_kind_dish, RecipeKind.MEAL to UiStrings.food_book_kind_meal)
                    kinds.forEachIndexed { index, (kind, label) ->
                        SegmentedButton(
                            selected = state.saveKind == kind,
                            onClick = { onKindChange(kind) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = kinds.size)
                        ) { Text(stringResource(label)) }
                    }
                }
                OutlinedTextField(
                    value = state.saveName,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(CoreUiStrings.name)) },
                    singleLine = true,
                    isError = state.saveName.isBlank()
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = state.saveName.isNotBlank()) { Text(stringResource(CoreUiStrings.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreUiStrings.cancel)) } }
    )
}
