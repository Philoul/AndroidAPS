package app.aaps.plugins.sync.nsclientV3.extensions

import app.aaps.core.data.model.FD
import app.aaps.core.data.model.IDs
import app.aaps.core.nssdk.localmodel.food.NSFood
import kotlin.math.roundToInt
import kotlin.time.Clock

fun NSFood.toFood(): FD =
    FD(
        isValid = isValid,
        name = name,
        category = category,
        subCategory = subCategory,
        portion = portion,
        carbs = carbs.toDouble(),
        fat = fat?.toDouble(),
        protein = protein?.toDouble(),
        energy = energy?.toDouble(),
        unit = unit,
        gi = gi,
        ids = IDs(nightscoutId = identifier)
    )

fun FD.toNSFood(): NSFood =
    NSFood(
        date = Clock.System.now().toEpochMilliseconds(),
        isValid = isValid,
        name = name,
        category = category,
        subCategory = subCategory,
        portion = portion,
        carbs = carbs.roundToInt(),
        fat = fat?.roundToInt(),
        protein = protein?.roundToInt(),
        energy = energy?.roundToInt(),
        unit = unit,
        gi = gi,
        identifier = ids.nightscoutId,
    )
