package cz.matee.appreviewzz.core.model

/**
 * Katalog platformní konfigurace (F7.2, [ADR 0018]).
 *
 * Typovaný seznam, ne volná key/value tabulka. Volná konfigurace je místo, kde překlep
 * v názvu klíče přežije roky a nikdo se to nedozví — tady je neznámý klíč chyba požadavku
 * a console si z katalogu vykreslí formulář včetně vět pro člověka.
 *
 * Co v katalogu **není** a nikdy nebude: hodnoty, bez kterých se aplikace nespustí
 * (`DATABASE_*`, `VAULT_KEK_URI`, `SERVER_*`). Konfigurace uložená v databázi, bez které
 * se k databázi nedostaneme, je kruh.
 */
enum class PlatformSettingType {
    INT,
    TEXT,
    BOOL,
    ENUM,

    /** Uloží se zapečetěné a ven jde jen otisk — hodnota se z API nevrací nikdy. */
    SECRET,
}

/** Odkud je hodnota, která právě platí. Console to ukazuje, aby bylo vidět, co změna udělá. */
enum class PlatformSettingSource {
    /** Výchozí hodnota z kódu. */
    DEFAULT,

    /** Proměnná prostředí — v consoli se dá přebít. */
    ENV,

    /** Uloženo v databázi; smazání vrátí hodnotu o patro níž. */
    DB,
}

/**
 * Jedna položka katalogu.
 *
 * @param envName proměnná prostředí, ze které se hodnota bere, dokud ji nikdo neuloží.
 *   `null` u klíčů, které v prostředí nikdy nežily.
 * @param default výchozí hodnota v textové podobě; `null` znamená „nenastaveno".
 */
data class PlatformSettingDefinition(
    val key: String,
    val type: PlatformSettingType,
    val section: String,
    /** Popisek u pole ve formuláři. */
    val label: String,
    /** Věta pod polem: co ta hodnota dělá a co se stane, když se změní. */
    val help: String,
    val default: String? = null,
    val envName: String? = null,
    val options: List<String> = emptyList(),
    val min: Int? = null,
    val max: Int? = null,
) {
    val secret: Boolean get() = type == PlatformSettingType.SECRET

    /**
     * Kontrola hodnoty proti katalogu. Vrací normalizovaný text k uložení, nebo hlásí,
     * co je s ní špatně — stejná věta pak jde do console i do CLI.
     */
    fun validate(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) throw PlatformSettingException("$label: hodnota je prázdná")
        return when (type) {
            PlatformSettingType.INT -> {
                val number =
                    value.toIntOrNull()
                        ?: throw PlatformSettingException("$label: čekám celé číslo, dostalo '$value'")
                if ((min != null && number < min) || (max != null && number > max)) {
                    throw PlatformSettingException("$label: musí být mezi $min a $max, dostalo $number")
                }
                number.toString()
            }

            PlatformSettingType.BOOL ->
                when (value.lowercase()) {
                    "true", "false" -> value.lowercase()
                    else -> throw PlatformSettingException("$label: čekám true nebo false, dostalo '$value'")
                }

            PlatformSettingType.ENUM ->
                options.firstOrNull { it.equals(value, ignoreCase = true) }
                    ?: throw PlatformSettingException("$label: zná ${options.joinToString(", ")}, dostalo '$value'")

            PlatformSettingType.TEXT, PlatformSettingType.SECRET -> value
        }
    }
}

class PlatformSettingException(
    message: String,
) : RuntimeException(message)

object PlatformSettings {
    const val SECTION_INGEST = "Stahování recenzí"
    const val SECTION_AI = "AI návrhy odpovědí"
    const val SECTION_LIMITS = "Limity"
    const val SECTION_GOOGLE_PLAY = "Napojení Google Play"

    /**
     * Jak často se stahují recenze aplikacím, které nemají výjimku. Není to preference
     * klienta: je to kvóta store API, zátěž workeru a počet volání do AI v jednom čísle.
     */
    const val INGEST_DEFAULT_INTERVAL = "ingest.default_interval_minutes"

    /** Podlaha, pod kterou nesmí ani výjimka pro konkrétní appku. */
    const val INGEST_MIN_INTERVAL = "ingest.min_interval_minutes"

    const val AI_PROVIDER = "ai.provider"
    const val AI_MODEL = "ai.model"
    const val AI_API_KEY = "ai.api_key"

    const val MAX_APPS_PER_ORG = "limits.max_apps_per_org"

    /**
     * Provisioner service accountů (onboarding Google Play). Bez těchhle dvou klíčů dialog
     * „Připojit Google Play" jen řekne, že platforma není nastavená — ruční nahrání klíče
     * funguje pořád.
     */
    const val GCP_PROVISIONER_PROJECT = "gcp.provisioner.project_id"
    const val GCP_PROVISIONER_KEY = "gcp.provisioner.service_account_json"

