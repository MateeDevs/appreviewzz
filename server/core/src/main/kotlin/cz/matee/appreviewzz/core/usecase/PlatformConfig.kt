package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.PlatformSettingDefinition
import cz.matee.appreviewzz.core.model.PlatformSettingException
import cz.matee.appreviewzz.core.model.PlatformSettingSource
import cz.matee.appreviewzz.core.model.PlatformSettings
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.port.PlatformSecretRepository
import cz.matee.appreviewzz.core.port.PlatformSecretVault
import cz.matee.appreviewzz.core.port.PlatformSettingRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Hodnota, která právě platí, i s tím, odkud je. Console podle [source] ukazuje, jestli
 * se dívá na výchozí stav, na proměnnou prostředí, nebo na uložené nastavení — bez toho
 * by se dalo dlouho hledat, proč uložená hodnota „nic nedělá".
 */
data class ResolvedSetting(
    val definition: PlatformSettingDefinition,
    /** U tajemství vždy `null` — hodnota se z API nevrací. */
    val value: String?,
    val source: PlatformSettingSource,
)

/**
 * Jak často se stahují recenze. Vlastní rozhraní, aby [AppService] nemusel znát celý
 * konfigurační aparát a testy si mohly dosadit dvě čísla.
 */
interface IngestPolicy {
    fun defaultIntervalMinutes(): Int

    fun minIntervalMinutes(): Int

    /** Strop počtu aplikací na organizaci; `0` znamená bez omezení. */
    fun maxAppsPerOrg(): Int

    companion object {
        /** Pevné hodnoty pro testy a pro běh bez platformní konfigurace. */
        fun fixed(
            default: Int = DEFAULT_INTERVAL,
            min: Int = DEFAULT_MIN_INTERVAL,
            maxApps: Int = 0,
        ): IngestPolicy =
            object : IngestPolicy {
                override fun defaultIntervalMinutes(): Int = default

                override fun minIntervalMinutes(): Int = min

                override fun maxAppsPerOrg(): Int = maxApps
            }

        private const val DEFAULT_INTERVAL = 30
        private const val DEFAULT_MIN_INTERVAL = 15
    }
}

/**
 * Čtení platformní konfigurace (F7.2, [ADR 0018]).
 *
 * **Pořadí přebíjení: databáze > prostředí > výchozí hodnota v kódu.** Obráceně to nejde —
 * kdyby vyhrávalo prostředí, správce platformy by v consoli ukládal hodnotu, která nic nedělá.
 *
 * **Cache s TTL.** API a worker jsou dva procesy; sdílená invalidace by znamenala LISTEN/NOTIFY
 * nebo restart. Půl minuty zpoždění u změny konfigurace nikdo nepozná a console to u uložení
 * napíše. Čte se přes `AtomicReference`, takže souběžné požadavky si snímek nerozbijí — nejhůř
 * ho dva načtou zbytečně dvakrát.
 */
class PlatformConfig(
    private val settings: PlatformSettingRepository,
    private val secrets: PlatformSecretRepository? = null,
    private val vault: PlatformSecretVault? = null,
    private val env: (String) -> String? = System::getenv,
    private val clock: Clock = Clock.System,
    private val ttl: Duration = DEFAULT_TTL,
) : IngestPolicy {
    private val snapshot = AtomicReference<Snapshot?>(null)

    fun resolve(key: String): ResolvedSetting {
        val definition = PlatformSettings.require(key)
        // Tajemství neleží v `platform_setting`, ale ve vlastní tabulce — bez tohohle by
        // uložený klíč navěky hlásil, že je „z prostředí".
        val stored = if (definition.secret) secrets?.findMeta(key)?.fingerprint else stored()[key]
        val fromEnv = definition.envName?.let { env(it) }?.takeIf { it.isNotBlank() }
        val source =
            when {
                stored != null -> PlatformSettingSource.DB
                fromEnv != null -> PlatformSettingSource.ENV
                else -> PlatformSettingSource.DEFAULT
            }
        // U tajemství se nevrací ani hodnota z prostředí: přes API by z něj byl čtecí kanál
        // na `AI_API_KEY`, což je přesně to, čemu se write-only úložiště vyhýbá.
        val value = if (definition.secret) null else stored ?: fromEnv ?: definition.default
        return ResolvedSetting(definition, value, source)
    }

    /** Všechno, co se dá ukázat ve formuláři. Tajemství mají vlastní cestu ([secretMeta]). */
    fun resolveAll(): List<ResolvedSetting> = PlatformSettings.ALL.map { resolve(it.key) }

    fun int(key: String): Int {
        val resolved = resolve(key)
        val raw = resolved.value ?: throw PlatformSettingException("Nastavení '$key' nemá hodnotu")
        return raw.toIntOrNull() ?: run {
            // Do databáze se ukládá jen zvalidovaná hodnota, takže sem se dá dojít jen ruční
            // editací řádku. Radši výchozí hodnota a hlášku do logu než pád workeru.
            logger.warn { "Nastavení '$key' má nečíselnou hodnotu '$raw', beru výchozí ${resolved.definition.default}" }
            resolved.definition.default?.toIntOrNull() ?: 0
        }
    }

    fun text(key: String): String? = resolve(key).value?.takeIf { it.isNotBlank() }

    fun bool(key: String): Boolean = resolve(key).value?.toBooleanStrictOrNull() == true

    /**
     * Hodnota tajemství pro běh aplikace (ne pro API). Uložené v databázi přebíjí prostředí
     * stejně jako u ostatních klíčů; bez správce klíčů zbývá jen prostředí.
     */
    fun secret(key: String): SecretPayload? {
        val definition = PlatformSettings.require(key)
        val sealed = secrets?.findSealed(key)
        if (sealed != null && vault != null) return vault.open(key, sealed)
        return definition.envName
            ?.let { env(it) }
            ?.takeIf { it.isNotBlank() }
            ?.let(::SecretPayload)
    }

    /**
     * Otisk uložené hodnoty. Používá ho AI vrstva, aby poznala, že se klíč změnil, aniž by
     * ho musela pokaždé rozbalovat.
     */
    fun secretFingerprint(key: String): String? =
        secrets?.findMeta(key)?.fingerprint
            ?: PlatformSettings
                .require(key)
                .envName
                ?.let { env(it) }
                ?.takeIf { it.isNotBlank() }
                ?.let { "env" }

    override fun defaultIntervalMinutes(): Int = int(PlatformSettings.INGEST_DEFAULT_INTERVAL)

    override fun minIntervalMinutes(): Int = int(PlatformSettings.INGEST_MIN_INTERVAL)

    override fun maxAppsPerOrg(): Int = int(PlatformSettings.MAX_APPS_PER_ORG)

    /** Po zápisu — aby ten, kdo právě uložil, viděl výsledek hned, ne za půl minuty. */
    fun invalidate() = snapshot.set(null)

    private fun stored(): Map<String, String> {
        val now = clock.now()
        snapshot.get()?.takeIf { it.expiresAt > now }?.let { return it.values }
        val loaded = settings.all()
        snapshot.set(Snapshot(loaded, now + ttl))
        return loaded
    }

    private class Snapshot(
        val values: Map<String, String>,
        val expiresAt: Instant,
    )

    companion object {
        val DEFAULT_TTL = 30.seconds
    }
}
