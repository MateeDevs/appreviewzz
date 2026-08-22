package cz.matee.appreviewzz.app.cli

import cz.matee.appreviewzz.app.Components
import cz.matee.appreviewzz.app.SLACK_INSTALL_PATH
import cz.matee.appreviewzz.app.StoreCredentialKind
import cz.matee.appreviewzz.app.StoreCredentialPayloads
import cz.matee.appreviewzz.backup.BackupStoreException
import cz.matee.appreviewzz.backup.BackupToolException
import cz.matee.appreviewzz.backup.StoredBackup
import cz.matee.appreviewzz.channels.slack.SlackInstallStates
import cz.matee.appreviewzz.channels.teams.TeamsInstall
import cz.matee.appreviewzz.core.message.RatingsDigest
import cz.matee.appreviewzz.core.model.ActorType
import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.BackupRun
import cz.matee.appreviewzz.core.model.BackupStatus
import cz.matee.appreviewzz.core.model.ChannelType
import cz.matee.appreviewzz.core.model.CredentialId
import cz.matee.appreviewzz.core.model.CredentialMeta
import cz.matee.appreviewzz.core.model.CredentialPurpose
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.FailedJob
import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.OrgRole
import cz.matee.appreviewzz.core.model.Organization
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Review
import cz.matee.appreviewzz.core.model.ReviewState
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.model.Slugs
import cz.matee.appreviewzz.core.model.ValidationStatus
import cz.matee.appreviewzz.core.port.ChannelException
import cz.matee.appreviewzz.core.port.ChannelTarget
import cz.matee.appreviewzz.core.port.ConnectivityNotice
import cz.matee.appreviewzz.core.port.NewApp
import cz.matee.appreviewzz.core.port.NewChannel
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreContext
import cz.matee.appreviewzz.core.port.ValidationOutcome
import cz.matee.appreviewzz.core.port.auditEntry
import cz.matee.appreviewzz.core.usecase.AppInputs
import cz.matee.appreviewzz.core.usecase.ConsoleException
import cz.matee.appreviewzz.core.usecase.PlatformIngest
import cz.matee.appreviewzz.core.usecase.RatingsSkipReason
import cz.matee.appreviewzz.core.usecase.hintFor
import cz.matee.appreviewzz.crypto.CredentialNotFoundException
import cz.matee.appreviewzz.crypto.KeyManagementException
import kotlinx.datetime.LocalTime
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Příkazy seed CLI (F1.7). Dokud není console (F3), je tohle jediná cesta, jak do systému
 * dostat organizaci, aplikaci a klíče ke storům — a zároveň smyčka, na které se dá ověřit
 * akceptace F1: klíč se uloží zašifrovaný, ověří proti storu a ingest stáhne recenze.
 *
 * Zásady, které tu platí všude:
 *
 * - **Payload klíče se nikdy netiskne.** Ven jde fingerprint a neutrální nápověda (client_email,
 *   Key ID), tedy přesně to, co uvidí klient v consoli.
 * - **Každý zápis jde do audit logu** se `SYSTEM`/`cli`, aby po ručním zásahu zůstala stopa.
 * - **Chyba se hlásí větou, ne stack tracem** — tohle spouští člověk na produkci přes `docker exec`.
 */
