package app.aaps.ui.compose.foodManagement

import kotlin.math.abs
import kotlin.math.floor
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

    /** Reads a portion: like [parse], but a portion of 0 means nothing, so it is null too. */
    fun parsePortion(text: String): Double? = parse(text)?.takeIf { it > 0 }

    /**
     * A portion as it is stored: 125.0 shows as "125", 0.25 stays "0.25". It is not rounded like
     * [format], so opening and saving a food never changes its portion.
     */
    fun formatPortion(value: Double): String =
        if (value == floor(value) && abs(value) < 1e15) value.toLong().toString() else value.toString()

    /** 80.0 shows as "80", 14.84 as "14.8". */
    fun format(value: Double): String {
        val tenths = (value * 10).roundToInt()
        return if (tenths % 10 == 0) (tenths / 10).toString() else (tenths / 10.0).toString()
    }

    /** The bolus wizard takes whole grams. */
    fun wizardCarbs(carbs: Double): Int = carbs.roundToInt()
}
