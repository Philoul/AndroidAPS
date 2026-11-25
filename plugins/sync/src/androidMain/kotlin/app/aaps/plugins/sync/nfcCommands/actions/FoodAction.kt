package app.aaps.plugins.sync.nfcCommands.actions

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import app.aaps.core.interfaces.food.FoodCatalog
import app.aaps.core.interfaces.food.FoodItemKind
import app.aaps.core.interfaces.food.FoodItemRef
import app.aaps.core.interfaces.food.FoodSendTarget
import app.aaps.core.interfaces.food.PreparedMeal
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.navigation.color
import app.aaps.core.ui.compose.navigation.icon
import app.aaps.plugins.sync.SyncStrings
import app.aaps.plugins.sync.nfcCommands.ArgType
import app.aaps.plugins.sync.nfcCommands.NfcExecutionResult
import app.aaps.plugins.sync.nfcCommands.NfcParams

/**
 * Sends an item of the food library (a food, a dish or a standard meal) as a carbs entry or to the
 * bolus wizard.
 *
 * There is no entry dialog: the scan shows the usual NFC confirmation, the same as [CarbsAction] and
 * [BolusWizardAction], with the carbs of the item. The work is done by those two actions; this one
 * only gives them the carbs of the item and the meal note, and keeps the meal in the meal history
 * once the treatment is saved. The note is what links the treatment to the meal in that history.
 *
 * An item that is not in the library any more fails like a profile or a scene that is not found: the
 * confirmation says so, and OK returns a failure.
 *
 * @param catalog the food library, or null when this build has none: the command is then not offered.
 */
class FoodAction(
    aapsLogger: AAPSLogger,
    rh: TextResolver,
    uel: UserEntryLogger,
    private val catalog: FoodCatalog?,
    private val carbsAction: CarbsAction,
    private val wizardAction: BolusWizardAction,
    private val dateUtil: DateUtil
) : NfcAction(aapsLogger, rh, uel) {

    override val label: TextRef = SyncStrings.nfccommands_cmd_food
    override val elementType = ElementType.FOOD_MANAGEMENT

    // The wizard options and the percentage are kept also for a carbs entry, so a change of the
    // target in the form does not lose them. The form shows them only for the wizard.
    override val argType = listOf(ArgType.FOOD_ITEM, ArgType.FOOD_TARGET, ArgType.BOLUS_WIZARD_OPTIONS, ArgType.PERCENT)
    override val icon
        get() = elementType.icon()

    private val targetType: ElementType
        get() = if (params.foodTarget == FoodSendTarget.WIZARD) ElementType.BOLUS_WIZARD else ElementType.CARBS

    override val secondaryIcon: ImageVector
        get() = targetType.icon()
    override val secondaryIconColor: @Composable () -> Color
        get() = { targetType.color() }

    override fun isSupported(): Boolean = catalog != null

    override suspend fun getDefaultParams(): NfcParams {
        val first = catalog?.choices()?.firstOrNull()
        return wizardAction.getDefaultParams().copy(
            carbs = null,
            foodKind = first?.ref?.kind,
            foodId = first?.ref?.id,
            foodName = first?.name,
            foodTarget = FoodSendTarget.CARBS
        )
    }

    override suspend fun formatParams(tagName: String): String {
        val meal = prepare() ?: return notFoundText()
        val detail = when (params.foodTarget) {
            FoodSendTarget.WIZARD -> wizardFor(meal).formatParams(tagName)
            else                  -> carbsFor(meal).formatParams(tagName)
        } ?: return meal.name
        return rh.gs(SyncStrings.nfccommands_food_summary, meal.name, detail)
    }

    override suspend fun execute(tagName: String): NfcExecutionResult {
        val target = params.foodTarget ?: return invalidFormat()
        val meal = prepare() ?: return NfcExecutionResult(false, notFoundText())
        val sentAt = dateUtil.now()
        val result = when (target) {
            FoodSendTarget.WIZARD -> wizardFor(meal).execute(tagName)
            FoodSendTarget.CARBS  -> carbsFor(meal).execute(tagName)
        }
        if (result.success) {
            // The treatment is saved: a failure here only loses the line in the meal history.
            runCatching { catalog?.recordSent(meal, target, sentAt) }
                .onFailure { aapsLogger.error(LTag.NFC, "Food command: the meal was not kept in the meal history", it) }
        }
        return result
    }

    /** The meal of the item on the tag, or null when it is not in the library any more. */
    private suspend fun prepare(): PreparedMeal? {
        val kind: FoodItemKind = params.foodKind ?: return null
        val id = params.foodId ?: return null
        return catalog?.prepare(FoodItemRef(kind, id))
    }

    private fun notFoundText(): String =
        rh.gs(SyncStrings.nfccommands_food_not_found, params.foodName ?: params.foodId.orEmpty())

    private fun carbsFor(meal: PreparedMeal): CarbsAction = carbsAction.apply {
        params = NfcParams(carbs = meal.carbs)
        treatmentNote = meal.note
    }

    // The same parameters each time for the same meal: the wizard keeps its preview under them,
    // between the confirmation and the OK.
    private fun wizardFor(meal: PreparedMeal): BolusWizardAction = wizardAction.apply {
        params = this@FoodAction.params.copy(carbs = meal.carbs)
        treatmentNote = meal.note
    }
}
