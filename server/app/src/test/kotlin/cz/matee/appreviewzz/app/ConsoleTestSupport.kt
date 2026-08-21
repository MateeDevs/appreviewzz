package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.app.cli.TestDatabase
import cz.matee.appreviewzz.core.message.RatingsDigest
import cz.matee.appreviewzz.core.message.ReviewNotification
import cz.matee.appreviewzz.core.model.ChannelType
import cz.matee.appreviewzz.core.model.ObservedReview
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.port.ChannelException
import cz.matee.appreviewzz.core.port.ChannelTarget
import cz.matee.appreviewzz.core.port.ConnectivityNotice
import cz.matee.appreviewzz.core.port.Mailer
import cz.matee.appreviewzz.core.port.NotificationChannel
import cz.matee.appreviewzz.core.port.OutgoingMail
import cz.matee.appreviewzz.core.port.PostedMessage
import cz.matee.appreviewzz.core.port.ReplyRendering
import cz.matee.appreviewzz.core.port.ReviewSource
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreContext
import cz.matee.appreviewzz.core.port.ValidationOutcome
import cz.matee.appreviewzz.core.usecase.AppService
import cz.matee.appreviewzz.core.usecase.AuthPolicy
import cz.matee.appreviewzz.core.usecase.AuthenticationService
import cz.matee.appreviewzz.core.usecase.ChannelService
import cz.matee.appreviewzz.core.usecase.ConsoleLinks
import cz.matee.appreviewzz.core.usecase.CredentialService
import cz.matee.appreviewzz.core.usecase.OrganizationService
import cz.matee.appreviewzz.core.usecase.ReviewInbox
import cz.matee.appreviewzz.crypto.Argon2PasswordHasher
import cz.matee.appreviewzz.crypto.CredentialVault
import cz.matee.appreviewzz.crypto.KekProviders
import cz.matee.appreviewzz.persistence.repository.ExposedAppRepository
import cz.matee.appreviewzz.persistence.repository.ExposedAuditLogRepository
import cz.matee.appreviewzz.persistence.repository.ExposedChannelRepository
import cz.matee.appreviewzz.persistence.repository.ExposedCredentialRepository
import cz.matee.appreviewzz.persistence.repository.ExposedDataKeyRepository
import cz.matee.appreviewzz.persistence.repository.ExposedFailedJobRepository
import cz.matee.appreviewzz.persistence.repository.ExposedInvitationRepository
import cz.matee.appreviewzz.persistence.repository.ExposedMembershipRepository
import cz.matee.appreviewzz.persistence.repository.ExposedOrganizationRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReplyRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReviewMessageRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReviewRepository
import cz.matee.appreviewzz.persistence.repository.ExposedSessionRepository
import cz.matee.appreviewzz.persistence.repository.ExposedUserRepository
import cz.matee.appreviewzz.persistence.repository.ExposedUserTokenRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.nio.file.Files

/**
 * Console nad opravdovým Postgresem. Session, role i pozvánky stojí na tom, co se doopravdy
 * zapíše do databáze — s falešnými repozitáři by testy ověřovaly samy sebe.
 */
const val CONSOLE_URL = "https://console.test"

/** Zachytává, co by šlo e-mailem — jednorázový odkaz jinak z aplikace nevyleze. */
class RecordingMailer : Mailer {
    val sent = mutableListOf<OutgoingMail>()

    override fun send(mail: OutgoingMail) {
        sent += mail
    }

    fun lastToken(): String = tokenOf(sent.last())

    fun tokenOf(mail: OutgoingMail): String =
        checkNotNull(Regex("""token=([A-Za-z0-9_-]+)""").find(mail.body)) { "V e-mailu není odkaz: ${mail.body}" }
            .groupValues[1]

    fun lastTo(email: String): OutgoingMail = sent.last { it.to == email }
}

/**
 * Konektory a kanály, které v testu nesahají na síť. Chování se přepíná zvenčí, takže
 * jde ověřit i to, co se v reálném storu vyrábí špatně — odvolaný token, chybějící scope.
 */
class FakeReviewSource(
    override val platform: Platform,
) : ReviewSource {
    var outcome: ValidationOutcome = ValidationOutcome(valid = true)
    var failWith: StoreConnectorException? = null
    val validated = mutableListOf<StoreContext>()

    override suspend fun fetchReviews(context: StoreContext): List<ObservedReview> = emptyList()

    override suspend fun validate(context: StoreContext): ValidationOutcome {
        validated += context
        failWith?.let { throw it }
        return outcome
    }
}

