package cz.matee.appreviewzz.ai

import cz.matee.appreviewzz.core.model.PlatformSettings
import cz.matee.appreviewzz.core.port.ReplySuggestion
import cz.matee.appreviewzz.core.port.ReplySuggestionRequest
import cz.matee.appreviewzz.core.port.SuggestReplyProvider
import cz.matee.appreviewzz.core.usecase.PlatformConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * Provider, který se řídí platformní konfigurací (F7.6).
 *
 * Do F7 vznikal provider jednou při startu z proměnných prostředí. S klíčem v databázi to
 * nestačí: změna klíče by se projevila až restartem obou kontejnerů, což je přesně to, čemu
 * se celá platformní sekce vyhýbá.
 *
 * Řeší se to nejlacinější možnou cestou — **provider se staví znovu jen tehdy, když se změní
 * to, z čeho vzniká**: provider, model a *otisk* klíče. Otisk schválně, ne klíč samotný:
 * porovnávat se dá bez rozbalování, takže běžný požadavek na KMS vůbec nesáhne. Čtení
 * konfigurace stojí na cache v [PlatformConfig], takže tohle není dotaz do databáze na každou
 * recenzi.
 *
 * Chybějící klíč u nastaveného providera **není výjimka**: [ReplySuggestion.Unavailable] projde
 * doručením a do kanálu jde prázdný vstup. Provozovatel to vidí v consoli u nastavení, klient
 * se nedozví o ničem horším než o chybějícím návrhu — a hlavně mu kvůli tomu nepřestane chodit
 * recenze. (Při startu z prostředí to dřív bylo `error()`, protože špatný config měl spadnout
 * hned; tady je to hodnota, kterou někdo mění za běhu.)
 */
class ConfiguredSuggestReplyProvider(
    private val config: PlatformConfig,
    private val httpClient: () -> HttpClient,
) : SuggestReplyProvider {
    private val current = AtomicReference<Built?>(null)

    override suspend fun suggest(request: ReplySuggestionRequest): ReplySuggestion = provider().suggest(request)

    private fun provider(): SuggestReplyProvider {
        val signature =
            Signature(
                provider = config.text(PlatformSettings.AI_PROVIDER) ?: SuggestReplyProviders.NONE,
                model = config.text(PlatformSettings.AI_MODEL),
                keyFingerprint = config.secretFingerprint(PlatformSettings.AI_API_KEY),
            )
        current.get()?.takeIf { it.signature == signature }?.let { return it.provider }

        val built =
            try {
                SuggestReplyProviders.fromConfig(
                    provider = signature.provider,
                    apiKey = config.secret(PlatformSettings.AI_API_KEY)?.value,
                    model = signature.model,
                    httpClient = httpClient,
                )
            } catch (error: IllegalStateException) {
                logger.warn(error) { "AI provider '${signature.provider}' není použitelný, návrhy odpovědí se nebudou generovat" }
                NoSuggestReplyProvider
            }
        current.set(Built(signature, built))
        logger.info { "AI provider nastavený na '${signature.provider}' (model ${signature.model ?: "výchozí"})" }
        return built
    }

    /** Trojice, ze které provider vzniká. Klíč jen otiskem — rozbalovat ho kvůli porovnání nemá smysl. */
    private data class Signature(
        val provider: String,
        val model: String?,
        val keyFingerprint: String?,
    )

    private class Built(
        val signature: Signature,
        val provider: SuggestReplyProvider,
    )
}
