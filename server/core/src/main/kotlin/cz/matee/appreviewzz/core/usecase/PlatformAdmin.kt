package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.PlatformRole
import cz.matee.appreviewzz.core.model.PlatformSettingException
import cz.matee.appreviewzz.core.model.PlatformSettings
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.model.User
import cz.matee.appreviewzz.core.model.UserId
import cz.matee.appreviewzz.core.port.AppRepository
import cz.matee.appreviewzz.core.port.PlatformAuditEntry
import cz.matee.appreviewzz.core.port.PlatformAuditRepository
import cz.matee.appreviewzz.core.port.PlatformSecretMeta
import cz.matee.appreviewzz.core.port.PlatformSecretRepository
import cz.matee.appreviewzz.core.port.PlatformSecretVault
import cz.matee.appreviewzz.core.port.PlatformSettingRepository
import cz.matee.appreviewzz.core.port.PlatformStats
import cz.matee.appreviewzz.core.port.PlatformStatsRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

/**
 * Kdo mění platformní konfiguraci. Vlastní typ, ne [OrgActor] — ten nese roli v organizaci
 * a tady žádná organizace není.
 */
data class PlatformActor(
    val userId: UserId,
    val label: String,
) {
    companion object {
        fun of(user: User): PlatformActor = PlatformActor(user.id, user.displayName ?: user.email)

        /** Seed CLI. Jednorázová správa z terminálu je pořád akce, která patří do auditu. */
        val CLI = PlatformActor(userId = UserId(kotlin.uuid.Uuid.NIL), label = "cli")
    }
}

/** Aplikace s výjimkou intervalu, jak ji vidí správce platformy. */
data class PlatformAppSummary(
    val app: App,
    val effectiveIntervalMinutes: Int,
)

/**
 * Zápisová strana platformní správy (F7.3, [ADR 0018]).
 *
 * Kontrolu role tu **nenajdeš**: drží ji strom cest (`requirePlatformAdmin`), takže se na ni
 * nedá zapomenout v jednom handleru z osmi. Tahle třída hlídá to druhé — že se do konfigurace
 * nedostane hodnota mimo katalog a že po každé změně zůstane záznam.
 */