class SeedCommands(
    private val components: Components,
    private val out: (String) -> Unit = ::println,
    private val clock: Clock = Clock.System,
) {
    fun orgCreate(args: Arguments) {
        val name = args.required("name")
        val slug = args.optional("slug") ?: slugify(name)
        requireValidSlug(slug)
        components.organizations.findBySlug(slug)?.let {
            throw CommandException("Organizace se slugem '$slug' už existuje (${it.id})")
        }

        val organization = components.organizations.create(name, slug)
        audit(organization.id, "org.created", "organization", organization.id.toString())
        out("Organizace založena")
        out(details("ID" to organization.id.toString(), "název" to organization.name, "slug" to organization.slug))
    }

    fun orgList() {
        val organizations = components.organizations.list()
        if (organizations.isEmpty()) {
            out("Zatím žádná organizace — založ ji příkazem `org create --name …`")
            return
        }
        organizations.forEach { out("${it.id}  ${it.slug.padEnd(SLUG_COLUMN)}  ${it.name}") }
    }

    fun userAdd(args: Arguments) {
        val organization = organization(args)
        val email = args.required("email")
        val role = orgRole(args.optional("role") ?: OrgRole.ADMIN.name)

        val user =
            components.users.findByEmail(email)
                ?: components.users.create(email, args.optional("name"))
        components.memberships.upsert(organization.id, user.id, role)
        audit(organization.id, "member.added", "user", user.id.toString(), mapOf("role" to role.name))

        out("Uživatel $email je v organizaci ${organization.slug} jako ${role.name.lowercase()}")
        out(details("ID" to user.id.toString(), "jméno" to (user.displayName ?: "—")))
    }

    fun appCreate(args: Arguments) {
        val organization = organization(args)
        val gpPackage = args.optional("gp-package")
        val ascAppId = args.optional("asc-app-id")
        if (gpPackage == null && ascAppId == null) {
            throw UsageException("Aplikace potřebuje aspoň jeden store — zadej --gp-package nebo --asc-app-id")
        }
        requireStoreIdentifiersFree(organization.id, gpPackage, ascAppId)

        // Výchozí hodnoty si drží doména (NewApp), ne CLI — jinak by se obojí rozešlo.
        val defaults =
            NewApp(
                name = args.required("name"),
                gpPackageName = gpPackage,
                gpReportingBucket = args.optional("gp-bucket")?.let { AppInputs.reportingBucket(it, "--gp-bucket") },
                ascAppId = ascAppId,
            )
        val app =
            components.apps.create(
                organization.id,
                defaults.copy(
                    locale = args.optional("locale")?.let(::locale) ?: defaults.locale,
                    timezone = args.optional("timezone")?.let(::timezone) ?: defaults.timezone,
                    notifyFrom = notifyFrom(args.optional("notify-from"), clock),
                    aiInstructions = args.optional("ai-instructions"),
                    ingestIntervalMinutes =
                        args.int("ingest-interval")?.let(::ingestInterval) ?: defaults.ingestIntervalMinutes,
                    dailyDigestAt = args.optional("digest-at")?.let(::digestAt) ?: defaults.dailyDigestAt,
                ),
            )
        audit(organization.id, "app.created", "app", app.id.toString())

        out("Aplikace založena")
        out(
            details(
                "ID" to app.id.toString(),
                "název" to app.name,
                "Google Play" to (app.gpPackageName ?: "—"),
                "Play reporting" to (app.gpReportingBucket ?: "— (hodnocení z veřejného listingu)"),
                "App Store" to (app.ascAppId ?: "—"),
                "jazyk" to app.locale.code,
                "časová zóna" to app.timezone,
                "notifikace od" to (app.notifyFrom?.toString() ?: "bez omezení"),
                "ingest" to "každých ${app.ingestIntervalMinutes} min",
                "denní přehled" to app.dailyDigestAt.toString(),
            ),
        )
        out("Worker si appku vyzvedne při nejbližším sweepu — restart není potřeba.")
    }

    fun appList(args: Arguments) {
        val organization = organization(args)
        val apps = components.apps.listByOrg(organization.id)
        if (apps.isEmpty()) {
            out("Organizace ${organization.slug} nemá žádnou aplikaci")
            return
        }
        apps.forEach { app ->
            val stores = app.platforms().joinToString("+") { it.name.lowercase() }
            val state = if (app.enabled) "zapnutá" else "vypnutá"
            out("${app.id}  ${stores.padEnd(STORES_COLUMN)}  ${state.padEnd(ENABLED_COLUMN)}  ${app.name}")
        }
    }

    fun credentialAdd(args: Arguments) {
        val organization = organization(args)
        val kind = StoreCredentialKind.of(args.required("type"))
        val payload = payload(kind, args)
        val hint = command { kind.describe(payload) }

        val meta = components.vault.store(organization.id, kind.type, args.required("label"), payload, hint)
        audit(
            organization.id,
            "credential.created",
            "credential",
            meta.id.toString(),
            mapOf("type" to meta.type.name, "fingerprint" to meta.fingerprint),
        )

        out("Credential uložený (zašifrovaný, payload už z vaultu nevyjde)")
        out(
            details(
                "ID" to meta.id.toString(),
                "typ" to meta.type.name,
                "štítek" to meta.label,
                "fingerprint" to meta.fingerprint,
                "nápověda" to (meta.hint ?: "—"),
            ),
        )
    }

    fun credentialList(args: Arguments) {
        val organization = organization(args)
        val credentials = components.credentials.listByOrg(organization.id)
        if (credentials.isEmpty()) {
            out("Organizace ${organization.slug} nemá žádný credential")
            return
        }
        credentials.forEach { meta ->
            out(
                "${meta.id}  ${meta.type.name.padEnd(TYPE_COLUMN)}  " +
                    "${meta.validationStatus.name.padEnd(STATUS_COLUMN)}  ${meta.label} — ${meta.hint ?: "bez nápovědy"}",
            )
        }
    }

    fun credentialAttach(args: Arguments) {
        val organization = organization(args)
        val app = app(organization.id, args)
        val meta = credential(organization.id, args)
        val purpose = purpose(args.optional("purpose") ?: CredentialPurpose.REVIEWS.name)

        val kind = StoreCredentialKind.of(meta.type)
        if (app.storeIdentifier(kind.platform) == null) {
            throw CommandException(
                "Aplikace ${app.name} nemá identifikátor pro ${kind.platform} — klíč ${meta.label} by neměla kam použít",
            )
        }

        components.credentials.attachToApp(organization.id, app.id, meta.id, purpose)
        audit(
            organization.id,
            "credential.attached",
            "app",
            app.id.toString(),
            mapOf("credential" to meta.id.toString(), "purpose" to purpose.name),
        )
        out("Credential ${meta.label} připojený k aplikaci ${app.name} pro ${purpose.name.lowercase()}")
    }

    /**
     * Ověřovací volání do storu. Výsledek se zapisuje do credentialu i do audit logu — je to
     * tatáž cesta, kterou ve F3 použije onboarding wizard, jen bez UI.
     */
    suspend fun credentialValidate(args: Arguments) {
        val organization = organization(args)
        val app = app(organization.id, args)
        val meta = credential(organization.id, args)
        val kind = StoreCredentialKind.of(meta.type)

        val identifier =
            app.storeIdentifier(kind.platform)
                ?: throw CommandException("Aplikace ${app.name} nemá identifikátor pro ${kind.platform}")
        val source =
            components.reviewSources.firstOrNull { it.platform == kind.platform }
                ?: throw CommandException("Pro ${kind.platform} není zaregistrovaný konektor")

        val secret =
            try {
                components.vault.load(organization.id, meta.id)
            } catch (error: CredentialNotFoundException) {
                throw CommandException("Credential ${meta.id} se nepodařilo načíst", error)
            } catch (error: KeyManagementException) {
                throw CommandException("Credential ${meta.id} nejde dešifrovat — sedí VAULT_KEK_URI?", error)
            }

        val outcome =
            try {
                source.validate(StoreContext(identifier, secret))
            } catch (error: StoreConnectorException) {
                ValidationOutcome(valid = false, message = "${error.kind}: ${error.message}")
            }

        val status = if (outcome.valid) ValidationStatus.VALID else ValidationStatus.INVALID
        components.credentials.recordValidation(
            organization.id,
            meta.id,
            status,
            outcome.message.takeUnless { outcome.valid },
            clock.now(),
        )
        audit(
            organization.id,
            if (outcome.valid) "credential.validated" else "credential.validation_failed",
            "credential",
            meta.id.toString(),
            mapOf("app" to app.id.toString()),
        )

        if (!outcome.valid) {
            throw CommandException("Klíč ${meta.label} neprošel: ${outcome.message ?: "store odpověď odmítl"}")
        }
        out("Klíč ${meta.label} funguje — ${kind.platform} aplikace ${app.name} ($identifier) je dostupná")
    }

    /**
     * Ruční spuštění ingestu mimo scheduler. Idempotentní stejně jako naplánovaný běh, takže
     * opakované volání je bezpečné — a právě na tom je vidět dedup.
     */
    suspend fun ingestRun(args: Arguments) {
        val organization = organization(args)
        val app = app(organization.id, args)

        val report = components.ingest.ingest(organization.id, app.id)
        audit(organization.id, "ingest.manual", "app", app.id.toString())

        out("Ingest ${app.name} (${app.id})")
        report.appSkipped?.let {
            out("  přeskočeno: $it")
            return
        }
        report.platforms.forEach { out("  " + it.describe()) }
        out("  k doručení do kanálů: ${report.notifiable.size}")

        report.failures.firstOrNull()?.let { failure ->
            throw CommandException("Ingest ${failure.platform} selhal (${failure.kind}): ${failure.message}")
        }
    }

    /**
     * Ruční spuštění denního přehledu hodnocení. Užitečné hlavně při onboardingu: první běh
     * nemá s čím srovnávat a je lepší to vidět hned, než druhý den v kanálu klienta.
     */
    suspend fun ratingsRun(args: Arguments) {
        val organization = organization(args)
        val app = app(organization.id, args)

        val report = components.dailyRatings.run(organization.id, app.id)
        audit(organization.id, "ratings.manual", "app", app.id.toString())

        out("Přehled hodnocení ${app.name} (${app.id})")
        // Čísla se vypisují i tehdy, když se přehled nikam neposlal: snapshoty jsou uložené
        // a při onboardingu je to jediný způsob, jak si je prohlédnout ještě před kanálem.
        report.platforms.forEach { part ->
            val average = part.average?.let { RatingsDigest.formatRating(it) } ?: "—"
            val change =
                when {
                    part.isFirstRun -> "první přehled, není s čím srovnat"
                    part.delta == null -> "—"
                    else -> "${RatingsDigest.trend(part.delta)} ${RatingsDigest.formatDelta(part.delta!!)}"
                }
            out("  ${part.platform}: průměr $average ($change), nových ${part.newTotal ?: "?"}, k ${part.asOf}")
        }
        report.skipped?.let {
            out("  neodesláno: ${describeSkip(it)}")
            return
        }
        report.deliveries.forEach { delivery ->
            val state =
                when {
                    delivery.sent -> "✓ odesláno"
                    delivery.alreadySent -> "· dnes už odešlo"
                    else -> "✗ ${delivery.error}"
                }
            out("  kanál ${delivery.channelId}: $state")
        }

        report.failures.firstOrNull()?.let { failure ->
            throw CommandException("Hodnocení ${failure.platform} selhala (${failure.kind}): ${failure.message}")
        }
    }

    /**
     * Instalační odkaz pro klienta („Add to Slack"). Odkaz nese podepsaný `state`, který váže
     * instalaci na organizaci — proto se generuje tady a ne ručním sestavením URL.
     */
    fun slackInstallUrl(args: Arguments) {
        val organization = organization(args)
        val states =
            components.slackInstallStates
                ?: throw CommandException("Chybí SLACK_SIGNING_SECRET — bez něj se instalační odkaz nedá podepsat")
        val baseUrl =
            components.publicBaseUrl
                ?: throw CommandException("Chybí PUBLIC_BASE_URL — z ní se skládá adresa, na kterou se Slack vrací")

        val state = states.issue(organization.id.toString())
        out("Instalační odkaz pro ${organization.name} (platí ${SlackInstallStates.DEFAULT_VALIDITY}):")
        out("${baseUrl.trimEnd('/')}$SLACK_INSTALL_PATH?state=$state")
    }

    /**
     * Ruční vložení bot tokenu — cesta pro self-host a pro náš vlastní workspace, kde se appka
     * nainstaluje přímo z api.slack.com („Install to Workspace") a OAuth flow není potřeba.
     * Výsledek je tentýž credential jako po instalaci odkazem, takže se pak nic nepřepisuje.
     */
    suspend fun slackConnect(args: Arguments) {
        val organization = organization(args)
        val token = SecretPayload(args.required("token"))
        if (!token.value.startsWith("xoxb-")) {
            throw UsageException("--token čeká bot token workspace (začíná xoxb-), ne app-level ani user token")
        }

        // Ověření hned při vkládání: špatný token se má poznat tady, ne až první recenzí.
        val install =
            try {
                components.slackApi.authTest(token)
            } catch (error: ChannelException) {
                throw CommandException("Slack token neuznal: ${error.message}", error)
            }
        val missing =
            REQUIRED_SLACK_SCOPES.filterNot { scope ->
                install.scopes
                    .orEmpty()
                    .split(',')
                    .contains(scope)
            }

        val meta =
            components.vault.store(
                organization.id,
                CredentialType.SLACK_INSTALL,
                args.optional("label") ?: "Slack ${install.hint()}",
                install.payload(),
                install.hint(),
            )
        audit(
            organization.id,
            "slack.installed",
            "credential",
            meta.id.toString(),
            mapOf("team" to install.teamId, "scopes" to install.scopes.orEmpty()),
        )

        out("Slack workspace připojený (token uložený zašifrovaný, ven už z vaultu nevyjde)")
        out(
            details(
                "ID" to meta.id.toString(),
                "workspace" to install.hint(),
                "bot" to (install.botUserId ?: "—"),
                "scopes" to (install.scopes ?: "neuvedeny"),
            ),
        )
        if (missing.isNotEmpty() && install.scopes != null) {
            out("Pozor: appce chybí scopes ${missing.joinToString()} — doplň je v api.slack.com a nainstaluj znovu")
        }
        out("Dál: `channel add --org ${organization.slug} --app <APP_ID> --credential ${meta.id} --slack-channel C…`")
    }

    /**
     * Připojení Teams. Na rozdíl od Slacku se tu **neukládá tajemství bota** — ten je app-level
     * a jeho heslo je v konfiguraci deploymentu; per klient se drží jen tenant a regionální
     * endpoint, tedy to, co dnes v n8n leží natvrdo v URL jednotlivých nodů.
     */
    suspend fun teamsConnect(args: Arguments) {
        val organization = organization(args)
        val bot =
            components.teamsBot
                ?: throw CommandException("Chybí TEAMS_BOT_APP_ID a TEAMS_BOT_APP_PASSWORD — bez registrace bota nemá co posílat zprávy")
        val install =
            TeamsInstall(
                tenantId = args.required("tenant"),
                serviceUrl = args.optional("service-url") ?: TeamsInstall.DEFAULT_SERVICE_URL,
                teamId = args.optional("team"),
                teamName = args.optional("team-name"),
            )

        // Ověřovací volání je na úrovni deploymentu (funguje vůbec náš bot?), ne tenantu — ten
        // se pozná až zkušební zprávou do kanálu. I tak je lepší chytit špatné heslo hned tady.
        val botError =
            try {
                components.teamsTokens.accessToken(bot)
                null
            } catch (error: ChannelException) {
                error.message
            }

        val meta =
            components.vault.store(
                organization.id,
                CredentialType.TEAMS_BOT_REF,
                args.optional("label") ?: "Teams ${install.hint()}",
                install.payload(),
                install.hint(),
            )
        audit(
            organization.id,
            "teams.connected",
            "credential",
            meta.id.toString(),
            mapOf("tenant" to install.tenantId, "team" to install.teamId.orEmpty()),
        )

        out("Teams připojené")
        out(
            details(
                "ID" to meta.id.toString(),
                "tenant" to install.tenantId,
                "endpoint" to install.serviceUrl,
                "bot" to (botError?.let { "nedostupný: $it" } ?: "odpovídá"),
            ),
        )
        out("Dál: `channel add --org ${organization.slug} --app <APP_ID> --credential ${meta.id} --teams-channel 19:…`")
    }

    /**
     * Kanál, do kterého mají recenze chodit. Typ se pozná podle toho, čím je zadaný cíl:
     * `--slack-channel C…` nebo `--teams-channel 19:…`. Jméno kanálu to není a být nesmí —
     * jméno se mění, ID ne.
     */
    fun channelAdd(args: Arguments) {
        val organization = organization(args)
        val app = app(organization.id, args)
        val meta = credential(organization.id, args)
        val slackChannel = args.optional("slack-channel")
        val teamsChannel = args.optional("teams-channel")
        val type =
            when {
                slackChannel != null && teamsChannel != null ->
                    throw UsageException("--slack-channel a --teams-channel se navzájem vylučují; kanál je jeden")

                slackChannel != null -> ChannelType.SLACK
                teamsChannel != null -> ChannelType.TEAMS
                else -> throw UsageException("Kanál potřebuje cíl: --slack-channel <C…> nebo --teams-channel <19:…>")
            }
        val expectedCredential =
            when (type) {
                ChannelType.SLACK -> CredentialType.SLACK_INSTALL
                ChannelType.TEAMS -> CredentialType.TEAMS_BOT_REF
            }
        if (meta.type != expectedCredential) {
            throw CommandException("Kanál typu ${type.name} potřebuje ${expectedCredential.name}, ne credential typu ${meta.type.name}")
        }
        val targetRef = slackChannel ?: teamsChannel!!
        when (type) {
            ChannelType.SLACK ->
                if (!targetRef.startsWith("C") && !targetRef.startsWith("G")) {
                    throw UsageException("--slack-channel čeká ID kanálu ze Slacku (začíná C nebo G), ne jeho jméno")
                }

            ChannelType.TEAMS ->
                if (!targetRef.startsWith("19:")) {
                    throw UsageException("--teams-channel čeká ID kanálu v Teams (začíná 19: a končí @thread.tacv2)")
                }
        }

        val channel =
            components.channels.create(
                organization.id,
                NewChannel(
                    appId = app.id,
                    type = type,
                    targetRef = targetRef,
                    targetLabel = args.optional("label"),
                    credentialId = meta.id,
                    locale = args.optional("locale")?.let(::locale) ?: app.locale,
                ),
            )
        audit(
            organization.id,
            "channel.created",
            "channel",
            channel.id.toString(),
            mapOf("app" to app.id.toString(), "type" to channel.type.name),
        )
        out("Kanál připojený k aplikaci ${app.name}")
        out(
            details(
                "ID" to channel.id.toString(),
                "kanál" to channel.targetRef,
                "jazyk" to channel.locale.code,
                "instalace" to (meta.hint ?: meta.label),
            ),
        )
    }

    fun channelList(args: Arguments) {
        val organization = organization(args)
        val app = app(organization.id, args)
        val channels = components.channels.listByApp(organization.id, app.id)
        if (channels.isEmpty()) {
            out("Aplikace ${app.name} zatím nemá kanál — přidej ho příkazem `channel add`")
            return
        }
        channels.forEach { channel ->
            val state = if (channel.enabled) "zapnutý" else "vypnutý"
            out(
                "${channel.id}  ${channel.type.name.padEnd(TYPE_COLUMN)}  ${channel.targetRef.padEnd(TYPE_COLUMN)}  " +
                    "${channel.locale.code}  $state",
            )
        }
    }

    /**
     * Ověření kanálu bez čekání na recenzi: pošle do něj krátkou zprávu „připojeno".
     *
     * Tohle je jediný způsob, jak po nastavení hned poznat tři nejčastější chyby — odvolaný
     * token, chybějící scope a bota, kterého nikdo nepozval do privátního kanálu. Bez něj se
     * na ně přijde až tím, že první recenze nikam nedorazí, a to je ta nejdražší chvíle.
     */
    suspend fun channelTest(args: Arguments) {
        val organization = organization(args)
        val app = app(organization.id, args)
        val all = components.channels.listByApp(organization.id, app.id)
        val wanted = args.optional("channel")
        val targets =
            when {
                wanted == null -> all
                else ->
                    all.filter { it.id.toString() == wanted || it.targetRef == wanted }.ifEmpty {
                        throw CommandException("Kanál '$wanted' u aplikace ${app.name} není — vypiš je příkazem `channel list`")
                    }
            }
        if (targets.isEmpty()) {
            throw CommandException("Aplikace ${app.name} zatím nemá kanál — přidej ho příkazem `channel add`")
        }

        var failed = 0
        targets.forEach { channel ->
            val implementation = components.notificationChannels.firstOrNull { it.type == channel.type }
            val credentialId = channel.credentialId
            when {
                implementation == null -> {
                    failed++
                    out("${channel.targetRef}  ✗ kanál typu ${channel.type.name} tenhle proces neumí")
                }

                credentialId == null -> {
                    failed++
                    out("${channel.targetRef}  ✗ chybí připojená instalace — projdi `slack connect`/`teams connect` a `channel add`")
                }

                else ->
                    try {
                        val posted =
                            implementation.postConnectivityCheck(
                                ChannelTarget(channel.targetRef, components.vault.resolve(organization.id, credentialId)),
                                ConnectivityNotice(appName = app.name, locale = channel.locale),
                            )
                        out("${channel.targetRef}  ✓ zpráva odeslaná (ts ${posted.messageId})")
                    } catch (error: ChannelException) {
                        failed++
                        out("${channel.targetRef}  ✗ ${error.kind}: ${error.message} — ${hintFor(error, channel.type)}")
                    }
            }
        }
        audit(organization.id, "channel.tested", "app", app.id.toString(), mapOf("kanálů" to targets.size.toString()))
        if (failed > 0) throw CommandException("Kanálů se nepodařilo ověřit: $failed z ${targets.size}")
    }

    /**
     * Úlohy, které se nepovedly ani po opakování. Runbooky i návod ke Slack Appce na DLQ
     * odkazují — dokud není console (F3), je tohle jediný způsob, jak se do ní podívat.
     */
    fun jobsFailed(args: Arguments) {
        val limit = args.int("limit") ?: DEFAULT_JOB_LIMIT
        val orgSlug = args.optional("org")
        val jobs =
            if (orgSlug == null) {
                components.failedJobs.listOpen(limit)
            } else {
                components.failedJobs.listOpenByOrg(organization(args).id, limit)
            }
        if (jobs.isEmpty()) {
            out("Fronta nemá žádnou neuzavřenou chybu")
            return
        }
        out("Neuzavřené chyby fronty (${jobs.size})")
        jobs.forEach { out("  " + it.summarize()) }
        out("Úloha z výpisu zmizí, jakmile stejná instance projde — u doručení ji rozjede další ingest.")
    }

    fun reviewList(args: Arguments) {
        val organization = organization(args)
        val app = app(organization.id, args)
        val limit = args.int("limit") ?: DEFAULT_REVIEW_LIMIT
        val states =
            args
                .optional("state")
                ?.split(',')
                ?.map { reviewState(it.trim()) }
                ?.toSet()
                ?: ReviewState.entries.toSet()

        val reviews = components.reviews.listByApp(organization.id, app.id, states, limit)
        if (reviews.isEmpty()) {
            out("Aplikace ${app.name} zatím nemá uložené recenze")
            return
        }
        reviews.forEach { out(it.summarize()) }
    }

    /**
     * Rotace datových klíčů (F5.6). Bez `--org` projde všechny organizace i klíč uživatelských
     * tajemství — to je varianta, kterou člověk chce po podezření na kompromitaci; s `--org`
     * jen jednu, což se hodí po odchodu člověka, který k jejím klíčům měl přístup.
     *
     * KEK se **nemění**, mění se datové klíče pod ním. Rotace KEK je operace ve správci klíčů,
     * ne tady; po ní se nic přešifrovávat nemusí, protože zabalený DEK umí rozbalit i nová verze.
     */
    fun vaultRotate(args: Arguments) {
        val slug = args.optional("org")
        val organizations = if (slug == null) components.organizations.list() else listOf(organization(args))

        var total = 0
        organizations.forEach { organization ->
            val count = components.vault.rotateDataKey(organization.id)
            total += count
            out("  ${organization.slug}: přešifrováno $count klíčů ke storům")
            components.audit.append(
                auditEntry(
                    orgId = organization.id,
                    action = "vault.rotated",
                    actorType = ActorType.SYSTEM,
                    actorLabel = "cli",
                    targetType = "org_data_key",
                    targetId = organization.id.toString(),
                    metadata = mapOf("credentials" to count.toString()),
                ),
            )
        }

        // Uživatelská tajemství visí na deploymentu, ne na organizaci — proto jen u plné rotace.
        if (slug == null) {
            val secrets = components.userSecrets
            if (secrets == null) {
                out("  druhý faktor: přeskočeno (bez VAULT_KEK_URI se nešifruje)")
            } else {
                out("  druhý faktor: přešifrováno ${secrets.rotateDataKey()} tajemství")
            }
        }
        out("Hotovo, celkem $total klíčů v ${organizations.size} organizacích")
    }

    /**
     * Ruční záloha — tímhle příkazem se dělá i drill: zálohuj, obnov vedle, porovnej.
     * Selhání končí nenulovým kódem, takže se to dá pověsit i do skriptu.
     */
    fun backupRun() {
        val run = components.backup.backupNow()
        if (run.status == BackupStatus.FAILED) {
            throw CommandException("Záloha selhala: ${run.error ?: "neznámá chyba"}")
        }
        out("Záloha hotová")
        out(
            details(
                "umístění" to (run.location ?: "—"),
                "velikost" to formatSize(run.sizeBytes),
                "sha-256" to (run.checksum ?: "—"),
                "trvání" to "${(run.finishedAt - run.startedAt).inWholeSeconds} s",
            ),
        )
    }

    fun backupList() {
        val stored = components.backup.list()
        out("Zálohy v úložišti (${stored.size})")
        if (stored.isEmpty()) {
            out("  žádné — spusť `backup run`")
        } else {
            stored.forEach { out("  " + it.summarize()) }
        }

        val history = components.backupRuns.listRecent(HISTORY_LIMIT)
        if (history.isNotEmpty()) {
            out("Poslední běhy")
            history.forEach { out("  " + it.summarize()) }
        }
    }

    /**
     * Obnova do vedlejší databáze. Do provozní se přes CLI obnovit nedá schválně — ostrá
     * obnova znamená obnovit vedle, podívat se na počty řádků a teprve pak přepnout aplikaci.
     */
    fun backupRestore(args: Arguments) {
        val key = args.required("key")
        val database = args.required("database")

        val report =
            try {
                components.backup.restore(
                    key = key,
                    databaseName = database,
                    dropExisting = args.boolean("drop-existing") ?: false,
                )
            } catch (error: BackupToolException) {
                throw CommandException("Obnova selhala: ${error.message}", error)
            } catch (error: BackupStoreException) {
                throw CommandException("Obnova selhala: ${error.message}", error)
            } catch (error: IllegalArgumentException) {
                // Pojistky služby (obnova do provozní databáze, nebezpečné jméno) — chyba obsluhy,
                // ne kódu, takže z ní musí být věta, ne stack trace.
                throw CommandException(error.message ?: "neplatné parametry obnovy", error)
            }

        out("Obnoveno do databáze ${report.database}")
        out(
            details(
                "záloha" to report.key,
                "velikost" to formatSize(report.sizeBytes),
                "otisk" to
                    when (report.checksumVerified) {
                        true -> "sedí na historii"
                        else -> "nemám s čím porovnat (záloha není v historii běhů)"
                    },
                "schéma" to (report.schemaVersion ?: "—"),
            ),
        )
        report.rowCounts.forEach { (table, count) -> out("  ${table.padEnd(DETAIL_LABEL_COLUMN)}$count") }
    }

    private fun payload(
        kind: StoreCredentialKind,
        args: Arguments,
    ): SecretPayload {
        val file = Path.of(args.required("file"))
        if (!file.exists()) throw CommandException("Soubor $file neexistuje")
        val content =
            try {
                file.readText()
            } catch (error: IOException) {
                throw CommandException("Soubor $file nejde přečíst: ${error.message}", error)
            }

        return usage {
            StoreCredentialPayloads.of(kind, content, args.optional("key-id"), args.optional("issuer-id"))
        }
    }

    /**
     * Stejný balíček dvakrát v jedné organizaci zachytává unikátní index — chytit to dřív
     * znamená větu místo výjimky z databáze.
     */
    private fun requireStoreIdentifiersFree(
        orgId: OrganizationId,
        gpPackage: String?,
        ascAppId: String?,
    ) {
        components.apps.listByOrg(orgId).forEach { existing ->
            val collision =
                when {
                    gpPackage != null && existing.gpPackageName == gpPackage -> gpPackage
                    ascAppId != null && existing.ascAppId == ascAppId -> ascAppId
                    else -> null
                }
            if (collision != null) {
                throw CommandException("Aplikace ${existing.name} (${existing.id}) už $collision v téhle organizaci má")
            }
        }
    }

    private fun organization(args: Arguments): Organization {
        val raw = args.required("org")
        return components.organizations.findBySlug(raw)
            ?: runCatching { OrganizationId.parse(raw) }.getOrNull()?.let(components.organizations::findById)
            ?: throw CommandException("Organizace '$raw' neexistuje (hledáno podle slugu i ID)")
    }

    private fun app(
        orgId: OrganizationId,
        args: Arguments,
    ): App {
        val raw = args.required("app")
        val id =
            runCatching { AppId.parse(raw) }.getOrNull()
                ?: throw UsageException("--app čeká ID aplikace (UUID), dostalo '$raw'")
        return components.apps.findById(orgId, id)
            ?: throw CommandException("Aplikace $raw v téhle organizaci neexistuje")
    }

    private fun credential(
        orgId: OrganizationId,
        args: Arguments,
    ): CredentialMeta {
        val raw = args.required("credential")
        val id =
            runCatching { CredentialId(Uuid.parse(raw)) }.getOrNull()
                ?: throw UsageException("--credential čeká ID credentialu (UUID), dostalo '$raw'")
        return components.credentials.findMeta(orgId, id)
            ?: throw CommandException("Credential $raw v téhle organizaci neexistuje")
    }

    private fun audit(
        orgId: OrganizationId,
        action: String,
        targetType: String,
        targetId: String,
        metadata: Map<String, String> = emptyMap(),
    ) = components.audit.append(
        auditEntry(
            orgId = orgId,
            action = action,
            actorType = ActorType.SYSTEM,
            actorLabel = "cli",
            targetType = targetType,
            targetId = targetId,
            metadata = metadata,
        ),
    )

    private companion object {
        /** Bez těchhle scopes se zpráva buď neodešle, nebo nepůjde přepsat po odeslání odpovědi. */
        val REQUIRED_SLACK_SCOPES = listOf("chat:write")

        const val DEFAULT_REVIEW_LIMIT = 20
        const val DEFAULT_JOB_LIMIT = 50
        const val HISTORY_LIMIT = 5
        const val SLUG_COLUMN = 24
        const val STORES_COLUMN = 15
        const val ENABLED_COLUMN = 8
        const val TYPE_COLUMN = 18
        const val STATUS_COLUMN = 7
    }
}

