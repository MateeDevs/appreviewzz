package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.CredentialId
import cz.matee.appreviewzz.core.model.CredentialPurpose
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.ValidationStatus
import cz.matee.appreviewzz.core.port.AppRepository
import cz.matee.appreviewzz.core.port.CredentialRepository
import cz.matee.appreviewzz.core.port.ReviewSource
import cz.matee.appreviewzz.core.port.SecretResolver
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreContext
import cz.matee.appreviewzz.core.port.ValidationOutcome
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

private val logger = KotlinLogging.logger {}

data class RevalidationReport(
    val checked: Int,
    val nowValid: Int,
    val stillFailing: Int,
) {
    val touched: Boolean get() = checked > 0
}

/**
 * Průběžné doověřování klíčů, které zatím nefungují.
 *
 * Existuje kvůli Google Play: klient pozve náš service account do Play Console a **pozvánka
 * se propaguje se zpožděním** — obvykle minuty, výjimečně dýl. Bez tohohle by musel u dialogu
 * sedět a mačkat „zkontrolovat", případně se druhý den vrátit a přijít na to, že už to jde.
 * Takhle se stav v consoli překlopí sám a klient mezitím může zavřít prohlížeč.
 *
 * Dvě meze, které tomu drží náklady:
 *
 * - Zkouší se **jen klíče, které nefungují** (`UNKNOWN`/`INVALID`). Fungující klíč se
 *   doověřuje ingestem, ne tímhle.
 * - Zkouší se **jen [window] od posledního pokusu**. Po dvou dnech už to není čekání na
 *   Google, ale klient, který pozvánku neodeslal — a ten potřebuje člověka, ne další HTTP
 *   volání každou čtvrthodinu donekonečna.
 */
class RevalidateCredentialsUseCase(
    private val apps: AppRepository,
    private val credentials: CredentialRepository,
    private val secrets: SecretResolver,
    private val sources: List<ReviewSource>,
    private val clock: Clock = Clock.System,
    /** Jak dlouho od posledního pokusu se ještě zkouší. */
    private val window: Duration = DEFAULT_WINDOW,
) {
    suspend fun revalidate(): RevalidationReport {
        val now = clock.now()
        // Jeden klíč visí často na víc appkách; stav je ale na klíči, takže druhý pokus
        // v témže běhu by jen přepsal výsledek prvního.
        val done = mutableSetOf<CredentialId>()
        var checked = 0
        var nowValid = 0
        var failing = 0

        apps.listEnabled().forEach { app ->
            app.platforms().forEach { platform ->
                val meta =
                    credentials.findForApp(app.orgId, app.id, CredentialPurpose.REVIEWS, credentialType(platform))
                        ?: return@forEach
                if (meta.validationStatus == ValidationStatus.VALID) return@forEach
                if (!done.add(meta.id)) return@forEach
                val since = meta.validatedAt ?: meta.createdAt
                if (now - since > window) return@forEach

                checked++
                val outcome = validate(app, platform, meta.id)
                credentials.recordValidation(
                    app.orgId,
                    meta.id,
                    if (outcome.valid) ValidationStatus.VALID else ValidationStatus.INVALID,
                    outcome.message.takeUnless { outcome.valid },
                    now,
                )
                if (outcome.valid) {
                    nowValid++
                    logger.info { "Klíč ${meta.id} (${meta.type}) začal fungovat — appka ${app.id} se rozjede" }
                } else {
                    failing++
                }
            }
        }

        val report = RevalidationReport(checked, nowValid, failing)
        if (report.touched) {
            logger.info { "Revalidace klíčů: ověřeno $checked, nově funguje $nowValid, pořád nefunguje $failing" }
        }
        return report
    }

    private suspend fun validate(
        app: App,
        platform: Platform,
        credentialId: CredentialId,
    ): ValidationOutcome {
        val identifier = app.storeIdentifier(platform) ?: return invalid("Aplikace nemá identifikátor pro $platform")
        val source = sources.firstOrNull { it.platform == platform } ?: return invalid("Pro $platform není konektor")
        return try {
            source.validate(StoreContext(identifier, secrets.resolve(app.orgId, credentialId)))
        } catch (error: StoreConnectorException) {
            // Chyba konektoru je výsledek ověření, ne pád jobu: běh musí dojet i pro ostatní appky.
            invalid("${error.kind}: ${error.message}")
        } catch (error: RuntimeException) {
            // Nerozbalitelný klíč (rotovaný DEK, poškozený ciphertext) nesmí shodit celý běh.
            logger.warn(error) { "Klíč $credentialId organizace ${app.orgId} nejde ověřit" }
            invalid("Klíč se nepodařilo načíst")
        }
    }

    private fun invalid(message: String) = ValidationOutcome(valid = false, message = message)

    private fun credentialType(platform: Platform): CredentialType =
        when (platform) {
            Platform.ANDROID -> CredentialType.GP_SERVICE_ACCOUNT
            Platform.IOS -> CredentialType.ASC_API_KEY
        }

    companion object {
        /**
         * Dva dny. Vendoři u propagace práv v Play Console uvádějí až 24–36 hodin, takže
         * kratší okno by se dalo minout; delší už jen bušíme do storu za klienta, který
         * pozvánku neodeslal.
         */
        val DEFAULT_WINDOW: Duration = 48.hours
    }
}
