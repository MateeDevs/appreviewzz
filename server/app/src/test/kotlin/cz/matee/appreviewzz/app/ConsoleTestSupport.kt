package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.app.cli.TestDatabase
import cz.matee.appreviewzz.core.port.Mailer
import cz.matee.appreviewzz.core.port.OutgoingMail
import cz.matee.appreviewzz.core.usecase.AppService
import cz.matee.appreviewzz.core.usecase.AuthPolicy
import cz.matee.appreviewzz.core.usecase.AuthenticationService
import cz.matee.appreviewzz.core.usecase.ConsoleLinks
import cz.matee.appreviewzz.core.usecase.OrganizationService
import cz.matee.appreviewzz.crypto.Argon2PasswordHasher
import cz.matee.appreviewzz.persistence.repository.ExposedAppRepository
import cz.matee.appreviewzz.persistence.repository.ExposedAuditLogRepository
import cz.matee.appreviewzz.persistence.repository.ExposedInvitationRepository
import cz.matee.appreviewzz.persistence.repository.ExposedMembershipRepository
import cz.matee.appreviewzz.persistence.repository.ExposedOrganizationRepository
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
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

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

fun ApplicationTestBuilder.consoleModule(
    mailer: RecordingMailer,
    policy: AuthPolicy = AuthPolicy(),
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
    val appService = AppService(apps = ExposedAppRepository(exposed), audit = ExposedAuditLogRepository(exposed))
    val orgs =
        OrganizationService(
            organizations = organizations,
            memberships = memberships,
            users = users,
            invitations = ExposedInvitationRepository(exposed),
            audit = ExposedAuditLogRepository(exposed),
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
                    cookies = SessionCookies(secure = false, lifetime = policy.sessionLifetime),
                    organizations = organizations,
                    memberships = memberships,
                ),
        )
    }
}

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
