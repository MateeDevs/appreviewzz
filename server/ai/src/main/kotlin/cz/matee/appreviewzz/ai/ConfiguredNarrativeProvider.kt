package cz.matee.appreviewzz.ai

import cz.matee.appreviewzz.core.model.PlatformSettings
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.port.AnalysisNarrativeProvider
import cz.matee.appreviewzz.core.port.NarrativeRequest
import cz.matee.appreviewzz.core.port.NarrativeResult
import cz.matee.appreviewzz.core.usecase.PlatformConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/** Instalace bez AI: rozbor odejde ze šablony, bez úvodního odstavce. */
object NoNarrativeProvider : AnalysisNarrativeProvider {
    override suspend fun narrate(request: NarrativeRequest): NarrativeResult = NarrativeResult.Unavailable
}

/**
 * Shrnutí rozboru řízené platformní konfigurací (B6) — týž princip jako
 * [ConfiguredSuggestReplyProvider]. Model se bere z `ai.model`, ne z `ai.analysis_model`:
 * tagování je klasifikace, kde levnější model stačí, tohle je text pro člověka.
 */
class ConfiguredNarrativeProvider(
    private val config: PlatformConfig,
    private val httpClient: () -> HttpClient,
) : AnalysisNarrativeProvider {
    private val current = AtomicReference<Built?>(null)

    override suspend fun narrate(request: NarrativeRequest): NarrativeResult = provider().narrate(request)

    private fun provider(): AnalysisNarrativeProvider {
        val signature =
            Signature(
                provider = config.text(PlatformSettings.AI_PROVIDER) ?: AiProviders.NONE,
                model = config.text(PlatformSettings.AI_MODEL),
                keyFingerprint = config.secretFingerprint(PlatformSettings.AI_API_KEY),
            )
        current.get()?.takeIf { it.signature == signature }?.let { return it.provider }

        val built =
            when (signature.provider.lowercase()) {
                AiProviders.GEMINI -> {
                    val key = config.secret(PlatformSettings.AI_API_KEY)?.value?.takeIf { it.isNotBlank() }
                    if (key == null) {
                        logger.warn { "AI_PROVIDER=gemini bez klíče — rozbory půjdou bez shrnutí" }
                        NoNarrativeProvider
                    } else {
                        GeminiNarrativeProvider(
                            httpClient = httpClient(),
                            apiKey = SecretPayload(key),
                            model = signature.model?.takeIf { it.isNotBlank() } ?: GeminiSuggestReplyProvider.DEFAULT_MODEL,
                        )
                    }
                }

                else -> NoNarrativeProvider
            }
        current.set(Built(signature, built))
        return built
    }

    private data class Signature(
        val provider: String,
        val model: String?,
        val keyFingerprint: String?,
    )

    private class Built(
        val signature: Signature,
        val provider: AnalysisNarrativeProvider,
    )
}