private fun slugify(name: String): String = Slugs.of(name)

private fun requireValidSlug(slug: String) {
    if (!Slugs.isValid(slug)) {
        throw UsageException("Slug '$slug' neprojde: ${Slugs.RULE}. Zadej vlastní přes --slug.")
    }
}

private fun locale(raw: String): MessageLocale = usage { AppInputs.locale(raw, "--locale") }

private fun timezone(raw: String): String = usage { AppInputs.timezone(raw, "--timezone") }

private fun ingestInterval(minutes: Int): Int = usage { AppInputs.ingestInterval(minutes, "--ingest-interval") }

/**
 * Watermark, od kterého se recenze notifikují. `now` je to, co se použije při onboardingu
 * existující appky: historie se doimportuje, ale kanál nezaplaví.
 */
private fun notifyFrom(
    raw: String?,
    clock: Clock,
): Instant? = usage { AppInputs.notifyFrom(raw, "--notify-from", clock) }

private fun digestAt(raw: String): LocalTime = usage { AppInputs.digestAt(raw, "--digest-at") }

/**
 * Pravidla pro hodnoty appky drží doména ([AppInputs]) — stejná pro console i CLI.
 * Tady se jen její chyba přeloží na chybu použití, kterou CLI umí vypsat jako větu.
 */
