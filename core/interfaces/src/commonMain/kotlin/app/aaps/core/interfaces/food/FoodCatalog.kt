package app.aaps.core.interfaces.food

/** What can be sent from the food library: a food, a dish or a standard meal. */
enum class FoodItemKind { FOOD, DISH, MEAL }

/** Where a meal is sent. */
enum class FoodSendTarget {

    /** A carbs entry, no insulin. */
    CARBS,

    /** The bolus wizard: a bolus calculation with the carbs. */
    WIZARD
}

/**
 * The way to find an item of the food library again, for example from an NFC tag.
 *
 * @param id the id of the food as text, or the id of the dish or meal. It stays the same when the
 *   item is edited.
 */
data class FoodItemRef(val kind: FoodItemKind, val id: String)

/**
 * One item that can be chosen, with the carbs it sends: one portion of a food, one serving of a
 * dish, or the whole standard meal.
 */
data class FoodChoice(
    val ref: FoodItemRef,
    val name: String,
    val carbs: Double,
)

/**
 * A meal ready to be sent.
 *
 * @param carbs the whole grams given to the carbs entry or to the bolus wizard
 * @param note the note to store with the treatment. It lists the lines of the meal, and it is how
 *   the meal history finds the treatment of the meal.
 */
data class PreparedMeal(
    val ref: FoodItemRef,
    val name: String,
    val carbs: Int,
    val note: String,
)

/**
 * The food library (foods, dishes and standard meals) as seen from outside the food screen, for
 * example by an NFC command.
 *
 * The library can be missing from a build. It is contributed into a set, and a caller that finds
 * the set empty must not offer the feature at all, rather than offer one that does nothing.
 */
interface FoodCatalog {

    /** Every item that can be sent, sorted by name. */
    suspend fun choices(): List<FoodChoice>

    /**
     * The meal for [ref], with its carbs and its note, or null when the item does not exist any more
     * or its carbs cannot be computed (a food of a dish was deleted).
     */
    suspend fun prepare(ref: FoodItemRef): PreparedMeal?

    /**
     * Keeps [meal] in the meal history, after its treatment was saved.
     *
     * @param target where the meal was sent
     * @param timestamp when the meal was sent, just before the treatment was saved
     */
    suspend fun recordSent(meal: PreparedMeal, target: FoodSendTarget, timestamp: Long)
}
