package app.aaps.plugins.sync.nfcCommands.compose

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.food.FoodChoice
import app.aaps.core.interfaces.food.FoodItemKind
import app.aaps.core.interfaces.food.FoodItemRef
import app.aaps.core.interfaces.food.FoodSendTarget
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.sync.SyncStrings
import kotlin.math.roundToInt

/**
 * The item of the food library for the food command. The list can be long, so the field is also a
 * search: what is typed keeps the items whose name contains it.
 *
 * @param storedName the name saved on the tag, shown when the item is not in the library any more
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FoodItemPicker(
    choices: List<FoodChoice>,
    selected: FoodItemRef?,
    storedName: String?,
    onSelect: (FoodChoice) -> Unit,
) {
    if (choices.isEmpty()) {
        Text(
            text = stringResource(SyncStrings.nfccommands_food_library_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    val current = choices.firstOrNull { it.ref == selected }
    var expanded by remember { mutableStateOf(false) }
    // Null while nothing is typed: the field then shows the item chosen.
    var search by remember { mutableStateOf<String?>(null) }
    val typed = search?.trim().orEmpty()
    val shown = if (typed.isEmpty()) choices else choices.filter { it.name.contains(typed, ignoreCase = true) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it; if (!it) search = null }) {
        OutlinedTextField(
            value = search ?: current?.name ?: storedName.orEmpty(),
            onValueChange = { search = it; expanded = true },
            label = { Text(stringResource(SyncStrings.nfccommands_food_item)) },
            singleLine = true,
            isError = current == null,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded && shown.isNotEmpty(),
            onDismissRequest = { expanded = false; search = null }
        ) {
            shown.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choiceText(choice)) },
                    onClick = { onSelect(choice); expanded = false; search = null },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

@Composable
private fun choiceText(choice: FoodChoice): String {
    val kind = when (choice.ref.kind) {
        FoodItemKind.FOOD -> stringResource(SyncStrings.nfccommands_food_kind_food)
        FoodItemKind.DISH -> stringResource(SyncStrings.nfccommands_food_kind_dish)
        FoodItemKind.MEAL -> stringResource(SyncStrings.nfccommands_food_kind_meal)
    }
    val carbs = stringResource(InterfacesStrings.format_carbs, choice.carbs.roundToInt())
    return stringResource(SyncStrings.nfccommands_food_choice, choice.name, kind, carbs)
}

/** Carbs entry or bolus wizard, for the food command. */
@Composable
internal fun FoodTargetRow(target: FoodSendTarget, onChange: (FoodSendTarget) -> Unit) {
    Text(
        text = stringResource(SyncStrings.nfccommands_food_send_to),
        style = MaterialTheme.typography.bodyMedium,
    )
    val options = listOf(
        FoodSendTarget.CARBS to stringResource(InterfacesStrings.carbs),
        FoodSendTarget.WIZARD to stringResource(CoreUiStrings.boluswizard),
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = target == value,
                onClick = { onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(label)
            }
        }
    }
}
