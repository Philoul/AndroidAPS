package app.aaps.ui.compose.foodManagement.book

import app.aaps.core.data.model.BCR
import app.aaps.core.data.model.FD
import kotlinx.serialization.Serializable
import kotlin.math.abs

// PROOF OF CONCEPT (POC 2 and 3). Dishes, meals and the meals eaten are kept in one JSON file, apart
// from the AAPS database, so trying them can never touch the real data. The final storage is to be
// designed once, from what these POCs teach.

/** Everything the food POC stores. */
@Serializable
data class FoodBook(
    val recipes: List<Recipe> = emptyList(),
    val eaten: List<EatenMeal> = emptyList(),
)

@Serializable
enum class RecipeKind {

    /** Foods put together under one name, for example "Sweet yogurt" = 1 yogurt + 2 sugars. */
    DISH,

    /** A standard meal, made of foods and dishes, for example "Breakfast". */
    MEAL
}

/** A saved dish or meal. Its values are always computed from the foods it uses, never stored. */
@Serializable
data class Recipe(
    val id: String,
    val name: String,
    val kind: RecipeKind,
    val items: List<RecipeItem>,
)

/**
 * One line of a dish or a meal: a food, or another dish.
 *
 * @param name kept so the line can still be shown when its food or dish has been deleted
 * @param quantity for a food, in the unit of the food (g, ml, pot...). For a dish, in servings: 1 is
 *   the whole dish.
 * @param unit shown with the quantity
 */
@Serializable
data class RecipeItem(
    val name: String,
    val quantity: Double,
    val unit: String,
    val foodId: Long? = null,
    val recipeId: String? = null,
)

/**
 * A meal sent to the bolus wizard, with a copy of every value at that time. Changing a food or a
 * standard meal later does not change what was eaten.
 *
 * @param timestamp when the user opened the wizard with this meal
 * @param note the note given to the wizard. Used to find the bolus calculation that came from it.
 */
@Serializable
data class EatenMeal(
    val id: String,
    val timestamp: Long,
    val name: String?,
    val note: String,
    val wizardCarbs: Int,
    val items: List<EatenItem>,
)

@Serializable
data class EatenItem(
    val name: String,
    val quantity: Double,
    val unit: String,
    val carbs: Double,
    val fat: Double?,
    val protein: Double?,
    val energyKj: Double?,
)

/**
 * The bolus calculation found for an eaten meal.
 *
 * @param sameNote true when the calculation has the meal note. False when only the carbs and the
 *   time match, which is a weaker link.
 */
data class BolusLink(val timestamp: Long, val insulin: Double, val carbs: Double, val sameNote: Boolean)

/** Nutrition of a quantity. A value is null when one of the foods does not have it. */
data class Nutrition(
    val carbs: Double,
    val fat: Double?,
    val protein: Double?,
    val energyKj: Double?,
) {

    operator fun plus(other: Nutrition) = Nutrition(
        carbs = carbs + other.carbs,
        fat = sum(fat, other.fat),
        protein = sum(protein, other.protein),
        energyKj = sum(energyKj, other.energyKj),
    )

    operator fun times(factor: Double) = Nutrition(carbs * factor, fat?.times(factor), protein?.times(factor), energyKj?.times(factor))

    private fun sum(a: Double?, b: Double?): Double? = if (a == null || b == null) null else a + b

    companion object {

        val ZERO = Nutrition(0.0, 0.0, 0.0, 0.0)
    }
}

/**
 * Computes dishes and meals from the foods they use.
 *
 * A dish can hold another dish, so the computation walks down. It stops on a loop (a dish that holds
 * itself, maybe through another one) and on a food or dish that does not exist any more: the answer
 * is then null, and the user must see that the total is not known. A wrong total is never given.
 */
internal object FoodBookMath {

    /** How deep dishes may hold dishes. Far more than any real meal needs. */
    private const val MAX_DEPTH = 8

    fun nutritionOf(food: FD, quantity: Double): Nutrition {
        val factor = if (food.portion > 0) quantity / food.portion else quantity
        return Nutrition(
            carbs = food.carbs * factor,
            fat = food.fat?.let { it * factor },
            protein = food.protein?.let { it * factor },
            energyKj = food.energy?.let { it * factor },
        )
    }

    fun nutritionOf(recipe: Recipe, foods: Map<Long, FD>, recipes: Map<String, Recipe>): Nutrition? =
        nutritionOf(recipe, foods, recipes, visiting = emptyList())

    fun nutritionOf(item: RecipeItem, foods: Map<Long, FD>, recipes: Map<String, Recipe>): Nutrition? =
        nutritionOf(item, foods, recipes, visiting = emptyList())

    /** True when [items], saved as recipe [id], would hold that recipe again somewhere below. */
    fun makesLoop(id: String, items: List<RecipeItem>, recipes: Map<String, Recipe>): Boolean {
        fun holds(itemList: List<RecipeItem>, depth: Int): Boolean = itemList.any { item ->
            val child = item.recipeId ?: return@any false
            child == id || depth >= MAX_DEPTH || holds(recipes[child]?.items.orEmpty(), depth + 1)
        }
        return holds(items, 0)
    }

    /**
     * Finds, for each eaten meal, the bolus calculation the wizard saved for it: one made between
     * [slackMs] before and [windowMs] after the meal was sent, with the same note, or else with the
     * same carbs. Each calculation is given to one meal only, the oldest meal first.
     */
    fun linkBoluses(meals: List<EatenMeal>, results: List<BCR>, slackMs: Long, windowMs: Long): Map<String, BolusLink> {
        val used = mutableSetOf<Long>()
        val links = mutableMapOf<String, BolusLink>()
        for (meal in meals.sortedBy { it.timestamp }) {
            val window = results.filter { it.id !in used && it.timestamp in (meal.timestamp - slackMs)..(meal.timestamp + windowMs) }
            val byNote = window.firstOrNull { it.note == meal.note }
            val match = byNote ?: window.firstOrNull { abs(it.carbs - meal.wizardCarbs) < 0.5 } ?: continue
            used += match.id
            links[meal.id] = BolusLink(timestamp = match.timestamp, insulin = match.totalInsulin, carbs = match.carbs, sameNote = byNote != null)
        }
        return links
    }

    private fun nutritionOf(recipe: Recipe, foods: Map<Long, FD>, recipes: Map<String, Recipe>, visiting: List<String>): Nutrition? {
        if (recipe.id in visiting || visiting.size >= MAX_DEPTH) return null
        var total = Nutrition.ZERO
        for (item in recipe.items) total += nutritionOf(item, foods, recipes, visiting + recipe.id) ?: return null
        return total
    }

    private fun nutritionOf(item: RecipeItem, foods: Map<Long, FD>, recipes: Map<String, Recipe>, visiting: List<String>): Nutrition? {
        item.foodId?.let { id -> return foods[id]?.let { nutritionOf(it, item.quantity) } }
        item.recipeId?.let { id -> return recipes[id]?.let { recipe -> nutritionOf(recipe, foods, recipes, visiting)?.times(item.quantity) } }
        return null
    }
}
