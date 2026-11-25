package app.aaps.ui.compose.foodManagement

import app.aaps.core.data.model.FD
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.ui.CoreUiStrings
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.foodManagement.book.EatenItem
import app.aaps.ui.compose.foodManagement.book.EatenMeal
import app.aaps.ui.compose.foodManagement.book.FoodBookMath
import app.aaps.ui.compose.foodManagement.book.MealTarget
import app.aaps.ui.compose.foodManagement.book.Recipe
import app.aaps.ui.compose.foodManagement.book.RecipeItem
import kotlin.math.roundToInt
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Builds the lines of a meal from foods, dishes and standard meals, the note given to the dialogs,
 * and the copy kept in the meal history.
 *
 * Used by the food screen and by `PocFoodCatalog` (the NFC food command), so a meal sent from a tag
 * is the same as a meal sent from the screen.
 *
 * @param nextKey gives the key of each new line
 */
internal class MealLines(
    private val rh: TextResolver,
    private val foods: Map<Long, FD>,
    private val recipes: Map<String, Recipe>,
    private val nextKey: () -> Long,
) {

    fun foodLine(food: FD, quantity: Double): MealItem {
        val nutrition = FoodBookMath.nutritionOf(food, quantity)
        return MealItem(
            key = nextKey(),
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

    /** Fat, protein and energy are not known for a line typed by hand, so the meal shows them as not known. */
    fun handLine(name: String, carbs: Double, key: Long = nextKey()) =
        MealItem(key = key, name = name, quantity = 1.0, unit = "", carbs = carbs, estimated = true)

    fun recipeLine(recipe: Recipe, servings: Double): MealItem? {
        val nutrition = FoodBookMath.nutritionOf(recipe, foods, recipes)?.times(servings) ?: return null
        return MealItem(
            key = nextKey(),
            name = recipe.name,
            quantity = servings,
            unit = rh.gs(UiStrings.food_book_servings),
            carbs = nutrition.carbs,
            fat = nutrition.fat,
            protein = nutrition.protein,
            energyKj = nutrition.energyKj,
            recipeId = recipe.id,
            partial = nutrition.partial,
        )
    }

    /** The lines of a dish or meal as meal lines, or null when one of them cannot be computed. */
    fun linesOf(recipe: Recipe): List<MealItem>? = recipe.items.map { item -> lineOf(item) ?: return null }

    fun lineOf(item: RecipeItem): MealItem? =
        item.estimatedCarbs?.let { handLine(item.name, it) }
            ?: item.foodId?.let { id -> foods[id]?.takeUnless { FoodBookMath.unitChanged(item, it) }?.let { foodLine(it, item.quantity) } }
            ?: item.recipeId?.let { id -> recipes[id]?.let { recipeLine(it, item.quantity) } }

    /**
     * The note the wizard stores with the bolus and carbs, for example
     * "Bread 80 g (40 g), Milk 250 ml (12 g)".
     */
    fun noteFor(items: List<MealItem>): String =
        items
            .map { item ->
                rh.gs(
                    UiStrings.food_meal_note_item,
                    item.name,
                    if (item.estimated) rh.gs(UiStrings.food_meal_estimated) else rh.gs(CoreUiStrings.value_with_unit, MealMath.formatAmount(item.quantity), item.unit),
                    rh.gs(InterfacesStrings.format_carbs, item.carbs.roundToInt())
                )
            }
            .reduceOrNull { acc, next -> rh.gs(UiStrings.food_meal_note_join, acc, next) }
            .orEmpty()

    /** The copy of a meal kept in the meal history, with every value at [timestamp]. */
    @OptIn(ExperimentalUuidApi::class)
    fun eatenMeal(items: List<MealItem>, name: String?, target: MealTarget, timestamp: Long, changed: Boolean = false, photo: String? = null) =
        EatenMeal(
            id = Uuid.random().toString(),
            timestamp = timestamp,
            name = name,
            note = noteFor(items),
            wizardCarbs = MealMath.wizardCarbs(items),
            items = items.map {
                EatenItem(
                    it.name, it.quantity, it.unit, it.carbs, it.fat, it.protein, it.energyKj, estimated = it.estimated, foodId = it.foodId, recipeId = it.recipeId,
                    photo = it.recipeId?.let { id -> recipes[id]?.photo }, partial = it.partial
                )
            },
            target = target,
            changed = changed,
            photo = photo,
        )
}
