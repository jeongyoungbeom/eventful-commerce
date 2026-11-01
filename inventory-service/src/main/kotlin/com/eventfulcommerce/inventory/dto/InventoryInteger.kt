package com.eventfulcommerce.inventory.dto

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.deser.std.StdDeserializer

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@JacksonAnnotationsInside
@JsonProperty(required = true)
@JsonDeserialize(using = InventoryIntegerDeserializer::class)
annotation class InventoryInteger

class InventoryIntegerDeserializer : StdDeserializer<Long>(Long::class.javaObjectType) {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): Long {
        if (parser.currentToken != JsonToken.VALUE_NUMBER_INT)
            return context.reportInputMismatch(Long::class.javaObjectType, "수량·금액은 JSON 정수여야 합니다")
        return parser.longValue
    }

    override fun getNullValue(context: DeserializationContext): Long =
        context.reportInputMismatch(Long::class.javaObjectType, "수량·금액은 필수입니다")
}