private fun <T> command(block: () -> T): T =
    try {
        block()
    } catch (error: ConsoleException) {
        throw CommandException(error.message.orEmpty(), error)
    }

private fun <T> usage(block: () -> T): T =
    try {
        block()
    } catch (error: ConsoleException) {
        throw UsageException(error.message.orEmpty(), error)
    }

private fun orgRole(raw: String): OrgRole =
    OrgRole.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        ?: throw UsageException("--role zná ${OrgRole.entries.joinToString { it.name.lowercase() }}")

private fun purpose(raw: String): CredentialPurpose =
    CredentialPurpose.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        ?: throw UsageException("--purpose zná ${CredentialPurpose.entries.joinToString { it.name.lowercase() }}")

private fun reviewState(raw: String): ReviewState =
    ReviewState.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        ?: throw UsageException("--state zná ${ReviewState.entries.joinToString { it.name.lowercase() }}")

/** Nejčastější příčiny podle druhu chyby — ať člověk nemusí hledat, co Slack tím slovem myslí. */
private fun FailedJob.summarize(): String =
    "${lastFailedAt.toString().take(TIMESTAMP_LENGTH)}  ${taskName.padEnd(TASK_COLUMN)} pokusů $attempts  " +
        "${payload ?: taskInstance}  ${errorMessage ?: errorClass ?: "—"}"

