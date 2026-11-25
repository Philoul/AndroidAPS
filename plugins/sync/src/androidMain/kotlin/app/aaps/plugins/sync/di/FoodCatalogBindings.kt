package app.aaps.plugins.sync.di

import app.aaps.core.interfaces.food.FoodCatalog
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Multibinds

/**
 * The food libraries of this build, for the NFC food command.
 *
 * A build without a food library contributes nothing. `allowEmpty` makes that legal, and the empty
 * set tells the NFC screen not to offer the food command.
 */
@ContributesTo(AppScope::class)
interface FoodCatalogBindings {

    @Multibinds(allowEmpty = true)
    val foodCatalogs: Set<FoodCatalog>
}
