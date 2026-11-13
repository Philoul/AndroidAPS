package app.aaps.core.nssdk.remotemodel

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.floor

/**
 * A food value with decimals (14.8 g), written so that older AAPS versions can still read it.
 *
 * - A whole value is written as a whole number: 12.0 is sent as `12`, not `12.0`. Older AAPS
 *   versions read these fields as whole numbers only, and one value they cannot read loses the whole
 *   page of foods. So a food without decimals looks exactly as before.
 * - A value with decimals is written as it is (`14.8`).
 * - Reading accepts both forms, from any client.
 */
internal object NsDecimalSerializer : KSerializer<Double> {

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("app.aaps.core.nssdk.NsDecimal", PrimitiveKind.DOUBLE)

    override fun deserialize(decoder: Decoder): Double {
        if (decoder !is JsonDecoder) return decoder.decodeDouble()
        val element = decoder.decodeJsonElement()
        return (element as? JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: throw SerializationException("Expected a number, found $element")
    }

    override fun serialize(encoder: Encoder, value: Double) {
        if (encoder !is JsonEncoder) return encoder.encodeDouble(value)
        val whole = value.isFinite() && value == floor(value) && abs(value) < MAX_WHOLE
        encoder.encodeJsonElement(if (whole) JsonPrimitive(value.toLong()) else JsonPrimitive(value))
    }

    /** Above this a Double is not exact as a Long any more. Food values never come close. */
    private const val MAX_WHOLE = 1e15
}
