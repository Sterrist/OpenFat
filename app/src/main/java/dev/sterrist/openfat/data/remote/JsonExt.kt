package dev.sterrist.openfat.data.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject

internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray

/** Строка из примитива; пустые строки считаются отсутствующими. */
internal fun JsonElement?.str(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }

/** Число из примитива: источники отдают числа и как числа, и как строки. */
internal fun JsonElement?.num(): Double? =
    str()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
