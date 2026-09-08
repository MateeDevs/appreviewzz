package cz.matee.appreviewzz.connectors.googleplay

/**
 * Čtení CSV exportů z Play Console — společné pro hodnocení i pro recenze.
 *
 * Obojí leží v témž bucketu a má tytéž dvě pasti:
 *
 * - Soubor je **UTF-16LE s BOM**. Kdo ho přečte jako UTF-8, dostane text prokládaný nulovými
 *   bajty, všechny sloupce mu vyjdou prázdné a průměr spadne na nulu. Dnešní n8n to obchází
 *   hledáním klíče, který „obsahuje Date"; tady se BOM řeší rovnou při dekódování.
 * - Pole smí být **v uvozovkách a mít v sobě čárku i konec řádku**. U přehledu hodnocení na
 *   tom nezáleží (samá čísla), u recenzí je to běžný stav: text recenze má odstavce.
 *   Naivní `split(',')` po řádcích by rozsekal jednu recenzi na několik nesmyslných.
 */
internal object PlayCsv {
    fun decode(bytes: ByteArray): String =
        when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)

            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)

            // Bez BOM: sudý počet bajtů s nulou na liché pozici je taky UTF-16LE.
            bytes.size >= 2 && bytes.size % 2 == 0 && bytes[1] == 0.toByte() -> String(bytes, Charsets.UTF_16LE)
            else -> String(bytes, Charsets.UTF_8).removePrefix("﻿")
        }

    /**
     * Řádky CSV podle RFC 4180: pole v uvozovkách nese čárku i konec řádku, zdvojená uvozovka
     * uvnitř je jedna uvozovka. Úplně prázdné řádky (i ten poslední) se zahazují.
     */
    @Suppress("NestedBlockDepth")
    fun rows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0

        fun endRow() {
            row.add(field.toString())
            field.clear()
            if (row.any { it.isNotEmpty() }) rows.add(row)
            row = mutableListOf()
        }

        while (index < text.length) {
            val char = text[index]
            when {
                quoted && char == '"' && text.getOrNull(index + 1) == '"' -> {
                    field.append('"')
                    index++
                }

                char == '"' -> quoted = !quoted
                !quoted && char == ',' -> {
                    row.add(field.toString())
                    field.clear()
                }

                !quoted && (char == '\n' || char == '\r') -> {
                    if (char == '\r' && text.getOrNull(index + 1) == '\n') index++
                    endRow()
                }

                else -> field.append(char)
            }
            index++
        }
        endRow()
        return rows
    }

    /** Index sloupce podle jména v hlavičce; `-1` znamená, že ho export nemá. */
    fun column(
        header: List<String>,
        name: String,
    ): Int = header.indexOfFirst { it.trim().removePrefix("﻿").equals(name, ignoreCase = true) }

    /** Buňka jako text, nebo `null` u chybějícího i prázdného sloupce. */
    fun cell(
        cells: List<String>,
        index: Int,
    ): String? = cells.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }
}
