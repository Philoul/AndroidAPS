package app.aaps.ui.compose.foodManagement.images

import app.aaps.core.data.model.FD
import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.ui.compose.foodManagement.openfoodfacts.CurrentImage
import app.aaps.ui.compose.foodManagement.openfoodfacts.OffImage
import app.aaps.ui.compose.foodManagement.openfoodfacts.OffProduct
import app.aaps.ui.compose.foodManagement.openfoodfacts.OpenFoodFactsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The food photos. The database only keeps names, the pictures are small files in [FoodImageStore]:
 *
 * - The Open Food Facts (OFF) photo of a food imported from OFF. Its file is named after the
 *   `barcode` of the food, so two foods made from the same product share it. When the file is
 *   missing (new phone, cleared data), it is downloaded again the next time the food is opened.
 * - A photo taken by the user. Its random file name is in the `image` column of the food. It is
 *   shown instead of the OFF photo. The OFF file is kept, so removing the photo of the user shows
 *   the OFF photo again without a download.
 *
 * When OFF has no photo for a product, an empty marker file says so, and OFF is never asked again
 * for it.
 */
@SingleIn(AppScope::class)
@Inject
class FoodImages(
    private val store: FoodImageStore,
    private val openFoodFacts: OpenFoodFactsRepository,
    private val aapsLogger: AAPSLogger,
) {

    /** One download at a time: opening the same food twice must not download twice. */
    private val mutex = Mutex()

    val available: Boolean get() = store.available

    /** Downloads the photo of a search result, to show it in the list. Not saved. */
    suspend fun download(product: OffProduct): ByteArray? {
        if (!store.available) return null
        val url = product.imageUrl ?: return null
        return (openFoodFacts.image(url) as? OffImage.Found)?.bytes
    }

    /**
     * Saves the photo of [product] for a food just imported. [bytes] is the photo already downloaded
     * for the list, if any, so it is not downloaded twice. A product without a photo gets the marker.
     */
    suspend fun saveOffPhoto(product: OffProduct, bytes: ByteArray?) {
        if (!store.available) return
        if (product.imageUrl == null) {
            write(noPhotoKey(product.barcode), ByteArray(0))
            return
        }
        val photo = bytes ?: download(product) ?: return
        write(product.barcode, photo)
    }

    /**
     * The photo to show for each food of [foods] that has one: the photo of the user, or else the
     * saved OFF photo. Nothing is downloaded.
     */
    suspend fun shown(foods: Collection<FD>): Map<Long, ByteArray> {
        if (!store.available) return emptyMap()
        return withContext(aapsIoDispatcher) {
            foods.mapNotNull { food ->
                val photo = food.image?.let { read(it) } ?: food.barcode?.let { read(it) }
                photo?.let { food.id to it }
            }.toMap()
        }
    }

    /** The photo the user took for [food], or null when there is none. */
    suspend fun userPhoto(food: FD): ByteArray? {
        if (!store.available) return null
        val key = food.image ?: return null
        return withContext(aapsIoDispatcher) { read(key) }
    }

    /**
     * Saves [bytes] as a new photo file and returns its name, to put in the `image` column, or null
     * when it was not saved.
     */
    suspend fun savePhoto(bytes: ByteArray): String? {
        if (!store.available) return null
        val key = newUserKey()
        return if (write(key, bytes)) key else null
    }

    /** Deletes the photo file [key] of the user. An OFF photo is never deleted here. */
    suspend fun deletePhoto(key: String) {
        if (!store.available || !key.startsWith(USER_KEY_PREFIX)) return
        withContext(aapsIoDispatcher) { runCatching { store.delete(key) } }
    }

    /**
     * The OFF photo of [food]. When the file is missing, the product is read again to find the
     * current address of its photo (OFF changes it when a photo is replaced), and the photo is
     * downloaded. Null when the food was not imported, OFF has no photo, or OFF cannot be reached
     * now (it is tried again next time).
     */
    suspend fun offPhoto(food: FD): ByteArray? = mutex.withLock {
        if (!store.available) return@withLock null
        val barcode = food.barcode ?: return@withLock null
        withContext(aapsIoDispatcher) { read(barcode) }?.let { return@withLock it }
        if (withContext(aapsIoDispatcher) { hasMarker(barcode) }) return@withLock null

        when (val current = openFoodFacts.currentImageUrl(barcode)) {
            is CurrentImage.At   -> (openFoodFacts.image(current.url) as? OffImage.Found)?.bytes?.also { write(barcode, it) }

            CurrentImage.None    -> {
                // OFF has no photo: never asked again for this product.
                write(noPhotoKey(barcode), ByteArray(0))
                null
            }

            // No answer: nothing is known, so nothing is changed. Tried again next time.
            CurrentImage.Unknown -> null
        }
    }

    /**
     * Deletes the photo files that no food of [foods] uses any more: the photo of the user of a
     * deleted food, an old photo of the user, and the OFF photo of a product no food comes from.
     *
     * [foods] must be every valid food. Nothing is deleted when it is empty: an empty list is more
     * likely a failed read than a user who deleted every food.
     */
    suspend fun deleteUnused(foods: Collection<FD>) {
        if (!store.available || foods.isEmpty()) return
        val used = foods.flatMap { food -> listOfNotNull(food.image, food.barcode, food.barcode?.let { noPhotoKey(it) }) }.toSet()
        withContext(aapsIoDispatcher) {
            val keys = runCatching { store.keys() }.getOrDefault(emptyList())
            keys.filter { it !in used }.forEach { key -> runCatching { store.delete(key) } }
        }
    }

    /** A real photo: the marker file of a product without a photo is empty. */
    private fun read(key: String): ByteArray? = runCatching { store.read(key) }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun hasMarker(barcode: String): Boolean = runCatching { store.read(noPhotoKey(barcode)) }.getOrNull() != null

    private suspend fun write(key: String, bytes: ByteArray): Boolean = withContext(aapsIoDispatcher) {
        runCatching { store.write(key, bytes) }
            .onFailure { e -> aapsLogger.error(LTag.UI, "Cannot save a food photo", e) }
            .isSuccess
    }

    /** A new name for each photo, so a restored file never takes the place of a newer photo. */
    @OptIn(ExperimentalUuidApi::class)
    private fun newUserKey(): String = USER_KEY_PREFIX + Uuid.random().toHexString().take(16)

    companion object {

        const val USER_KEY_PREFIX = "user_"
        private const val NO_PHOTO_SUFFIX = "_none"

        fun noPhotoKey(barcode: String): String = barcode + NO_PHOTO_SUFFIX
    }
}
