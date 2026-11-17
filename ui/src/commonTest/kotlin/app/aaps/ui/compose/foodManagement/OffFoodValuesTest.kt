package app.aaps.ui.compose.foodManagement

import app.aaps.ui.compose.foodManagement.openfoodfacts.OffProduct
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** An Open Food Facts product becomes a food: the portion and the values the editor is filled with. */
class OffFoodValuesTest {

    @Test
    fun `an OFF product becomes a 100 ml food, values kept with their decimals`() {
        val product = OffProduct(
            barcode = "1", name = "Lait", brand = "Ferme", carbsPer100 = 4.7, sugarsPer100 = 4.7, fiberPer100 = null,
            fatPer100 = 1.5, proteinPer100 = 3.2, energyKjPer100 = 196.0, isLiquid = true
        )
        val food = OffFoodValues.from(product)
        assertEquals("Lait (Ferme)", food.name)
        assertEquals(100.0, food.portion)
        assertEquals("ml", food.unit)
        assertEquals(4.7, food.nutrients.carbs!!, 0.0001)
        assertEquals(1.5, food.nutrients.fat!!, 0.0001)
        assertEquals(196.0, food.nutrients.energyKj!!, 0.0001)
        assertNull(food.nutrients.fiber)
    }

    @Test
    fun `missing OFF carbs stay empty instead of becoming zero`() {
        val product = OffProduct(
            barcode = "1", name = "X", brand = null, carbsPer100 = null, sugarsPer100 = null, fiberPer100 = null,
            fatPer100 = null, proteinPer100 = null, energyKjPer100 = null, isLiquid = false
        )
        val food = OffFoodValues.from(product)
        assertNull(food.nutrients.carbs)
        assertEquals("X", food.name)
        assertEquals("g", food.unit)
    }

    private fun yogurt(servingSize: String?, servingQuantity: Double?) = OffProduct(
        barcode = "1", name = "Yaourt", brand = null, carbsPer100 = 8.8, sugarsPer100 = null, fiberPer100 = 0.4,
        fatPer100 = 3.2, proteinPer100 = 4.0, energyKjPer100 = 300.0, isLiquid = false,
        servingSize = servingSize, servingQuantity = servingQuantity
    )

    @Test
    fun `a counted serving becomes the portion, with values for that serving`() {
        val food = OffFoodValues.from(yogurt("1 pot (125 g)", 125.0))
        assertEquals(1.0, food.portion)
        assertEquals("pot (125 g)", food.unit)
        assertEquals(125.0, food.gramsPerUnit)
        assertEquals(11.0, food.nutrients.carbs!!, 0.0001) // 8.8 g per 100 g, so 11 g for 125 g
        assertEquals(375.0, food.nutrients.energyKj!!, 0.0001)
        assertEquals(0.5, food.nutrients.fiber!!, 0.0001)
    }

    @Test
    fun `a serving that is only a weight becomes a portion in grams`() {
        val food = OffFoodValues.from(yogurt("125 g", 125.0))
        assertEquals(125.0, food.portion)
        assertEquals("g", food.unit)
        assertEquals(1.0, food.gramsPerUnit)
        assertEquals(11.0, food.nutrients.carbs!!, 0.0001)
    }

    @Test
    fun `a serving of several pieces counts in pieces`() {
        val portion = OffFoodValues.servingPortion(yogurt("3 biscuits (30 g)", 30.0))!!
        assertEquals(3.0, portion.portion)
        assertEquals("biscuits (30 g)", portion.unit)
        assertEquals(10.0, portion.gramsPerUnit, 0.0001)
    }

    @Test
    fun `a weight written first, with a label, becomes a counted portion`() {
        val pot = OffFoodValues.servingPortion(yogurt("125 g (1 pot)", 125.0))!!
        assertEquals(1.0, pot.portion)
        assertEquals("pot (125 g)", pot.unit)
        assertEquals(125.0, pot.gramsPerUnit)

        val plain = OffFoodValues.servingPortion(yogurt("125 g (Pot)", 125.0))!!
        assertEquals(1.0, plain.portion)
        assertEquals("Pot (125 g)", plain.unit)
    }

    @Test
    fun `without a serving weight, or when asked, the portion is 100 g`() {
        assertEquals(100.0, OffFoodValues.from(yogurt("1 pot", null)).portion)
        val per100 = OffFoodValues.from(yogurt("1 pot (125 g)", 125.0), perServing = false)
        assertEquals(100.0, per100.portion)
        assertEquals("g", per100.unit)
        assertEquals(8.8, per100.nutrients.carbs!!, 0.0001)
    }

    @Test
    fun `values for a portion come from the exact values`() {
        // 2 pots of 125 g: 22 g carbs (8.8 x 2.5).
        val nutrients = OffFoodValues.nutrientsFor(yogurt(null, null), portion = 2.0, gramsPerUnit = 125.0)
        assertEquals(22.0, nutrients.carbs!!, 0.0001)
        assertEquals(8.0, nutrients.fat!!, 0.0001)
    }
}
