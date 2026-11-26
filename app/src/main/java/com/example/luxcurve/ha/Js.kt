package com.example.luxcurve.ha

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Small helpers around Gson so the protocol code stays readable and never throws on odd input. */
internal fun parseObject(text: String): JsonObject? = try {
    val element = JsonParser.parseString(text)
    if (element.isJsonObject) element.asJsonObject else null
} catch (e: Exception) {
    null
}

internal fun JsonObject.str(key: String): String? {
    val e = get(key) ?: return null
    return if (e.isJsonPrimitive) e.asString else null
}

internal fun JsonObject.obj(key: String): JsonObject? {
    val e = get(key) ?: return null
    return if (e.isJsonObject) e.asJsonObject else null
}

/** Reads 7, 7.0 and "7" alike. */
internal fun JsonObject.int(key: String): Int? {
    val e = get(key) ?: return null
    if (!e.isJsonPrimitive) return null
    return try {
        e.asDouble.toInt()
    } catch (ex: Exception) {
        null
    }
}

internal fun JsonObject.bool(key: String): Boolean? {
    val e = get(key) ?: return null
    if (!e.isJsonPrimitive) return null
    val p = e.asJsonPrimitive
    return if (p.isBoolean) p.asBoolean else null
}