class PlatformAdminService(
    private val config: PlatformConfig,
    private val settings: PlatformSettingRepository,
    private val secrets: PlatformSecretRepository,
    private val audit: PlatformAuditRepository,
    private val stats: PlatformStatsRepository,
    private val apps: AppRepository,
    /** `null` bez `VAULT_KEK_URI` — tajemství se pak nedají ukládat a console to řekne větou. */
    private val vault: PlatformSecretVault? = null,
    private val clock: Clock = Clock.System,
) {
    fun settings(): List<ResolvedSetting> = config.resolveAll()

    /**
     * Uložení změn. `null` u klíče znamená **zrušit uložené** a spadnout zpátky na prostředí,
     * resp. na výchozí hodnotu — proto se maže, ne ukládá prázdný řetězec.
     */
    fun updateSettings(
        actor: PlatformActor,
        changes: Map<String, String?>,
    ): List<ResolvedSetting> {
        if (changes.isEmpty()) return settings()

        // Nejdřív se zvaliduje všechno, teprve pak se zapisuje. Půlka uložených změn je horší
        // stav než žádná: formulář by se vrátil s chybou a část hodnot by už platila.
        val validated =
            changes.mapValues { (key, raw) ->
                val definition = PlatformSettings.require(key)
                if (definition.secret) {
                    throw PlatformSettingException("Nastavení '$key' je tajemství — ukládá se přes vlastní endpoint")
                }
                raw?.takeIf { it.isNotBlank() }?.let(definition::validate)
            }

        val before = config.resolveAll().associate { it.definition.key to it.value }
        val now = clock.now()
        validated.forEach { (key, value) ->
            if (value == null) settings.delete(key) else settings.upsert(key, value, actor.userId, now)
        }
        config.invalidate()

        val after = config.resolveAll().associate { it.definition.key to it.value }
        validated.keys
            .filter { before[it] != after[it] }
            .forEach { key ->
                record(
                    actor,
                    action = "platform.setting.changed",
                    targetKey = key,
                    metadata =
                        buildMap {
                            put("from", before[key] ?: "—")
                            put("to", after[key] ?: "—")
                        },
                )
                logger.info { "Nastavení '$key' změnil ${actor.label}: ${before[key]} → ${after[key]}" }
            }
        return settings()
    }

    fun secrets(): List<PlatformSecretMeta> {
        val stored = secrets.listMeta().associateBy { it.key }
        return PlatformSettings.secrets.mapNotNull { stored[it.key] }
    }

    /** Uložení tajemství. Hodnota se sem dostane naposledy — zpátky ji nedostane nikdo. */
    fun setSecret(
        actor: PlatformActor,
        key: String,
        value: SecretPayload,
    ) {
        val definition = PlatformSettings.require(key)
        if (!definition.secret) throw PlatformSettingException("Nastavení '$key' není tajemství")
        val box =
            vault ?: throw PlatformSettingException(
                "Ukládání tajemství potřebuje správce klíčů (VAULT_KEK_URI) — bez něj by klíč ležel v databázi otevřeně",
            )
        definition.validate(value.value)

        val before = secrets.findMeta(key)?.fingerprint
        val fingerprint = value.fingerprint()
        secrets.upsert(
            key = key,
            secret = box.seal(key, value),
            fingerprint = fingerprint,
            hint = hint(value),
            actor = actor.userId,
            at = clock.now(),
        )
        config.invalidate()
        record(
            actor,
            action = "platform.secret.set",
            targetKey = key,
            metadata = mapOf("from" to (before ?: "—"), "to" to fingerprint),
        )
        logger.info { "Platformní tajemství '$key' uložil ${actor.label} (otisk $fingerprint)" }
    }

    fun removeSecret(
        actor: PlatformActor,
        key: String,
    ) {
        val definition = PlatformSettings.require(key)
        if (!definition.secret) throw PlatformSettingException("Nastavení '$key' není tajemství")
        val before = secrets.findMeta(key)?.fingerprint ?: return
        secrets.delete(key)
        config.invalidate()
        record(
            actor,
            action = "platform.secret.removed",
            targetKey = key,
            metadata = mapOf("from" to before),
        )
        logger.info { "Platformní tajemství '$key' zrušil ${actor.label}" }
    }

    fun stats(): PlatformStats = stats.stats()

    fun auditTrail(limit: Int = AUDIT_LIMIT): List<PlatformAuditEntry> = audit.listRecent(limit)

    /** Aplikace, které mají vlastní interval. Jen ony — výpis všech appek platformě nepatří. */
    fun appsWithIntervalOverride(): List<PlatformAppSummary> =
        apps
            .listWithIntervalOverride()
            .map { PlatformAppSummary(it, it.ingestIntervalMinutes ?: config.defaultIntervalMinutes()) }

    /**
     * Výjimka intervalu pro konkrétní aplikaci. `null` ji ruší a appka se vrátí k platformní
     * výchozí hodnotě.
     */
    fun setAppInterval(
        actor: PlatformActor,
        appId: AppId,
        minutes: Int?,
    ): PlatformAppSummary {
        val current =
            apps.findAnyById(appId)
                ?: throw ConsoleException(ConsoleFailure.NOT_FOUND, "Taková aplikace tu není")
        val value =
            minutes?.let {
                val floor = config.minIntervalMinutes()
                if (it < floor) {
                    throw PlatformSettingException("Interval nesmí být kratší než platformní podlaha $floor minut")
                }
                if (it > PlatformSettings.MAX_ALLOWED_INTERVAL) {
                    throw PlatformSettingException("Interval nesmí být delší než ${PlatformSettings.MAX_ALLOWED_INTERVAL} minut")
                }
                it
            }
        val updated =
            apps.updateIngestInterval(appId, value)
                ?: throw ConsoleException(ConsoleFailure.NOT_FOUND, "Taková aplikace tu není")
        record(
            actor,
            action = "platform.app.interval",
            targetKey = appId.toString(),
            metadata =
                mapOf(
                    "from" to (current.ingestIntervalMinutes?.toString() ?: "platformní"),
                    "to" to (value?.toString() ?: "platformní"),
                ),
        )
        return PlatformAppSummary(updated, value ?: config.defaultIntervalMinutes())
    }

    /**
     * Zápis do auditu z míst mimo tuhle třídu — udělení role ze seed CLI. Vlastní metoda,
     * aby audit nemusel být veřejnou závislostí volajícího.
     */
    fun record(
        actor: PlatformActor,
        action: String,
        targetKey: String?,
        metadata: Map<String, String> = emptyMap(),
    ) {
        audit.append(
            PlatformAuditEntry(
                actorUserId = actor.userId.takeIf { it != PlatformActor.CLI.userId },
                actorLabel = actor.label,
                action = action,
                targetKey = targetKey,
                metadata = metadata,
                createdAt = clock.now(),
            ),
        )
    }

    /**
     * Neutrální nápověda, aby člověk v consoli poznal, který klíč uložil. Jen délka a konec —
     * pár posledních znaků API klíče je to, podle čeho se rozeznává v konzoli providera,
     * a samo o sobě to k ničemu není.
     */
    private fun hint(value: SecretPayload): String {
        val raw = value.value
        val tail = raw.takeLast(HINT_TAIL)
        return "${raw.length} znaků, končí na …$tail"
    }

    private companion object {
        const val AUDIT_LIMIT = 100
        const val HINT_TAIL = 4
    }
}

/**
 * Kontrola platformní role pro místa, která nestojí na stromě cest (seed CLI).
 * V HTTP vrstvě to dělá `requirePlatformAdmin`.
 */
fun requirePlatformRole(user: User) {
    if (user.platformRole != PlatformRole.SUPERADMIN) {
        throw ConsoleException(ConsoleFailure.FORBIDDEN, "Tohle spravuje provozovatel platformy")
    }
}
