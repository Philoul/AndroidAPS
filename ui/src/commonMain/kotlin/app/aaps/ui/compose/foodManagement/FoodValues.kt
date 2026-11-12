package app.aaps.ui.compose.foodManagement

import kotlin.math.roundToInt

/**
 * Nutrition values of a food, as the user types them and as they are shown.
 *
 * The food keeps decimals (14.8 g), as written on the package. Only the total sent to the bolus
 * wizard is rounded to whole grams, once.
 */
internal object FoodValues {

    /**
     * Reads a value typed from the package: "14.8" or "14,8". Null when empty, negative or not a
     * number.
     */
    fun parse(text: String): Double? =
        text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 && it.isFinite() }

    /** 80.0 shows as "80", 14.84 as "14.8". */
    fun format(value: Double): String {
        val tenths = (value * 10).roundToInt()
        return if (tenths % 10 == 0) (tenths / 10).toString() else (tenths / 10.0).toString()
    }

    /** The bolus wizard takes whole grams. */
    fun wizardCarbs(carbs: Double): Int = carbs.roundToInt()
}