/** Důvod, proč přehled nikam nešel. Enum konstanta nikomu nic neřekne, věta ano. */
private fun describeSkip(reason: RatingsSkipReason): String =
    when (reason) {
        RatingsSkipReason.APP_NOT_FOUND -> "aplikace neexistuje"
        RatingsSkipReason.APP_DISABLED -> "aplikace je vypnutá"
        RatingsSkipReason.NO_CHANNEL -> "aplikace nemá kanál, do kterého by přehled chodil"
        RatingsSkipReason.NO_DATA -> "ze storů se nepodařilo načíst žádná hodnocení"
    }

/** Popisek se zarovnává na nejdelší v bloku, ne na pevnou šířku — jinak se delší slepí s hodnotou. */
private fun details(vararg rows: Pair<String, String>): String {
    val width = maxOf(DETAIL_LABEL_COLUMN, rows.maxOfOrNull { it.first.length + 1 } ?: 0)
    return rows.joinToString(System.lineSeparator()) { (label, value) -> "  ${label.padEnd(width)}$value" }
}

private fun PlatformIngest.describe(): String =
    when (this) {
        is PlatformIngest.Ingested ->
            "${platform.name.padEnd(PLATFORM_COLUMN)} staženo $fetched · nové $created · změněné $updated · " +
                "beze změny $unchanged · potlačené $suppressed · odpovězeno ve storu $answeredInStore"

        is PlatformIngest.Skipped -> "${platform.name.padEnd(PLATFORM_COLUMN)} přeskočeno: $reason"
        is PlatformIngest.Failed -> "${platform.name.padEnd(PLATFORM_COLUMN)} selhalo: $kind — $message"
    }

