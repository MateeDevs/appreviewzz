package cz.matee.appreviewzz.ai

import cz.matee.appreviewzz.core.model.PlatformSettings
import cz.matee.appreviewzz.core.port.AnalysisRequest
import cz.matee.appreviewzz.core.port.AnalysisResult
import cz.matee.appreviewzz.core.port.ReviewAnalysisProvider
import cz.matee.appreviewzz.core.usecase.PlatformConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * Provider rozborů řízený platformní konfigurací — stejný princip jako
 * [ConfiguredSuggestReplyProvider]: provider se staví znovu jen tehdy, když se změní to,
 * z čeho vzniká (provider, model, *otisk* klíče). Přepnutí modelu v consoli se tak projeví
 * bez restartu, což je celý smysl otázky „flash nebo flash-lite" — ta se rozhoduje měřením,
 * ne dopředu.
 *
 * Chybějící klíč u nastaveného providera **není výjimka**: [AnalysisResult.Unavailable]
 * projde doručením a recenze dorazí bez štítků.
 */
class ConfiguredReviewAnalysisProvider(
    private val config: PlatformConfig,
    private val httpClient: () -> HttpClient,
) : ReviewAnalysisProvider {
    private val current = AtomicReference<Built?>(null)

    override suspend fun analyze(request: AnalysisRequest): AnalysisResult = provider().analyze(request)

    private fun provider(): ReviewAnalysisProvider {
        val signature =
            Signature(
                provider = config.text(PlatformSettings.AI_PROVIDER) ?: AiProviders.NONE,
                analysisModel = config.text(PlatformSettings.AI_ANALYSIS_MODEL),
                keyFingerprint = config.secretFingerprint(PlatformSettings.AI_API_KEY),
            )
        current.get()?.takeIf { it.signature == signature }?.let { return it.provider }

        val built =
            try {
                AiProviders.analysisFromConfig(
                    provider = signature.provider,
                    apiKey = config.secret(PlatformSettings.AI_API_KEY)?.value,
                    model = signature.analysisModel,
                    httpClient = httpClient,
                )
            } catch (error: IllegalStateException) {
                logger.warn(error) { "AI provider '${signature.provider}' není použitelný, rozbory recenzí se nebudou počítat" }
                NoReviewAnalysisProvider
            }
        current.set(Built(signature, built))
        logger.info { "Rozbory recenzí jedou na '${signature.provider}' (model ${signature.analysisModel ?: "výchozí"})" }
        return built
    }

    private data class Signature(
        val provider: String,
        val analysisModel: String?,
        val keyFingerprint: String?,
    )

    private class Built(
        val signature: Signature,
        val provider: ReviewAnalysisProvider,
    )
}
