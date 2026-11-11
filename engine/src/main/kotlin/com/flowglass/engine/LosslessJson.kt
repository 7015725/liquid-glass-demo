package com.flowglass.engine

import kotlinx.serialization.KSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

/** JsonElement's default numeric encoder may round through Double. Preserve numeric lexemes. */
internal object LosslessJsonElementSerializer : KSerializer<JsonElement> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor
    override fun deserialize(decoder: Decoder): JsonElement = JsonElement.serializer().deserialize(decoder)
    override fun serialize(encoder: Encoder, value: JsonElement) {
        require(encoder is JsonEncoder) { "仅支持 JSON" }
        EngineLimits.valueIssue(value)?.let { throw IllegalArgumentException(it) }
        encoder.encodeJsonElement(rawNumbers(value))
    }
    @OptIn(ExperimentalSerializationApi::class)
    private fun rawNumbers(value: JsonElement): JsonElement = when (value) {
        is JsonArray -> JsonArray(value.map(::rawNumbers))
        is JsonObject -> JsonObject(value.mapValues { rawNumbers(it.value) })
        is JsonPrimitive -> if (value.isString || value === JsonNull) value else JsonUnquotedLiteral(value.content)
    }
}
internal object LosslessParamsSerializer : KSerializer<Map<String, JsonElement>> by
    MapSerializer(String.serializer(), LosslessJsonElementSerializer)
