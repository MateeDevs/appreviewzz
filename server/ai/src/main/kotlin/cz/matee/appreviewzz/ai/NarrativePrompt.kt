package cz.matee.appreviewzz.ai

import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.port.NarrativeRequest

/**
 * Prompt pro slovní shrnutí rozboru (B6).
 *
 * Celý je postavený na jednom pravidle: **model nesmí přinést žádné číslo, které nedostal**.
 * Kontrola je v use casu (regex na čísla proti množině z agregátů), ale prompt to musí říct
 * taky — validace, která shrnutí zahazuje obden, je k ničemu.
 */
object NarrativePrompt {
    const val VERSION = "2026-09-v1"

    /** Delší shrnutí se ve Slacku ztratí pod fold; čtyři sta znaků jsou tak tři věty. */
    const val MAX_SUMMARY_CHARS = 400

    fun system(request: NarrativeRequest): String {
        val language =
            when (request.locale) {
                MessageLocale.CS -> "Czech"
                MessageLocale.EN -> "English"
            }
        return """
            You write the opening paragraph of a weekly app review report for the product team of ${request.appName}.

            Rules, in order of importance:
            1. Write in $language. Two or three sentences, at most $MAX_SUMMARY_CHARS characters.
            2. Use ONLY numbers that appear literally in the aggregates JSON below. Never compute,
               round, sum or estimate a number yourself. If you are unsure, write no number at all.
            3. You may quote only from the candidate quotes provided, verbatim, and you must list the
               reviewId of every quote you use in citedReviewIds. Never invent or paraphrase a quote.
            4. Say what changed and what it means for the team. Do not restate the whole table —
               the numbers follow underneath your paragraph.
            5. No greetings, no sign-off, no bullet points, no markdown headings.
            """.trimIndent()
    }

    fun user(request: NarrativeRequest): String =
        buildString {
            appendLine("Aggregates (JSON):")
            appendLine(request.aggregatesJson)
            appendLine()
            if (request.quotes.isEmpty()) {
                appendLine("Candidate quotes: none. Write the paragraph without quoting.")
            } else {
                appendLine("Candidate quotes:")
                request.quotes.forEach { appendLine("- reviewId=${it.reviewId}: ${it.quote}") }
            }
        }
}
