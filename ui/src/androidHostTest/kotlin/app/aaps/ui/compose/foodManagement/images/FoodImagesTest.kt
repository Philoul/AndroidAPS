package app.aaps.ui.compose.foodManagement.images

import app.aaps.core.data.model.FD
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.ui.compose.foodManagement.openfoodfacts.CurrentImage
import app.aaps.ui.compose.foodManagement.openfoodfacts.OffImage
import app.aaps.ui.compose.foodManagement.openfoodfacts.OffProduct
import app.aaps.ui.compose.foodManagement.openfoodfacts.OpenFoodFactsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

/**
 * The OFF photo of a food is found from its barcode, and downloaded again when its file is missing,
 * but only when OFF has one: a product without a photo is never asked again. A photo of the user
 * is shown instead of the OFF photo.
 */
internal class FoodImagesTest {

    @Mock private lateinit var openFoodFacts: OpenFoodFactsRepository
    @Mock private lateinit var aapsLogger: AAPSLogger

    private val photo = byteArrayOf(1, 2, 3)
    private val userPhoto = byteArrayOf(9, 9)
    private lateinit var store: MemoryImageStore
    private lateinit var sut: FoodImages

    private class MemoryImageStore : FoodImageStore {

        val files = mutableMapOf<String, ByteArray>()
        override val available: Boolean = true
        override fun read(key: String): ByteArray? = files[key]
        override fun write(key: String, bytes: ByteArray) {
            files[key] = bytes
        }

        override fun delete(key: String) {
            files.remove(key)
        }

        override fun keys(): List<String> = files.keys.toList()
    }

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        store = MemoryImageStore()
        sut = FoodImages(store, openFoodFacts, aapsLogger)
    }

    private val imported = FD(id = 1, name = "Yogurt", portion = 1.0, carbs = 11.0, barcode = BARCODE)

    private fun product(imageUrl: String?) = OffProduct(
        barcode = BARCODE, name = "Yogurt", brand = null, carbsPer100 = 12.0, sugarsPer100 = null, fiberPer100 = null,
        fatPer100 = null, proteinPer100 = null, energyKjPer100 = null, isLiquid = false, imageUrl = imageUrl
    )

    @Test
    fun `a saved photo is read from the file, nothing is downloaded`() = runTest {
        store.files[BARCODE] = photo

        assertThat(sut.offPhoto(imported)).isEqualTo(photo)
        verifyNoInteractions(openFoodFacts)
    }

    @Test
    fun `a food typed by hand has no OFF photo and asks nothing`() = runTest {
        assertThat(sut.offPhoto(imported.copy(barcode = null))).isNull()
        verifyNoInteractions(openFoodFacts)
    }

    @Test
    fun `a missing photo is found at its current address and saved`() = runTest {
        whenever(openFoodFacts.currentImageUrl(BARCODE)).thenReturn(CurrentImage.At(URL))
        whenever(openFoodFacts.image(URL)).thenReturn(OffImage.Found(photo))

        assertThat(sut.offPhoto(imported)).isEqualTo(photo)
        assertThat(store.files[BARCODE]).isEqualTo(photo)
    }

    @Test
    fun `a product without a photo is marked, so it is not asked again`() = runTest {
        whenever(openFoodFacts.currentImageUrl(BARCODE)).thenReturn(CurrentImage.None)

        assertThat(sut.offPhoto(imported)).isNull()
        assertThat(sut.offPhoto(imported)).isNull()

        verify(openFoodFacts).currentImageUrl(BARCODE) // once only
        assertThat(store.files.keys).containsExactly(FoodImages.noPhotoKey(BARCODE))
    }

    @Test
    fun `no answer from OFF changes nothing, so it is tried again next time`() = runTest {
        whenever(openFoodFacts.currentImageUrl(BARCODE)).thenReturn(CurrentImage.Unknown)

        assertThat(sut.offPhoto(imported)).isNull()

        assertThat(store.files).isEmpty()
        verify(openFoodFacts, never()).image(any())
    }

    @Test
    fun `an import keeps the photo already downloaded, or marks a product without a photo`() = runTest {
        sut.saveOffPhoto(product(URL), photo)
        assertThat(store.files[BARCODE]).isEqualTo(photo)
        verify(openFoodFacts, never()).image(any())

        sut.saveOffPhoto(product(null).copy(barcode = "999"), null)
        assertThat(store.files[FoodImages.noPhotoKey("999")]).isEmpty()
    }

    @Test
    fun `a photo of the user is shown instead of the OFF photo, which is kept`() = runTest {
        store.files[BARCODE] = photo
        val key = sut.savePhoto(userPhoto)!!

        assertThat(sut.shown(listOf(imported.copy(image = key)))[1L]).isEqualTo(userPhoto)
        assertThat(sut.shown(listOf(imported))[1L]).isEqualTo(photo)

        sut.deletePhoto(key)
        assertThat(store.files.keys).containsExactly(BARCODE)
    }

    @Test
    fun `an OFF photo is never deleted as a photo of the user`() = runTest {
        store.files[BARCODE] = photo

        sut.deletePhoto(BARCODE)

        assertThat(store.files.keys).containsExactly(BARCODE)
    }

    @Test
    fun `the marker of a product without a photo is not shown as a photo`() = runTest {
        store.files[FoodImages.noPhotoKey(BARCODE)] = ByteArray(0)
        store.files[BARCODE] = ByteArray(0)

        assertThat(sut.shown(listOf(imported))).isEmpty()
    }

    private companion object {

        const val BARCODE = "3017620422003"
        const val URL = "https://images.openfoodfacts.org/images/products/301/762/042/2003/front_fr.200.jpg"
    }
}
