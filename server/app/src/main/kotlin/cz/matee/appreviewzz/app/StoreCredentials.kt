package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.connectors.appstore.AscApiKey
import cz.matee.appreviewzz.connectors.googleplay.GoogleServiceAccount
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.usecase.ConsoleException
import cz.matee.appreviewzz.core.usecase.ConsoleFailure
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Store, ke kterému klíč patří. Drží pohromadě to, co se jinak rozpadá do tří `when` bloků:
 * jak se typ píše (v consoli i na příkazové řádce), jaká je platforma a co se z klíče smí
 * vypsat člověku.
 *
 * Sedí v `app`, ne v doméně: rozparsovat `.p8` nebo service account umí jen konektor daného
 * storu, a na ten se jádro schválně neváže.
 */
enum class StoreCredentialKind(
    val aliases: Set<String>,
    val type: CredentialType,
    val platform: Platform,
) {
    GOOGLE_PLAY(
        aliases = setOf("gp", "google-play", "gp-service-account"),
        type = CredentialType.GP_SERVICE_ACCOUNT,
        platform = Platform.ANDROID,
    ),
    APP_STORE(
        aliases = setOf("asc", "app-store", "asc-api-key"),
        type = CredentialType.ASC_API_KEY,
        platform = Platform.IOS,
    ),
    ;

    /**
     * Rozparsuje klíč (tedy ověří, že je to vůbec klíč) a vrátí nápovědu pro člověka.
     * Chyba tady je levná: klient se to dozví hned při nahrání, ne až prvním ingestem.
     */
    fun describe(payload: SecretPayload): String =
        try {
            when (this) {
                GOOGLE_PLAY -> GoogleServiceAccount.parse(payload).clientEmail
                APP_STORE ->
                    AscApiKey.parse(payload).let { key ->
                        "Key ID ${key.keyId}" + if (key.isIndividual) " (individuální klíč)" else ""
                    }
            }
        } catch (error: StoreConnectorException) {
            throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Klíč nejde načíst: ${error.message}")
        }

    companion object {
        fun of(raw: String): StoreCredentialKind =
            entries.firstOrNull { raw.lowercase() in it.aliases }
                ?: throw ConsoleException(
                    ConsoleFailure.INVALID_INPUT,
                    "Typ zná 'gp' (service account Google Play) a 'asc' (klíč App Store Connect); " +
                        "instalace Slacku a Teams vznikají vlastním připojením",
                )

        fun of(type: CredentialType): StoreCredentialKind =
            entries.firstOrNull { it.type == type }
                ?: throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Credential typu $type nepatří ke storu, ale ke kanálu")
    }
}

/** Tvar, ve kterém se ukládá klíč App Store Connect: `.p8` plus dvě ID opsaná z ASC. */
@Serializable
data class AscKeyFile(
    val keyId: String,
    val issuerId: String?,
    val privateKey: String,
)

/**
 * Složení payloadu z toho, co člověk nahrál. Google Play je celý JSON service accountu,
 * App Store Connect je `.p8` (jen privátní klíč) plus Key ID a Issuer ID, které k němu
 * klient opisuje z konzole Applu.
 */
object StoreCredentialPayloads {
    private val json = Json { encodeDefaults = false }

    fun of(
        kind: StoreCredentialKind,
        content: String,
        keyId: String?,
        issuerId: String?,
    ): SecretPayload {
        if (content.isBlank()) throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Obsah klíče je prázdný")
        if (kind == StoreCredentialKind.GOOGLE_PLAY && (keyId != null || issuerId != null)) {
            throw ConsoleException(
                ConsoleFailure.INVALID_INPUT,
                "Key ID a Issuer ID patří ke klíči App Store Connect, ne k service accountu",
            )
        }
        return when {
            keyId != null -> SecretPayload(json.encodeToString(AscKeyFile.serializer(), AscKeyFile(keyId, issuerId, content)))
            issuerId != null -> throw ConsoleException(ConsoleFailure.INVALID_INPUT, "K Issuer ID patří i Key ID")
            kind == StoreCredentialKind.APP_STORE ->
                throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Klíč App Store Connect potřebuje i Key ID")

            else -> SecretPayload(content)
        }
    }
}