    /** Providery zná továrna v modulu `ai`; katalog jen nabízí, z čeho se vybírá. */
    private val AI_PROVIDERS = listOf("none", "gemini")

    val ALL: List<PlatformSettingDefinition> =
        listOf(
            PlatformSettingDefinition(
                key = INGEST_DEFAULT_INTERVAL,
                type = PlatformSettingType.INT,
                section = SECTION_INGEST,
                label = "Interval stahování (minuty)",
                help =
                    "Platí pro všechny aplikace bez vlastní výjimky. Worker si změnu vyzvedne " +
                        "při nejbližším sweepu, restart není potřeba.",
                default = "30",
                min = MIN_ALLOWED_INTERVAL,
                max = MAX_ALLOWED_INTERVAL,
            ),
            PlatformSettingDefinition(
                key = INGEST_MIN_INTERVAL,
                type = PlatformSettingType.INT,
                section = SECTION_INGEST,
                label = "Nejkratší povolený interval (minuty)",
                help = "Podlaha i pro výjimky u konkrétní aplikace — pod ni se nedostane nikdo.",
                default = "15",
                min = MIN_ALLOWED_INTERVAL,
                max = MAX_ALLOWED_INTERVAL,
            ),
            PlatformSettingDefinition(
                key = AI_PROVIDER,
                type = PlatformSettingType.ENUM,
                section = SECTION_AI,
                label = "Provider",
                help = "Volba „none“ znamená bez AI: do kanálu chodí prázdný vstup a odpověď píše člověk.",
                default = "none",
                envName = "AI_PROVIDER",
                options = AI_PROVIDERS,
            ),
            PlatformSettingDefinition(
                key = AI_MODEL,
                type = PlatformSettingType.TEXT,
                section = SECTION_AI,
                label = "Model",
                help = "Prázdné = výchozí model providera.",
                envName = "AI_MODEL",
            ),
            PlatformSettingDefinition(
                key = AI_API_KEY,
                type = PlatformSettingType.SECRET,
                section = SECTION_AI,
                label = "API klíč",
                help = "Uloží se zašifrovaný. Zpátky ho nedostane nikdo — jen ho jde přepsat nebo zrušit.",
                envName = "AI_API_KEY",
            ),
            PlatformSettingDefinition(
                key = MAX_APPS_PER_ORG,
                type = PlatformSettingType.INT,
                section = SECTION_LIMITS,
                label = "Maximum aplikací na organizaci",
                help = "0 znamená bez omezení. Platí při zakládání, na existující aplikace nesahá.",
                default = "0",
                min = 0,
                max = MAX_APPS_CEILING,
            ),
            PlatformSettingDefinition(
                key = GCP_PROVISIONER_PROJECT,
                type = PlatformSettingType.TEXT,
                section = SECTION_GOOGLE_PLAY,
                label = "GCP projekt pro service accounty",
                help =
                    "Projekt, ve kterém vyrábíme service accounty zákazníkům. Musí v něm být zapnuté " +
                        "androidpublisher.googleapis.com a iam.googleapis.com.",
                envName = "GCP_PROVISIONER_PROJECT",
            ),
            PlatformSettingDefinition(
                key = GCP_PROVISIONER_KEY,
                type = PlatformSettingType.SECRET,
                section = SECTION_GOOGLE_PLAY,
                label = "Klíč provisioneru (service account JSON)",
                help =
                    "Service account s rolemi Service Account Admin a Service Account Key Admin nad tím " +
                        "projektem. Uloží se zašifrovaný a zpátky ho nedostane nikdo.",
                envName = "GCP_PROVISIONER_KEY",
            ),
        )

    private val byKey = ALL.associateBy { it.key }

    fun find(key: String): PlatformSettingDefinition? = byKey[key]

    fun require(key: String): PlatformSettingDefinition = byKey[key] ?: throw PlatformSettingException("Neznámé nastavení '$key'")

    /** Klíče, jejichž hodnota je tajemství — API i console je obsluhují jinou cestou. */
    val secrets: List<PlatformSettingDefinition> get() = ALL.filter { it.secret }

    val values: List<PlatformSettingDefinition> get() = ALL.filterNot { it.secret }

    /**
     * Krajní meze intervalu. Odpovídají `CHECK` v databázi — platformní podlaha se dá zvednout,
     * ne rozšířit pod tohle.
     */
    const val MIN_ALLOWED_INTERVAL = 5
    const val MAX_ALLOWED_INTERVAL = 1440
    private const val MAX_APPS_CEILING = 1000
}
