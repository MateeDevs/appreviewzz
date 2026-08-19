package cz.matee.appreviewzz.ai

import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.port.ReplySuggestion
import cz.matee.appreviewzz.core.port.ReplySuggestionRequest
import cz.matee.appreviewzz.core.port.SuggestReplyProvider
import io.ktor.client.HttpClient

/** Deployment bez AI: self-host, který si klíč nepřipojil, nebo `AI_PROVIDER=none`. */
object NoSuggestReplyProvider : SuggestReplyProvider {
    override suspend fun suggest(request: ReplySuggestionRequest): ReplySuggestion = ReplySuggestion.Unavailable
}

/**
 * Volba providera konfigurací (plán §5.5: per deployment, per-org override až později).
 * Anthropic a OpenAI přibudou stejným způsobem — proto je tahle továrna jediné místo,
 * které o konkrétních providerech ví.
 */
object SuggestReplyProviders {
    const val GEMINI = "gemini"
    const val NONE = "none"

    fun fromConfig(
        provider: String,
        apiKey: String?,
        model: String?,
        httpClient: () -> HttpClient,
    ): SuggestReplyProvider =
        when (provider.lowercase()) {
            NONE -> NoSuggestReplyProvider
            GEMINI -> {
                // Nastavený provider bez klíče je překlep v configu, ne „prostě bez AI" — kdyby
                // se tiše vypnul, klient by se divil, proč jsou vstupy ve Slacku prázdné.
                val key =
                    apiKey?.takeIf { it.isNotBlank() }
                        ?: error("AI_PROVIDER=gemini potřebuje AI_API_KEY (nebo přepni na AI_PROVIDER=none)")
                GeminiSuggestReplyProvider(
                    httpClient = httpClient(),
                    apiKey = SecretPayload(key),
                    model = model?.takeIf { it.isNotBlank() } ?: GeminiSuggestReplyProvider.DEFAULT_MODEL,
                )
            }

            else -> error("Neznámý AI_PROVIDER='$provider', čekám ${listOf(GEMINI, NONE).joinToString(" nebo ")}")
        }
}