private fun Review.summarize(): String {
    val stars = "★".repeat(starRating) + "☆".repeat(MAX_STARS - starRating)
    val text = listOfNotNull(title, body).joinToString(" — ").replace(Regex("\\s+"), " ")
    val snippet = if (text.length > SNIPPET_LENGTH) text.take(SNIPPET_LENGTH - 1) + "…" else text
    return "$stars  ${platform.name.padEnd(PLATFORM_COLUMN)} ${submittedAt.toString().take(TIMESTAMP_LENGTH)}  " +
        "${state.name.padEnd(STATE_COLUMN)} ${(authorName ?: "anonym").take(AUTHOR_COLUMN).padEnd(AUTHOR_COLUMN)}  $snippet"
}

private const val MIN_INGEST_INTERVAL = 5
private const val MAX_INGEST_INTERVAL = 1440
private const val DETAIL_LABEL_COLUMN = 16
private const val PLATFORM_COLUMN = 8
private const val STATE_COLUMN = 10
private const val AUTHOR_COLUMN = 18
private const val SNIPPET_LENGTH = 60
private const val TIMESTAMP_LENGTH = 16
private const val MAX_STARS = 5
private const val TASK_COLUMN = 16

private fun StoredBackup.summarize(): String = "${key.padEnd(BACKUP_KEY_COLUMN)} ${formatSize(sizeBytes).padStart(SIZE_COLUMN)}  $createdAt"

private fun BackupRun.summarize(): String =
    "${finishedAt.toString().take(TIMESTAMP_LENGTH)}  ${status.name.padEnd(STATE_COLUMN)} " +
        "${formatSize(sizeBytes).padStart(SIZE_COLUMN)}  ${location ?: error ?: "—"}"

/** Velikosti se čtou v MB — zálohy pod megabajt jsou samy o sobě podezřelé. */
private fun formatSize(bytes: Long?): String =
    bytes?.let { String.format(java.util.Locale.US, "%.1f MB", it.toDouble() / BYTES_IN_MB) } ?: "—"

private const val BYTES_IN_MB = 1024.0 * 1024.0
private const val BACKUP_KEY_COLUMN = 44
private const val SIZE_COLUMN = 10
