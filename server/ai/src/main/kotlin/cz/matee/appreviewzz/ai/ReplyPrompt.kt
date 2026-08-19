package cz.matee.appreviewzz.ai

import cz.matee.appreviewzz.core.port.ReplySuggestionRequest

/**
 * Prompt pro návrh odpovědi. **Verzovaný** (plán §5.5): když se znění změní, změní se
 * i [VERSION] — jinak nejde poznat, jestli se návrhy zhoršily kvůli modelu, nebo kvůli nám.
 *
 * Prompt je anglicky schválně, i když produkt mluví česky: modely na anglických instrukcích
 * drží pravidla spolehlivěji a jazyk odpovědi se řídí zvlášť (jazykem recenze).
 *
 * Proti dnešnímu n8n promptu (`ai_instructions + "make absolutely sure that your suggested
 * reply is shorter than N characters"`) tu jsou tři věci navíc: jazyk odpovědi je řízený,
 * model dostane hvězdičky (jinak chválí za jednohvězdičkovou recenzi) a limit se **navíc
 * vynucuje po vygenerování** ([clampToLimit]), protože prosba v promptu není záruka.
 */
object ReplyPrompt {
    const val VERSION = "2026-08-19"

    fun system(request: ReplySuggestionRequest): String =
        buildString {
            appendLine(
                "You write public replies to app store reviews on behalf of the team behind \"${request.appName}\".",
            )
            appendLine("Rules:")
            appendLine("- Write the reply in ${language(request)}.")
            appendLine("- Stay under ${request.maxLength} characters including spaces. Shorter is better.")
            appendLine("- Plain text only: no markdown, no surrounding quotes, no signature, no emoji.")
            appendLine("- Address what the review actually says; never invent features, dates or causes.")
            appendLine("- Unless the team instructions say otherwise, do not promise a release date or a refund.")
            appendLine("- Output the reply text and nothing else.")
            request.instructions?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine("Team instructions (they take precedence over the rules above):")
                append(it.trim())
            }
        }.trim()

    fun user(request: ReplySuggestionRequest): String =
        buildString {
            appendLine("Rating: ${request.starRating}/5")
            request.title?.takeIf { it.isNotBlank() }?.let { appendLine("Title: ${it.trim()}") }
            val body = request.body?.takeIf { it.isNotBlank() }?.trim()
            // Recenze bez textu (jen hvězdičky) se stát může; model to musí vědět, ne hádat.
            appendLine("Review: ${body ?: "(no text, rating only)"}")
        }.trim()

    /**
     * Jazyk odpovědi. Store dává jazyk recenze jako BCP-47 (`cs`, `en-GB`, `pt-BR`) — když
     * chybí, odpovídá se jazykem týmu, protože to je jazyk, kterému klient rozumí a umí ho
     * zkontrolovat.
     */
    private fun language(request: ReplySuggestionRequest): String =
        request.reviewLocale?.takeIf { it.isNotBlank() }?.let { "the language of the review (locale $it)" }
            ?: "language code ${request.teamLocale.code}"
}
