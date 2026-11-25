package app.aaps.ui.compose.foodManagement

import app.aaps.core.data.model.FD
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.food.FoodCatalog
import app.aaps.core.interfaces.food.FoodChoice
import app.aaps.core.interfaces.food.FoodItemKind
import app.aaps.core.interfaces.food.FoodItemRef
import app.aaps.core.interfaces.food.FoodSendTarget
import app.aaps.core.interfaces.food.PreparedMeal
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.ui.compose.foodManagement.book.FoodBookMath
import app.aaps.ui.compose.foodManagement.book.FoodBookRepository
import app.aaps.ui.compose.foodManagement.book.MealTarget
import app.aaps.ui.compose.foodManagement.book.Recipe
import app.aaps.ui.compose.foodManagement.book.RecipeKind
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding

/**
 * The food library of the POC for other features (the NFC food command): the foods of the database,
 * and the dishes and standard meals of the POC file.
 *
 * PROOF OF CONCEPT. A meal is built as the food screen builds it ([MealLines]): one portion of a
 * food, one serving of a dish, or all the lines of a standard meal. The meal history gets the same
 * copy as a meal sent from the screen, and finds its treatment in the same way, by its note.
 */
@ContributesIntoSet(AppScope::class, binding = binding<FoodCatalog>())
@Inject
class PocFoodCatalog(
    private val persistenceLayer: PersistenceLayer,
    private val foodBook: FoodBookRepository,
    private val rh: TextResolver,
    private val aapsLogger: AAPSLogger,
) : FoodCatalog {

    /** What a meal is made of: its lines, the name kept in the history and the photo of the dish or meal. */
    private class Content(val lines: List<MealItem>, val historyName: String?, val displayName: String, val photo: String?)

    override suspend fun choices(): List<FoodChoice> {
        val (foods, recipes) = load()
        val foodChoices = foods.values.map { FoodChoice(FoodItemRef(FoodItemKind.FOOD, it.id.toString()), it.name, it.carbs.toDouble()) }
        val recipeChoices = recipes.values.mapNotNull { recipe ->
            val carbs = FoodBookMath.nutritionOf(recipe, foods, recipes)?.carbs ?: return@mapNotNull null
            FoodChoice(FoodItemRef(kindOf(recipe), recipe.id), recipe.name, carbs)
        }
        return (foodChoices + recipeChoices).sortedBy { it.name.lowercase() }
    }

    override suspend fun prepare(ref: FoodItemRef): PreparedMeal? {
        val (foods, recipes) = load()
        val lines = MealLines(rh, foods, recipes, keys())
        val content = contentOf(ref, foods, recipes, lines) ?: return null
        return PreparedMeal(ref, content.displayName, MealMath.wizardCarbs(content.lines), lines.noteFor(content.lines))
    }

    override suspend fun recordSent(meal: PreparedMeal, target: FoodSendTarget, timestamp: Long) {
        val (foods, recipes) = load()
        val lines = MealLines(rh, foods, recipes, keys())
        val content = contentOf(meal.ref, foods, recipes, lines)
        if (content == null) {
            aapsLogger.error(LTag.UI, "Food POC: ${meal.ref} was deleted before its meal was kept")
            return
        }
        val mealTarget = when (target) {
            FoodSendTarget.CARBS  -> MealTarget.CARBS
            FoodSendTarget.WIZARD -> MealTarget.WIZARD
        }
        foodBook.addEatenMeal(lines.eatenMeal(content.lines, content.historyName, mealTarget, timestamp, photo = content.photo))
    }

    private suspend fun load(): Pair<Map<Long, FD>, Map<String, Recipe>> {
        foodBook.load()
        return persistenceLayer.getFoods().associateBy { it.id } to foodBook.book.value.recipes.associateBy { it.id }
    }

    /** The same lines as the carbs and wizard buttons of the food screen. */
    private fun contentOf(ref: FoodItemRef, foods: Map<Long, FD>, recipes: Map<String, Recipe>, lines: MealLines): Content? =
        when (ref.kind) {
            FoodItemKind.FOOD -> {
                val food = ref.id.toLongOrNull()?.let { foods[it] }
                food?.let { Content(listOf(lines.foodLine(it, if (it.portion > 0) it.portion else 1.0)), historyName = null, displayName = it.name, photo = null) }
            }

            FoodItemKind.DISH,
            FoodItemKind.MEAL -> {
                val recipe = recipes[ref.id]?.takeIf { kindOf(it) == ref.kind }
                val items = recipe?.let { if (it.kind == RecipeKind.MEAL) lines.linesOf(it) else lines.recipeLine(it, 1.0)?.let { line -> listOf(line) } }
                if (recipe == null || items == null) null else Content(items, recipe.name, recipe.name, recipe.photo)
            }
        }

    private fun kindOf(recipe: Recipe): FoodItemKind = if (recipe.kind == RecipeKind.MEAL) FoodItemKind.MEAL else FoodItemKind.DISH

    private fun keys(): () -> Long {
        var key = 1L
        return { key++ }
    }
}
