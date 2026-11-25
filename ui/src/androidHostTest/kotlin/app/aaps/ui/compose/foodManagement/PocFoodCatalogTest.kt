package app.aaps.ui.compose.foodManagement

import app.aaps.core.data.model.FD
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.food.FoodItemKind
import app.aaps.core.interfaces.food.FoodItemRef
import app.aaps.core.interfaces.food.FoodSendTarget
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.ui.compose.foodManagement.book.FoodBookRepository
import app.aaps.ui.compose.foodManagement.book.MealTarget
import app.aaps.ui.compose.foodManagement.book.MemoryFoodBookStore
import app.aaps.ui.compose.foodManagement.book.Recipe
import app.aaps.ui.compose.foodManagement.book.RecipeItem
import app.aaps.ui.compose.foodManagement.book.RecipeKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

internal class PocFoodCatalogTest {

    private val persistenceLayer: PersistenceLayer = mock()
    private val aapsLogger: AAPSLogger = mock()

    // Every text is "x": the tests check the carbs and the history, not the wording of the note.
    private val rh: TextResolver = mock(defaultAnswer = { if (it.method.returnType == String::class.java) "x" else null })

    private val bread = FD(id = 1, name = "Bread", portion = 100.0, carbs = 50, unit = "g")
    private val milk = FD(id = 2, name = "Milk", portion = 100.0, carbs = 5, unit = "ml")
    private val breakfast = Recipe(
        id = "meal-1", name = "Breakfast", kind = RecipeKind.MEAL,
        items = listOf(RecipeItem("Bread", 80.0, "g", foodId = 1), RecipeItem("Milk", 200.0, "ml", foodId = 2)),
        photo = "plate.jpg"
    )
    private val toast = Recipe(id = "dish-1", name = "Toast", kind = RecipeKind.DISH, items = listOf(RecipeItem("Bread", 40.0, "g", foodId = 1)))

    private lateinit var foodBook: FoodBookRepository
    private lateinit var sut: PocFoodCatalog

    @BeforeEach
    fun setUp() = runTest {
        whenever(persistenceLayer.getFoods()).thenReturn(listOf(milk, bread))
        foodBook = FoodBookRepository(MemoryFoodBookStore(), aapsLogger)
        foodBook.saveRecipe(breakfast)
        foodBook.saveRecipe(toast)
        sut = PocFoodCatalog(persistenceLayer, foodBook, rh, aapsLogger)
    }

    @Test
    fun `choices list foods, dishes and meals with their carbs, sorted by name`() = runTest {
        val choices = sut.choices()

        assertThat(choices.map { it.name }).containsExactly("Bread", "Breakfast", "Milk", "Toast").inOrder()
        assertThat(choices.first { it.name == "Bread" }.carbs).isEqualTo(50.0)
        assertThat(choices.first { it.name == "Breakfast" }.ref).isEqualTo(FoodItemRef(FoodItemKind.MEAL, "meal-1"))
        assertThat(choices.first { it.name == "Breakfast" }.carbs).isEqualTo(50.0)
        assertThat(choices.first { it.name == "Toast" }.ref.kind).isEqualTo(FoodItemKind.DISH)
    }

    @Test
    fun `a standard meal is sent with all its lines`() = runTest {
        val meal = sut.prepare(FoodItemRef(FoodItemKind.MEAL, "meal-1"))

        assertThat(meal?.carbs).isEqualTo(50)
        assertThat(meal?.name).isEqualTo("Breakfast")
    }

    @Test
    fun `a food is sent as one portion`() = runTest {
        assertThat(sut.prepare(FoodItemRef(FoodItemKind.FOOD, "1"))?.carbs).isEqualTo(50)
    }

    @Test
    fun `an item that is gone, or of another kind, is not found`() = runTest {
        assertThat(sut.prepare(FoodItemRef(FoodItemKind.FOOD, "99"))).isNull()
        assertThat(sut.prepare(FoodItemRef(FoodItemKind.DISH, "meal-1"))).isNull()
        assertThat(sut.prepare(FoodItemRef(FoodItemKind.MEAL, "gone"))).isNull()
    }

    @Test
    fun `a meal sent is kept in the history with a copy of its lines`() = runTest {
        val meal = sut.prepare(FoodItemRef(FoodItemKind.MEAL, "meal-1"))!!

        sut.recordSent(meal, FoodSendTarget.WIZARD, timestamp = 1_000L)

        val eaten = foodBook.book.value.eaten.single()
        assertThat(eaten.name).isEqualTo("Breakfast")
        assertThat(eaten.target).isEqualTo(MealTarget.WIZARD)
        assertThat(eaten.timestamp).isEqualTo(1_000L)
        assertThat(eaten.wizardCarbs).isEqualTo(50)
        assertThat(eaten.note).isEqualTo(meal.note)
        assertThat(eaten.photo).isEqualTo("plate.jpg")
        assertThat(eaten.items.map { it.name }).containsExactly("Bread", "Milk").inOrder()
    }
}