class FakeNotificationChannel(
    override val type: ChannelType = ChannelType.SLACK,
) : NotificationChannel {
    var failWith: ChannelException? = null
    val notices = mutableListOf<ConnectivityNotice>()

    override suspend fun postReview(
        target: ChannelTarget,
        notification: ReviewNotification,
    ): PostedMessage = PostedMessage(target.conversationId, "1755600000.000100")

    override suspend fun markReplied(
        target: ChannelTarget,
        message: PostedMessage,
        rendering: ReplyRendering,
    ) = Unit

    override suspend fun postConnectivityCheck(
        target: ChannelTarget,
        notice: ConnectivityNotice,
    ): PostedMessage {
        notices += notice
        failWith?.let { throw it }
        return PostedMessage(target.conversationId, "1755600000.000200")
    }

    override suspend fun postRatingsDigest(
        target: ChannelTarget,
        digest: RatingsDigest,
    ): PostedMessage = PostedMessage(target.conversationId, "1755600000.000300")

    override suspend fun reportFailure(
        target: ChannelTarget,
        message: PostedMessage,
        notification: ReviewNotification,
        error: String,
    ) = Unit
}

/**
 * Falešné části světa, na které si test potřebuje sáhnout po sestavení modulu.
 * Vault je opravdový, jen s lokálním keysetem v dočasném souboru — šifrování se
 * v testech neobchází, jinak by se AAD binding neověřoval nikdy.
 */
class ConsoleFakes(
    val googlePlay: FakeReviewSource,
    val appStore: FakeReviewSource,
    val slack: FakeNotificationChannel,
)

/** Fronta odpovědí bez plánovače: test si přečte, co by se publikovalo. */
class RecordingReplyQueue : (ConsoleReply) -> Boolean {
    val queued = mutableListOf<ConsoleReply>()

    override fun invoke(reply: ConsoleReply): Boolean {
        // Druhá tatáž odpověď se nezařazuje, stejně jako v opravdové frontě.
        val duplicate = queued.any { it.reviewId == reply.reviewId && it.body == reply.body }
        queued += reply
        return !duplicate
    }
}

fun ApplicationTestBuilder.consoleModule(
    mailer: RecordingMailer,
    policy: AuthPolicy = AuthPolicy(),
    slack: ConsoleSlack? = null,
    replyQueue: RecordingReplyQueue? = null,
    fakes: ConsoleFakes =
        ConsoleFakes(FakeReviewSource(Platform.ANDROID), FakeReviewSource(Platform.IOS), FakeNotificationChannel()),
) {
    val exposed = TestDatabase.database.exposed
    val organizations = ExposedOrganizationRepository(exposed)
    val memberships = ExposedMembershipRepository(exposed)
    val users = ExposedUserRepository(exposed)
    val auth =
        AuthenticationService(
            users = users,
            sessions = ExposedSessionRepository(exposed),
            tokens = ExposedUserTokenRepository(exposed),
            // Levné parametry: testy ověřují smyčku přihlášení, ne odolnost argon2.
            hasher = Argon2PasswordHasher(memoryKib = 256, iterations = 1, parallelism = 1),
            mailer = mailer,
            links = ConsoleLinks(CONSOLE_URL),
            policy = policy,
        )
    val appRepository = ExposedAppRepository(exposed)
    val credentialRepository = ExposedCredentialRepository(exposed)
    val channelRepository = ExposedChannelRepository(exposed)
    val audit = ExposedAuditLogRepository(exposed)
    val appService = AppService(apps = appRepository, audit = audit)
    val vault = consoleVault()
    val credentialService =
        CredentialService(
            credentials = credentialRepository,
            apps = appRepository,
            channels = channelRepository,
            vault = vault,
            sources = listOf(fakes.googlePlay, fakes.appStore),
            audit = audit,
        )
    val reviewInbox =
        ReviewInbox(
            reviews = ExposedReviewRepository(exposed),
            messages = ExposedReviewMessageRepository(exposed),
            replies = ExposedReplyRepository(exposed),
            apps = appRepository,
            channels = channelRepository,
            credentials = credentialRepository,
            failedJobs = ExposedFailedJobRepository(exposed),
            audit = audit,
        )
    val channelService =
        ChannelService(
            channels = channelRepository,
            apps = appRepository,
            credentials = credentialRepository,
            secrets = vault,
            implementations = listOf(fakes.slack),
            audit = audit,
        )
    val orgs =
        OrganizationService(
            organizations = organizations,
            memberships = memberships,
            users = users,
            invitations = ExposedInvitationRepository(exposed),
            audit = audit,
            mailer = mailer,
            links = ConsoleLinks(CONSOLE_URL),
        )

    application {
        apiModule(
            database = TestDatabase.database,
            metrics = PrometheusMeterRegistry(PrometheusConfig.DEFAULT),
            console =
                ConsoleWiring(
                    auth = auth,
                    orgs = orgs,
                    apps = appService,
                    credentials = credentialService,
                    channels = channelService,
                    cookies = SessionCookies(secure = false, lifetime = policy.sessionLifetime),
                    organizations = organizations,
                    memberships = memberships,
                    slack = slack,
                    reviews = reviewInbox,
                    audit = audit,
                    enqueueReply = replyQueue,
                ),
        )
    }
}

