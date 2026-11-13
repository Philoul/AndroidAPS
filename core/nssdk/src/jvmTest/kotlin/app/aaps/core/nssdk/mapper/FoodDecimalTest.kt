package app.aaps.core.nssdk.mapper

import app.aaps.core.nssdk.localmodel.food.NSFood
import app.aaps.core.nssdk.nsSdkJson
import app.aaps.core.nssdk.remotemodel.RemoteFood
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Food values keep their decimals (14.8 g) in Nightscout, and a whole value is still written as a
 * whole number, so older AAPS versions keep reading the food list.
 */
class FoodDecimalTest {

    private fun food(carbs: Double, fat: Double? = null, protein: Double? = null, energy: Double? = null) = NSFood(
        date = 1000, identifier = "id", isValid = true, name = "Apple", portion = 100.0,
        carbs = carbs, fat = fat, protein = protein, energy = energy
    )

    private fun upload(food: NSFood): String = nsSdkJson.encodeToString(RemoteFood.serializer(), food.toRemoteFood())

    @Test
    fun `decimals from Nightscout are read as they are`() {
        val json = """{"type":"food","name":"Bread","portion":100.0,"carbs":48.6,"fat":3.2,"protein":8.9,"energy":1050.5}"""

        val food = requireNotNull(json.toNSFood())

        assertThat(food.carbs).isEqualTo(48.6)
        assertThat(food.fat).isEqualTo(3.2)
        assertThat(food.protein).isEqualTo(8.9)
        assertThat(food.energy).isEqualTo(1050.5)
    }

    @Test
    fun `whole numbers from Nightscout are read as before`() {
        val json = """{"type":"food","name":"Apple","portion":100.0,"carbs":12,"energy":218}"""

        val food = requireNotNull(json.toNSFood())

        assertThat(food.carbs).isEqualTo(12.0)
        assertThat(food.energy).isEqualTo(218.0)
        assertThat(food.fat).isNull()
    }

    @Test
    fun `a decimal is uploaded with its decimals`() {
        val json = upload(food(carbs = 14.8, fat = 0.4))

        assertThat(json).contains("\"carbs\":14.8")
        assertThat(json).contains("\"fat\":0.4")
    }

    @Test
    fun `a whole value is uploaded as a whole number, not 12_0`() {
        val json = upload(food(carbs = 12.0, energy = 218.0))

        assertThat(json).contains("\"carbs\":12,")
        assertThat(json).contains("\"energy\":218")
        assertThat(json).doesNotContain("12.0")
    }

    /** What an older AAPS version reads: whole numbers only. A food without decimals must still read. */
    @Serializable
    private data class OlderAapsFood(val name: String, val carbs: Int = 0, val fat: Int? = null, val protein: Int? = null, val energy: Int? = null)

    @Test
    fun `an older AAPS version still reads a food without decimals`() {
        val olderJson = Json { ignoreUnknownKeys = true }
        val json = upload(food(carbs = 12.0, fat = 0.0, protein = 1.0, energy = 218.0))

        val read = olderJson.decodeFromString(OlderAapsFood.serializer(), json)

        assertThat(read).isEqualTo(OlderAapsFood(name = "Apple", carbs = 12, fat = 0, protein = 1, energy = 218))
    }

    @Test
    fun `the values come back the same after an upload and a download`() {
        val food = food(carbs = 14.8, fat = 0.4, protein = 9.0, energy = 1050.5)

        val back = requireNotNull(upload(food).toNSFood())

        assertThat(back.carbs).isEqualTo(14.8)
        assertThat(back.fat).isEqualTo(0.4)
        assertThat(back.protein).isEqualTo(9.0)
        assertThat(back.energy).isEqualTo(1050.5)
    }

    @Test
    fun `a decimal in a list does not lose the other foods`() {
        val json = """
            [{"type":"food","name":"Apple","portion":100.0,"carbs":12,"unit":"g"},
             {"type":"food","name":"Bread","portion":50.0,"carbs":24.3,"unit":"g"}]
        """.trimIndent()

        val foods = nsSdkJson.decodeFromString(ListSerializer(RemoteFood.serializer()), json).mapNotNull { it.toNSFood() }

        assertThat(foods.map { it.carbs }).containsExactly(12.0, 24.3).inOrder()
    }

    @Test
    fun `text that is not a number is still refused`() {
        val json = """{"type":"food","name":"Apple","portion":100.0,"carbs":"a lot"}"""

        assertThrows<SerializationException> { json.toNSFood() }
    }
}
