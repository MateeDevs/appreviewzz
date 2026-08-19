package cz.matee.appreviewzz.ai

/**
 * Vynucení délkového limitu po vygenerování. Model prosbu o limit občas ignoruje a store
 * delší odpověď odmítne — ořezáváme proto sami, a **na hranici věty**, aby návrh nekončil
 * v půlce slova.
 *
 * Když poslední věta končí moc brzy (model napsal jedno dlouhé souvětí), řeže se na hranici
 * slova a doplní výpustka — pořád je to čitelný návrh, který člověk dopíše.
 */
fun clampToLimit(
    text: String,
    maxLength: Int,
): String {
    require(maxLength > 0) { "Limit odpovědi musí být kladný, ne $maxLength" }
    val normalized = text.trim()
    if (normalized.length <= maxLength) return normalized

    val window = normalized.take(maxLength)
    val sentenceEnd = window.lastIndexOfAny(SENTENCE_ENDINGS)
    if (sentenceEnd >= maxLength * MIN_SENTENCE_SHARE) return window.take(sentenceEnd + 1).trimEnd()

    val wordEnd = window.take(maxLength - 1).lastIndexOf(' ')
    val cut = if (wordEnd > 0) window.take(wordEnd) else window.take(maxLength - 1)
    return cut.trimEnd().trimEnd(*SENTENCE_ENDINGS).trimEnd(',') + ELLIPSIS
}

private val SENTENCE_ENDINGS = charArrayOf('.', '!', '?', '…')
private const val ELLIPSIS = "…"

/** Pod touhle částí limitu už je věta tak krátká, že je lepší ořezat na slovo a naznačit pokračování. */
private const val MIN_SENTENCE_SHARE = 0.5
