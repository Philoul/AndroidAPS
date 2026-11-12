package app.aaps.database.transactions

import app.aaps.database.DelegatedAppDatabase
import app.aaps.database.daos.FoodDao
import app.aaps.database.entities.Food
import app.aaps.database.entities.embedments.InterfaceIDs
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SyncNsFoodTransactionTest {

    private lateinit var database: DelegatedAppDatabase
    private lateinit var foodDao: FoodDao

    @BeforeEach
    fun setup() {
        foodDao = mock()
        database = mock()
        whenever(database.foodDao).thenReturn(foodDao)
    }

    @Test
    fun `inserts new food when nsId not found`() = runTest {
        val food = createFood(id = 0, nsId = "ns-123", name = "Apple", carbs = 15.0)

        whenever(foodDao.findByNSId("ns-123")).thenReturn(null)

        val transaction = SyncNsFoodTransaction(listOf(food))
        transaction.database = database
        val result = transaction.run()

        assertThat(result.inserted).hasSize(1)
        assertThat(result.inserted[0]).isEqualTo(food)
        assertThat(result.updated).isEmpty()
        assertThat(result.invalidated).isEmpty()

        verify(foodDao).insertNewEntry(food)
    }

    @Test
    fun `updates food when content changes`() = runTest {
        val food = createFood(id = 0, nsId = "ns-123", name = "Apple", carbs = 20.0)
        val existing = createFood(id = 1, nsId = "ns-123", name = "Apple", carbs = 15.0)

        whenever(foodDao.findByNSId("ns-123")).thenReturn(existing)

        val transaction = SyncNsFoodTransaction(listOf(food))
        transaction.database = database
        val result = transaction.run()

        assertThat(result.updated).hasSize(1)
        assertThat(result.updated[0]).isEqualTo(existing)
        assertThat(result.inserted).isEmpty()
        assertThat(result.invalidated).isEmpty()

        verify(foodDao).updateExistingEntry(existing)
        verify(foodDao, never()).insertNewEntry(any())
    }

    @Test
    fun `invalidates food when valid becomes invalid`() = runTest {
        val food = createFood(id = 0, nsId = "ns-123", name = "Apple", carbs = 15.0, isValid = false)
        val existing = createFood(id = 1, nsId = "ns-123", name = "Apple", carbs = 15.0, isValid = true)

        whenever(foodDao.findByNSId("ns-123")).thenReturn(existing)

        val transaction = SyncNsFoodTransaction(listOf(food))
        transaction.database = database
        val result = transaction.run()

        assertThat(result.invalidated).hasSize(1)
        assertThat(result.invalidated[0]).isEqualTo(existing)
        assertThat(result.updated).isEmpty()
        assertThat(result.inserted).isEmpty()

        verify(foodDao).updateExistingEntry(existing)
    }

    @Test
    fun `does not update when content is same`() = runTest {
        val food = createFood(id = 0, nsId = "ns-123", name = "Apple", carbs = 15.0)
        val existing = createFood(id = 1, nsId = "ns-123", name = "Apple", carbs = 15.0)

        whenever(foodDao.findByNSId("ns-123")).thenReturn(existing)

        val transaction = SyncNsFoodTransaction(listOf(food))
        transaction.database = database
        val result = transaction.run()

        assertThat(result.updated).isEmpty()
        assertThat(result.invalidated).isEmpty()
        assertThat(result.inserted).isEmpty()

        verify(foodDao, never()).updateExistingEntry(any())
        verify(foodDao, never()).insertNewEntry(any())
    }

    @Test
    fun `handles multiple foods`() = runTest {
        val food1 = createFood(id = 0, nsId = "ns-1", name = "Apple", carbs = 15.0)
        val food2 = createFood(id = 0, nsId = "ns-2", name = "Banana", carbs = 25.0)

        whenever(foodDao.findByNSId("ns-1")).thenReturn(null)
        whenever(foodDao.findByNSId("ns-2")).thenReturn(null)

        val transaction = SyncNsFoodTransaction(listOf(food1, food2))
        transaction.database = database
        val result = transaction.run()

        assertThat(result.inserted).hasSize(2)
    }

    @Test
    fun `a food completed on the phone keeps its local fields when Nightscout sends it again`() = runTest {
        // Nightscout does not know barcode, photo, note and fiber, so it always sends them empty.
        val existing = createFood(id = 1, nsId = "ns-123", name = "Apple", carbs = 15.0).apply {
            barcode = "3017620422003"
            image = "food_1.jpg"
            note = "green ones only"
            fiber = 2.4
        }
        whenever(foodDao.findByNSId("ns-123")).thenReturn(existing)

        // The same food: nothing to write.
        val same = SyncNsFoodTransaction(listOf(createFood(id = 0, nsId = "ns-123", name = "Apple", carbs = 15.0)))
        same.database = database
        assertThat(same.run().updated).isEmpty()
        verify(foodDao, never()).updateExistingEntry(any())

        // Changed in Nightscout: the new values are taken, the local fields stay.
        val changed = SyncNsFoodTransaction(listOf(createFood(id = 0, nsId = "ns-123", name = "Apple", carbs = 14.5)))
        changed.database = database
        changed.run()

        verify(foodDao).updateExistingEntry(existing)
        assertThat(existing.carbs).isEqualTo(14.5)
        assertThat(existing.barcode).isEqualTo("3017620422003")
        assertThat(existing.image).isEqualTo("food_1.jpg")
        assertThat(existing.note).isEqualTo("green ones only")
        assertThat(existing.fiber).isEqualTo(2.4)
    }

    private fun createFood(
        id: Long,
        nsId: String,
        name: String,
        carbs: Double,
        isValid: Boolean = true
    ): Food = Food(
        name = name,
        category = "Test",
        subCategory = "Test",
        portion = 100.0,
        carbs = carbs,
        fat = 0.0,
        protein = 0.0,
        energy = 0.0,
        unit = "g",
        gi = null,
        isValid = isValid,
        interfaceIDs_backing = InterfaceIDs(nightscoutId = nsId),
    ).also { it.id = id }
}
