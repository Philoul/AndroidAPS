package app.aaps.ui.compose.foodManagement

import app.aaps.ui.compose.foodManagement.openfoodfacts.OffProduct

/**
 * Turns an Open Food Facts (OFF) product into the values of a food.
 *
 * OFF gives every value for 100 g (or 100 ml). The food keeps its values for one portion. The values
 * are computed from the exact OFF values each time, so a portion changed twice is never computed
 * from a value already rounded.
 */
internal object OffFoodValues {

    /**
     * The food an OFF product becomes in the editor.
     *
     * With [perServing] and a serving known to OFF, one portion is that serving: "1 pot (125 g)" gives
     * portion 1 and unit "pot (125 g)", "125 g" gives portion 125 and unit "g". Otherwise one portion
     * is 100 g (or 100 ml).
     */
    fun from(product: OffProduct, perServing: Boolean = true): OffFood {
        val portion = (if (perServing) servingPortion(product) else null) ?: per100Portion(product)
        return OffFood(
            // The brand helps to tell two close products apart in the list. It is data, not a text
            // of the app, so it is not translated.
            name = if (product.brand != null) "${product.name} (${product.brand})" else product.name,
            portion = portion.portion,
            unit = portion.unit,
            gramsPerUnit = portion.gramsPerUnit,
            nutrients = nutrientsFor(product, portion.portion, portion.gramsPerUnit),
        )
    }

    /** The serving OFF knows for this product, or null when OFF has no serving weight. */
    fun servingPortion(product: OffProduct): OffPortion? {
        val grams = product.servingQuantity ?: return null
        val text = product.servingSize?.trim().orEmpty()
        if (text.isEmpty() || WEIGHT_ONLY.matches(text)) return OffPortion(grams, baseUnit(product), 1.0)
        // "125 g (1 pot)" or "125 g (Pot)": the weight first, then what it is. Turned into "1 pot (125 g)".
        WEIGHT_THEN_LABEL.matchEntire(text)?.let { match ->
            val weight = match.groupValues[1].trim()
            val label = match.groupValues[2].trim()
            return countedPortion(label, grams, weight) ?: OffPortion(1.0, "$label ($weight)", grams)
        }
        return countedPortion(text, grams, weight = null) ?: OffPortion(1.0, text, grams)
    }

    /**
     * "1 pot (125 g)" or "3 biscuits": a count, then what is counted. The count becomes the portion,
     * so the food can be counted in pots or biscuits. [weight], when known apart, is added to the unit.
     */
    private fun countedPortion(text: String, grams: Double, weight: String?): OffPortion? {
        val match = LEADING_COUNT.matchEntire(text) ?: return null
        val count = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val rest = match.groupValues[2].trim()
        if (count <= 0 || rest.isEmpty()) return null
        return OffPortion(count, if (weight != null) "$rest ($weight)" else rest, grams / count)
    }

    /**
     * Nutrition of [portion] units when one unit weighs [gramsPerUnit] g (or ml), from the exact
     * per-100 OFF values. A value OFF does not have stays null: it is never guessed.
     */
    fun nutrientsFor(product: OffProduct, portion: Double, gramsPerUnit: Double): OffNutrients {
        val factor = portion * gramsPerUnit / 100.0
        return OffNutrients(
            carbs = product.carbsPer100?.let { it * factor },
            fat = product.fatPer100?.let { it * factor },
            protein = product.proteinPer100?.let { it * factor },
            energyKj = product.energyKjPer100?.let { it * factor },
            fiber = product.fiberPer100?.let { it * factor },
        )
    }

    private fun per100Portion(product: OffProduct) = OffPortion(100.0, baseUnit(product), 1.0)

    fun baseUnit(product: OffProduct) = if (product.isLiquid) "ml" else "g"

    /** "125 g", "125g", "12.5 cl": a weight or a volume and nothing else. */
    private val WEIGHT_ONLY = Regex("""^\d+(?:[.,]\d+)?\s*(?:mg|g|gr|kg|ml|cl|dl|l)?\.?$""", RegexOption.IGNORE_CASE)

    /** "125 g (1 pot)": a weight or a volume, then a label in brackets. */
    private val WEIGHT_THEN_LABEL = Regex("""^(\d+(?:[.,]\d+)?\s*(?:mg|g|gr|kg|ml|cl|dl|l)\.?)\s*\((.+)\)$""", RegexOption.IGNORE_CASE)

    /** "1 pot (125 g)", "3 biscuits (30 g)": a count, then what is counted. */
    private val LEADING_COUNT = Regex("""^(\d+(?:[.,]\d+)?)\s+(.+)$""")
}

/** One portion of an OFF food: [portion] units of [unit], each weighing [gramsPerUnit] g (or ml). */
internal data class OffPortion(val portion: Double, val unit: String, val gramsPerUnit: Double)

/** Exact values for a portion. Null when OFF does not have the value. */
internal data class OffNutrients(val carbs: Double?, val fat: Double?, val protein: Double?, val energyKj: Double?, val fiber: Double?)

internal data class OffFood(
    val name: String,
    val portion: Double,
    val unit: String,
    val gramsPerUnit: Double,
    val nutrients: OffNutrients,
)

/**
 * The values of a food when the editor started to follow the portion: a new portion changes every
 * value in proportion. Used for a food that does not come from OFF, or no longer has its product.
 */
data class ScaleBase(val portion: Double, val carbs: Double?, val fat: Double?, val protein: Double?, val energy: Double?, val fiber: Double?)