/**
 * Vault nad testovací databází s lokálním keysetem v dočasném souboru. Šifrování se
 * v testech neobchází — jinak by se AAD binding neověřoval nikdy — a testy si přes něj
 * umí založit i credential, který jinak vzniká připojením Slacku.
 */
private val vault: CredentialVault by lazy {
    val keyset = Files.createTempDirectory("appreviewzz-keyset").resolve("keyset")
    CredentialVault(
        dataKeys = ExposedDataKeyRepository(TestDatabase.database.exposed),
        credentials = ExposedCredentialRepository(TestDatabase.database.exposed),
        kek = KekProviders.fromUri("local://$keyset"),
    )
}

fun consoleVault(): CredentialVault = vault

/** Klient, který si drží cookies jako prohlížeč — bez toho není co testovat. */
fun ApplicationTestBuilder.browser(): HttpClient = createClient { install(HttpCookies) }

suspend fun HttpClient.csrf(): String {
    val response = get("/api/auth/csrf")
    check(response.status == HttpStatusCode.OK) { "CSRF token se nevydal: ${response.status}" }
    return Regex(""""token":"([^"]+)"""").find(response.bodyAsText())!!.groupValues[1]
}

suspend fun HttpClient.postJson(
    path: String,
    body: String,
    csrf: String? = null,
): HttpResponse =
    post(path) {
        contentType(ContentType.Application.Json)
        header(CSRF_HEADER, csrf ?: csrf())
        setBody(body)
    }

suspend fun HttpClient.putJson(
    path: String,
    body: String,
): HttpResponse =
    put(path) {
        contentType(ContentType.Application.Json)
        header(CSRF_HEADER, csrf())
        setBody(body)
    }

suspend fun HttpClient.patchJson(
    path: String,
    body: String,
): HttpResponse =
    patch(path) {
        contentType(ContentType.Application.Json)
        header(CSRF_HEADER, csrf())
        setBody(body)
    }

suspend fun HttpClient.deleteSigned(path: String): HttpResponse = delete(path) { header(CSRF_HEADER, csrf()) }

/** Registrace + přihlášení jedním krokem; většina testů začíná právě tímhle. */
suspend fun HttpClient.signUp(
    email: String,
    password: String = "dostatecne-dlouhe-heslo",
    displayName: String = "Tester",
): HttpResponse {
    postJson("/api/auth/register", """{"email":"$email","password":"$password","displayName":"$displayName"}""")
    return postJson("/api/auth/login", """{"email":"$email","password":"$password"}""")
}

/**
 * Pozvaný kolega: registrace a přijetí pozvánky.
 *
 * Token se čte **před** registrací schválně — registrace pošle na tutéž adresu ověřovací
 * e-mail a `lastTo` by pak vrátila jeho, ne pozvánku.
 */
suspend fun ApplicationTestBuilder.joinViaInvitation(
    mailer: RecordingMailer,
    email: String,
): HttpClient {
    val token = mailer.tokenOf(mailer.lastTo(email))
    val client = browser()
    client.signUp(email)
    val accepted = client.postJson("/api/invitations/accept", """{"token":"$token"}""")
    check(accepted.status == HttpStatusCode.OK) { "Pozvánku se nepodařilo přijmout: ${accepted.status}" }
    return client
}

/** Registrace, potvrzení e-mailu a přihlášení — pro testy, které rovnou zakládají organizaci. */
suspend fun HttpClient.signUpVerified(
    email: String,
    mailer: RecordingMailer,
    password: String = "dostatecne-dlouhe-heslo",
    displayName: String = "Tester",
): HttpResponse {
    postJson("/api/auth/register", """{"email":"$email","password":"$password","displayName":"$displayName"}""")
    postJson("/api/auth/email/verify", """{"token":"${mailer.tokenOf(mailer.lastTo(email))}"}""")
    return postJson("/api/auth/login", """{"email":"$email","password":"$password"}""")
}
