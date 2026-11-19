package net.eratiem.randogeddon.server.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

// Lenient accessors for the loosely typed Archipelago packets
internal fun JsonObject.obj(key: String): JsonObject? = (this[key] as? JsonObject)
internal fun JsonObject.array(key: String): JsonArray? = (this[key] as? JsonArray)
internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it != JsonNull }?.content
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.longs(key: String): List<Long> = array(key).orEmpty().map { it.jsonPrimitive.long }

internal val JsonPrimitive.long: Long get() = longOrNull ?: content.toLong()
internal val JsonPrimitive.int: Int get() = intOrNull ?: content.toInt()
