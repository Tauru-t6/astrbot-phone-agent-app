package com.tauru.astrbotphoneagent.logic

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

/** JSON commands from Python carry scalar numbers/bools as well as strings. */
object CommandArgsSerializer : KSerializer<Map<String, String>> {
    private val delegate = MapSerializer(String.serializer(), String.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: Map<String, String>) = delegate.serialize(encoder, value)
    override fun deserialize(decoder: Decoder): Map<String, String> {
        if (decoder !is JsonDecoder) return delegate.deserialize(decoder)
        val obj = decoder.decodeJsonElement() as? JsonObject ?: throw SerializationException("args must be an object")
        if (obj.size > 32) throw SerializationException("too many args")
        return obj.mapValues { (_, value) ->
            if (value !is JsonPrimitive || value is JsonNull) throw SerializationException("args values must be scalar")
            value.content.also { if (it.length > 8000) throw SerializationException("arg too long") }
        }
    }
}
