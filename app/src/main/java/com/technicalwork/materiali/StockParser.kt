package com.technicalwork.materiali

/**
 * Scompone stringhe come "5 + 2 sparat" in un oggetto strutturato Stock.
 * Usato durante lo scambio materiale per gestire quantità libere e "sparate" separatamente.
 */
data class Stock(
    val label: String,
    val free: Int,
    val used: Int,
    val rawValue: String,       // valore originale per ricostruzione
    val usedSuffix: String = "" // sinonimo trovato (es. "sparat") per ricomposizione fedele
)

class StockParser {

    // Ordinati dal più lungo al più corto per evitare match parziali
    private val synonyms = listOf(
        "installato", "sparato", "sparata", "montato",
        "sparat", "finito", "usato", "spara",
        "spar", "inst", "spa", "spr",
        "sp", "s"
    )

    // Pattern per "X + Y sparato"
    private val combinedRegex: Regex by lazy {
        val synonymPattern = synonyms.joinToString("|") { Regex.escape(it) }
        Regex("""^\s*(\d+)\s*\+\s*(\d+)\s*($synonymPattern)[a-zA-Z]*\s*$""", RegexOption.IGNORE_CASE)
    }

    // Pattern per solo "Y sparato"
    private val standaloneUsedRegex: Regex by lazy {
        val synonymPattern = synonyms.joinToString("|") { Regex.escape(it) }
        Regex("""^\s*(\d+)\s*($synonymPattern)[a-zA-Z]*\s*$""", RegexOption.IGNORE_CASE)
    }

    /**
     * Scompone un valore in free + used.
     * - "5 + 2 sparat" → Stock(free=5, used=2, usedSuffix="sparat")
     * - "4 sparat"     → Stock(free=0, used=4, usedSuffix="sparat")
     * - "10"           → Stock(free=10, used=0)
     */
    fun parse(label: String, value: String): Stock {
        val trimmed = value.trim()

        // 1. Prova il pattern combinato "X + Y sparato"
        combinedRegex.find(trimmed)?.let { match ->
            val free = match.groupValues[1].toIntOrNull() ?: 0
            val used = match.groupValues[2].toIntOrNull() ?: 0
            val suffix = match.groupValues[3]
            return Stock(label, free, used, trimmed, suffix)
        }

        // 2. Prova il solo "Y sparato"
        standaloneUsedRegex.find(trimmed)?.let { match ->
            val used = match.groupValues[1].toIntOrNull() ?: 0
            val suffix = match.groupValues[2]
            return Stock(label, 0, used, trimmed, suffix)
        }

        // 3. Fallback: valore semplice (intero)
        val numericVal = trimmed.toIntOrNull() ?: 0
        return Stock(label, numericVal, 0, trimmed)
    }

    /**
     * Verifica se il valore contiene un pattern "sparato".
     */
    fun hasUsedPart(value: String): Boolean {
        val trimmed = value.trim()
        return combinedRegex.containsMatchIn(trimmed) || standaloneUsedRegex.containsMatchIn(trimmed)
    }

    /**
     * Ricompone un Stock modificato nella stringa originale.
     * Se il suffisso è vuoto (non c'era "sparato"), restituisce solo il numero.
     * Es: Stock(free=3, used=2, suffix="sparat") → "3 + 2 sparat"
     *     Stock(free=10, used=0) → "10"
     */
    fun recompose(stock: Stock): String {
        val hasFree = stock.free > 0
        val hasUsed = stock.used > 0
        val suffix = stock.usedSuffix.ifEmpty { "sparat" }

        return when {
            hasFree && hasUsed -> "${stock.free} + ${stock.used} $suffix"
            hasUsed -> "${stock.used} $suffix"
            hasFree -> "${stock.free}"
            else -> ""
        }
    }
}
