package app.aaps.ui.compose.foodManagement.openfoodfacts

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * One product read from Open Food Facts (OFF).
 *
 * All nutrition values are per 100 g, or per 100 ml for a liquid. OFF stores both under the
 * `_100g` keys. A value is null when OFF does not have it: it is never guessed.
 *
 * PROOF OF CONCEPT. The values come from volunteers and can be wrong. They are only used to fill
 * the food editor, and the user must check them before saving.
 */
data class OffProduct(
    val barcode: String,
    val name: String,
    val brand: String?,
    val carbsPer100: Double?,
    val sugarsPer100: Double?,
    val fiberPer100: Double?,
    val fatPer100: Double?,
    val proteinPer100: Double?,
    val energyKjPer100: Double?,
    val isLiquid: Boolean,
    /** The serving written on the package, as OFF has it: "125 g", "1 pot (125 g)", "3 biscuits (30 g)". */
    val servingSize: String? = null,
    /** The weight of that serving in g (or ml for a liquid). */
    val servingQuantity: Double? = null,
)

/** The answer to one OFF lookup. */
sealed interface OffResult {

    data class Found(val products: List<OffProduct>) : OffResult
    data object NotFound : OffResult
    data object Offline : OffResult

    /** OFF refused the request because of too many requests (HTTP 429). Never retried. */
    data object RateLimited : OffResult
    data object Failed : OffResult
}

/**
 * Reads products from the Open Food Facts API. Read access needs no account and no key.
 *
 * OFF limits each address to about 15 product reads and 10 searches per minute. So a search is only
 * sent when the user asks for it, never while they type.
 *
 * Responses are walked as JSON elements, like `WikiSearchClient`: this is a third-party API that may
 * add fields at any time, and OFF sends numbers sometimes as numbers, sometimes as strings.
 *
 * A request that fails for a short time (no answer, time out, server error 5xx) is sent again after
 * each delay of [retryDelaysMs], without telling the user. A "too many requests" answer is never
 * sent again: that would only make the OFF limit worse.
 *
 * @param language the app language as a two-letter code, used to prefer the product name in it
 * @param onFailure gets a short reason for each failed request, for the log
 */
internal class OpenFoodFactsClient(
    private val http: HttpClient,
    private val language: () -> String,
    private val baseUrl: String = BASE_URL,
    private val retryDelaysMs: List<Long> = RETRY_DELAYS_MS,
    private val onFailure: (String) -> Unit = {},
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Looks up one product. [barcode] must contain digits only (checked by the caller). */
    suspend fun productByBarcode(barcode: String): OffResult = request {
        val response = http.get("$baseUrl/api/v2/product/$barcode") {
            parameter("fields", fields())
        }
        if (response.status == HttpStatusCode.NotFound) return@request OffResult.NotFound
        failureOf(response.status)?.let { return@request it }
        val root = json.parseToJsonElement(response.bodyAsText()) as? JsonObject ?: return@request OffResult.Failed
        val product = (root["product"] as? JsonObject)?.let { parseProduct(it, barcode) }
        if (root.number("status") != 1.0 || product == null) OffResult.NotFound
        else OffResult.Found(listOf(product))
    }

    /** Full text search on product names and brands. */
    suspend fun search(terms: String): OffResult = request {
        val response = http.get("$baseUrl/cgi/search.pl") {
            parameter("search_terms", terms)
            parameter("search_simple", 1)
            parameter("action", "process")
            parameter("json", 1)
            parameter("page_size", PAGE_SIZE)
            parameter("fields", fields())
        }
        failureOf(response.status)?.let { return@request it }
        val root = json.parseToJsonElement(response.bodyAsText()) as? JsonObject ?: return@request OffResult.Failed
        val products = (root["products"] as? JsonArray).orEmpty()
            .mapNotNull { element -> (element as? JsonObject)?.let { parseProduct(it, fallbackBarcode = null) } }
        if (products.isEmpty()) OffResult.NotFound else OffResult.Found(products)
    }

    private fun failureOf(status: HttpStatusCode): OffResult? = when {
        status.isSuccess()                       -> null
        status == HttpStatusCode.TooManyRequests -> OffResult.RateLimited.also { onFailure("HTTP 429, too many requests") }
        else                                     -> OffResult.Failed.also { onFailure("HTTP ${status.value}") }
    }

    private fun fields(): String =
        listOf(
            "code", "product_name", "product_name_${language()}", "brands", "nutriments", "product_quantity_unit",
            "serving_size", "serving_quantity"
        ).joinToString(",")

    /** A product without a name is skipped: the user could not tell it from the others. */
    private fun parseProduct(product: JsonObject, fallbackBarcode: String?): OffProduct? {
        val name = product.text("product_name_${language()}") ?: product.text("product_name") ?: return null
        val barcode = product.text("code") ?: fallbackBarcode ?: return null
        val nutriments = product["nutriments"] as? JsonObject
        return OffProduct(
            barcode = barcode,
            name = name,
            brand = product.text("brands")?.split(',')?.firstOrNull()?.trim()?.ifBlank { null },
            carbsPer100 = nutriments?.number("carbohydrates_100g"),
            sugarsPer100 = nutriments?.number("sugars_100g"),
            fiberPer100 = nutriments?.number("fiber_100g"),
            fatPer100 = nutriments?.number("fat_100g"),
            proteinPer100 = nutriments?.number("proteins_100g"),
            energyKjPer100 = nutriments?.number("energy-kj_100g")
                ?: nutriments?.number("energy-kcal_100g")?.let { it * KJ_PER_KCAL },
            isLiquid = product.text("product_quantity_unit").equals("ml", ignoreCase = true),
            servingSize = product.text("serving_size"),
            servingQuantity = product.number("serving_quantity")?.takeIf { it > 0 && it.isFinite() },
        )
    }

    /** Sends [block] again after each delay while it fails. Only cancellation goes up. */
    private suspend fun request(block: suspend () -> OffResult): OffResult {
        var result = once(block)
        for (wait in retryDelaysMs) {
            if (result != OffResult.Failed) break
            delay(wait)
            result = once(block)
        }
        return result
    }

    /** Any failure is reported as [OffResult.Failed]. */
    private suspend fun once(block: suspend () -> OffResult): OffResult =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onFailure(e::class.simpleName ?: "exception")
            OffResult.Failed
        }

    /** A non-blank string field, or null. */
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.ifBlank { null }

    /** A number field. OFF sends some numbers as strings, so both are read. */
    private fun JsonObject.number(key: String): Double? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.doubleOrNull ?: primitive.contentOrNull?.toDoubleOrNull()
    }

    companion object {

        const val BASE_URL = "https://world.openfoodfacts.org"
        private const val PAGE_SIZE = 20
        private val RETRY_DELAYS_MS = listOf(1_000L, 3_000L)
        private const val KJ_PER_KCAL = 4.184
    }
}
