package com.musicflow.play.data

import java.text.Normalizer
import java.util.Locale

object TextNormalizer {

    private val DIACRITICS_REGEX = "\\p{InCombiningDiacriticalMarks}+".toRegex()
    private val MULTI_SPACE_REGEX = "\\s+".toRegex()

    /**
     * Normalizes text for accent-insensitive and case-insensitive search:
     * "Été — Café" -> "ete - cafe"
     */
    fun normalize(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
        return DIACRITICS_REGEX.replace(decomposed, "")
            .lowercase(Locale.ROOT)
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
    }

    fun matches(query: String, vararg fields: String?): Boolean {
        val normQuery = normalize(query)
        if (normQuery.isEmpty()) return true
        val tokens = normQuery.split(' ').filter { it.isNotEmpty() }
        val combined = fields.joinToString(" ") { normalize(it) }
        return tokens.all { combined.contains(it) }
    }
}
