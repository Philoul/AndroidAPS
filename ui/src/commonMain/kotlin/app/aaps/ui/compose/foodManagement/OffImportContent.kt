package app.aaps.ui.compose.foodManagement

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.consumeOverscroll
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.foodManagement.openfoodfacts.BarcodeScanner
import app.aaps.ui.compose.foodManagement.openfoodfacts.OffProduct
import app.aaps.ui.compose.foodManagement.openfoodfacts.OffResult
import app.aaps.ui.compose.foodManagement.openfoodfacts.barcodeScannerAvailable

/** Content of the Open Food Facts import bottom sheet: search field, optional camera, results. */
@Composable
internal fun OffImportContent(
    state: FoodManagementUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenScanner: () -> Unit,
    onCloseScanner: () -> Unit,
    onScanned: (String) -> Unit,
    onSelect: (OffProduct) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .consumeOverscroll()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AapsSpacing.xxLarge)
            .padding(bottom = AapsSpacing.xxLarge),
        verticalArrangement = Arrangement.spacedBy(AapsSpacing.large)
    ) {
        Text(text = stringResource(UiStrings.food_off_import), style = MaterialTheme.typography.titleLarge)
        OffNotice(text = stringResource(UiStrings.food_off_warning_short), details = stringResource(UiStrings.food_off_warning))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.importQuery,
                onValueChange = onQueryChange,
                label = { Text(stringResource(UiStrings.food_off_search_hint)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                // A search is sent on the key or the button only: OFF allows about 10 per minute.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() })
            )
            IconButton(onClick = onSearch, enabled = !state.importInProgress && state.importQuery.isNotBlank()) {
                Icon(Icons.Filled.Search, contentDescription = stringResource(CoreUiStrings.search))
            }
            if (barcodeScannerAvailable) {
                IconButton(onClick = if (state.showScanner) onCloseScanner else onOpenScanner) {
                    Icon(
                        imageVector = if (state.showScanner) Icons.Filled.Close else Icons.Filled.QrCodeScanner,
                        contentDescription = stringResource(UiStrings.food_off_scan)
                    )
                }
            }
        }

        if (state.showScanner) {
            BarcodeScanner(
                onScanned = onScanned,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
            )
        }

        when (val result = state.importResult) {
            null                  -> if (state.importInProgress) CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            OffResult.NotFound    -> Text(stringResource(UiStrings.food_off_not_found))
            OffResult.Offline     -> Text(stringResource(UiStrings.food_off_offline), color = MaterialTheme.colorScheme.error)
            OffResult.RateLimited -> Text(stringResource(UiStrings.food_off_rate_limited), color = MaterialTheme.colorScheme.error)
            OffResult.Failed      -> Text(stringResource(UiStrings.food_off_failed), color = MaterialTheme.colorScheme.error)
            is OffResult.Found    -> result.products.forEach { product ->
                OffProductCard(product = product, image = state.importImages[product.barcode], onClick = { onSelect(product) })
            }
        }

        Text(
            text = stringResource(UiStrings.food_off_attribution),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun OffProductCard(product: OffProduct, image: ByteArray?, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        // The photo of the package is often what tells two close products apart.
        Row(modifier = Modifier.padding(AapsSpacing.large), horizontalArrangement = Arrangement.spacedBy(AapsSpacing.large)) {
            FoodPhoto(bytes = image, size = AapsSpacing.foodThumbnailSize)
            OffProductTexts(product = product, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun OffProductTexts(product: OffProduct, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(text = product.name, style = MaterialTheme.typography.titleSmall)
        product.brand?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val unit = OffFoodValues.baseUnit(product)
        val carbs = product.carbsPer100?.let { grams(it) } ?: stringResource(UiStrings.food_off_unknown)
        Text(
            text = stringResource(InterfacesStrings.confirmation_line, stringResource(InterfacesStrings.carbs), stringResource(UiStrings.food_off_carbs_per_100, carbs, unit)),
            style = MaterialTheme.typography.bodyMedium,
            color = if (product.carbsPer100 == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )
        product.servingSize?.let {
            Text(text = stringResource(UiStrings.food_off_serving, it), style = MaterialTheme.typography.bodySmall)
        }
        Text(text = product.barcode, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Shown at the top of the food editor when it was filled from OFF: the choice between the OFF serving
 * and 100 g, the OFF values for 100 g, and a short reminder to check them.
 */
@Composable
internal fun OffEditorBanner(product: OffProduct, perServing: Boolean, onPerServingChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
        val baseUnit = OffFoodValues.baseUnit(product)
        val serving = OffFoodValues.servingPortion(product)
        if (serving != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
                FilterChip(
                    selected = perServing,
                    onClick = { onPerServingChange(true) },
                    label = { Text(product.servingSize ?: stringResource(CoreUiStrings.value_with_unit, FoodValues.formatPortion(serving.portion), serving.unit)) }
                )
                FilterChip(
                    selected = !perServing,
                    onClick = { onPerServingChange(false) },
                    label = { Text(stringResource(CoreUiStrings.value_with_unit, "100", baseUnit)) }
                )
            }
        }
        Text(stringResource(UiStrings.food_off_values_follow), style = MaterialTheme.typography.bodySmall)
        val unknown = stringResource(UiStrings.food_off_unknown)
        Text(
            text = stringResource(
                UiStrings.food_off_exact_values,
                baseUnit,
                product.carbsPer100?.let { grams(it) } ?: unknown,
                product.sugarsPer100?.let { grams(it) } ?: unknown,
                product.fiberPer100?.let { grams(it) } ?: unknown
            ),
            style = MaterialTheme.typography.bodySmall
        )
        if (product.carbsPer100 == null) {
            Text(stringResource(UiStrings.food_off_no_carbs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        OffNotice(text = stringResource(UiStrings.food_off_warning_short), details = stringResource(UiStrings.food_off_warning))
    }
}

/**
 * A one-line reminder. With [details], a tap shows the full text. Kept small on purpose: the user
 * sees it at each step, so a large red card soon gets ignored.
 */
@Composable
private fun OffNotice(text: String, details: String? = null) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (details != null) Modifier.clickable { expanded = !expanded } else Modifier),
        horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Text(
            text = if (expanded && details != null) details else text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** A weight with one decimal, like "4.7 g". */
@Composable
private fun grams(value: Double): String =
    stringResource(CoreUiStrings.value_with_unit, FoodValues.format(value), stringResource(CoreUiStrings.units_grams))
