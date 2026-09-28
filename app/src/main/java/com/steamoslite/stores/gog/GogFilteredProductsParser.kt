package com.steamoslite.stores.gog

import org.json.JSONException
import org.json.JSONObject

object GogFilteredProductsParser {
    data class Page(
        val hiddenProductIds: Set<String>,
        val totalPages: Int,
    )

    fun parseHiddenPage(rawJson: String): Page {
        val root = try {
            JSONObject(rawJson)
        } catch (e: JSONException) {
            throw IllegalArgumentException("Malformed getFilteredProducts response", e)
        }

        val products = root.optJSONArray("products")
            ?: throw IllegalArgumentException("getFilteredProducts response is missing products")
        val totalPages = root.optInt("totalPages", -1)
        if (totalPages < 0) {
            throw IllegalArgumentException("getFilteredProducts response has invalid totalPages: $totalPages")
        }

        val hiddenProductIds = buildSet {
            for (i in 0 until products.length()) {
                val product = products.optJSONObject(i)
                    ?: throw IllegalArgumentException("getFilteredProducts product $i is not an object")
                val id = when (val rawId = product.opt("id")) {
                    null -> throw IllegalArgumentException("getFilteredProducts product $i is missing id")
                    is Number -> rawId.toString()
                    is String -> rawId.takeIf { it.isNotBlank() }
                        ?: throw IllegalArgumentException("getFilteredProducts product $i has a blank id")
                    else -> throw IllegalArgumentException("getFilteredProducts product $i has an invalid id")
                }
                add(id)
            }
        }

        return Page(hiddenProductIds = hiddenProductIds, totalPages = totalPages)
    }
}
