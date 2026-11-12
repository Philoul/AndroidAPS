package app.aaps.database.entities

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.aaps.database.entities.embedments.InterfaceIDs
import app.aaps.database.entities.interfaces.TraceableDBEntry

@Entity(
    tableName = TABLE_FOODS,
    foreignKeys = [ForeignKey(
        entity = Food::class,
        parentColumns = ["id"],
        childColumns = ["referenceId"]
    )],
    indices = [
        Index("nightscoutId"),
        Index("referenceId"),
        Index("barcode")
    ]
)
data class Food(
    @PrimaryKey(autoGenerate = true)
    override var id: Long = 0,
    override var version: Int = 0,
    override var dateCreated: Long = -1,
    override var isValid: Boolean = true,
    override var referenceId: Long? = null,
    @Embedded
    override var interfaceIDs_backing: InterfaceIDs? = null,
    var name: String,
    var category: String? = null,
    var subCategory: String? = null,
    // Example:
    // name="juice" portion=250 units="ml" carbs=12
    // means 250ml of juice has 12g of carbs

    var portion: Double, // common portion in "units"
    var carbs: Double, // in grams
    var fat: Double? = null, // in grams
    var protein: Double? = null, // in grams
    var energy: Double? = null, // in kJ
    var unit: String = "g",
    var gi: Int? = null, // not used yet
    // Local only: Nightscout does not know the fields below. See contentEqualsTo.
    var barcode: String? = null, // barcode of the product, when imported from Open Food Facts
    var image: String? = null, // picture file name in the app files (Open Food Facts or user photo), null when none
    var note: String? = null, // free text written by the user
    var fiber: Double? = null // in grams, as written on the package

) : TraceableDBEntry {

    /**
     * Compares the fields that Nightscout knows. The local only fields (`barcode`, `image`, `note`,
     * `fiber`) are left out on purpose, here and in [copyFrom]: a food that came from Nightscout and was
     * completed on the phone must keep them when Nightscout sends the same food again.
     */
    fun contentEqualsTo(other: Food): Boolean {
        if (isValid != other.isValid) return false
        if (portion != other.portion) return false
        if (carbs != other.carbs) return false
        if (fat != other.fat) return false
        if (protein != other.protein) return false
        if (energy != other.energy) return false
        if (gi != other.gi) return false
        if (name != other.name) return false
        if (category != other.category) return false
        if (subCategory != other.subCategory) return false
        return unit == other.unit
    }

    fun copyFrom(other: Food) {
        isValid = other.isValid
        name = other.name
        category = other.category
        subCategory = other.subCategory
        portion = other.portion
        carbs = other.carbs
        fat = other.fat
        protein = other.protein
        energy = other.energy
        unit = other.unit
        gi = other.gi
        interfaceIDs.nightscoutId = other.interfaceIDs.nightscoutId
    }
}