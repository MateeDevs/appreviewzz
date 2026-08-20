package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.core.model.CredentialMeta
import cz.matee.appreviewzz.core.model.CredentialPurpose
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.ValidationStatus
import cz.matee.appreviewzz.core.usecase.ConsoleException
import cz.matee.appreviewzz.core.usecase.ConsoleFailure
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * Nahrání klíče. `content` je obsah souboru tak, jak ho klient vybral — service account JSON
 * z Google Play, nebo `.p8` z App Store Connect. Obojí je text, takže žádný multipart:
 * prohlížeč soubor přečte a pošle ho v JSONu.
 */
@Serializable
data class AddCredentialRequest(
    /** `gp` nebo `asc`. */
    val type: String,
    val label: String,
    val content: String,
    /** Jen pro App Store Connect — klient je opisuje z konzole Applu. */
    val keyId: String? = null,
    val issuerId: String? = null,
)

@Serializable
data class RotateCredentialRequest(
    val content: String,
    val keyId: String? = null,
    val issuerId: String? = null,
)

/**
 * Co o klíči smí ven. Payload tu **není a nikdy nebude** — fingerprint pozná, že se klíč
 * změnil, `hint` je neutrální identifikátor (client_email, Key ID), podle kterého člověk
 * pozná, který klíč to je.
 */
@Serializable
data class CredentialResponse(
    val id: String,
    val type: CredentialType,
    val label: String,
    val fingerprint: String,
    val hint: String?,
    val validationStatus: ValidationStatus,
    val validationError: String?,
    val validatedAt: String?,
)

@Serializable
data class AttachCredentialRequest(
    val credentialId: String,
    val purpose: CredentialPurpose = CredentialPurpose.REVIEWS,
)

@Serializable
data class ValidationResponse(
    val valid: Boolean,
    val message: String? = null,
)

/**
 * Klíče ke storům (F3.4). Zakládat, rotovat a ověřovat je smí ADMIN; ostatní vidí jen to,
 * že klíč existuje a jestli funguje — což je přesně to, co potřebují k diagnostice.
 */
fun Route.credentialRoutes(console: ConsoleWiring) {
    val credentials = console.credentials

    route("/orgs/{org}/credentials") {
        get {
            val context = call.orgContext(console.organizations, console.memberships)
            call.respond(io { credentials.list(context.organization.id).map { it.toResponse() } })
        }

        post {
            val context = call.orgContext(console.organizations, console.memberships)
            val request = call.receive<AddCredentialRequest>()
            val kind = StoreCredentialKind.of(request.type)
            val payload = StoreCredentialPayloads.of(kind, request.content, request.keyId, request.issuerId)

            val meta =
                io {
                    credentials.add(
                        organization = context.organization,
                        actor = context.actor,
                        type = kind.type,
                        label = request.label,
                        payload = payload,
                        hint = kind.describe(payload),
                    )
                }
            call.respond(HttpStatusCode.Created, meta.toResponse())
        }

        put("/{credential}") {
            val context = call.orgContext(console.organizations, console.memberships)
            val request = call.receive<RotateCredentialRequest>()
            val id = call.credentialIdParam()
            val current = io { credentials.get(context.organization.id, id) }
            val kind = StoreCredentialKind.of(current.type)
            val payload = StoreCredentialPayloads.of(kind, request.content, request.keyId, request.issuerId)

            val meta =
                io {
                    credentials.rotate(
                        organization = context.organization,
                        actor = context.actor,
                        id = id,
                        payload = payload,
                        hint = kind.describe(payload),
                    )
                }
            call.respond(meta.toResponse())
        }

        delete("/{credential}") {
            val context = call.orgContext(console.organizations, console.memberships)
            io { credentials.delete(context.organization, context.actor, call.credentialIdParam()) }
            call.respond(HttpStatusCode.NoContent)
        }
    }

    route("/orgs/{org}/apps/{app}/credentials") {
        post {
            val context = call.orgContext(console.organizations, console.memberships)
            val request = call.receive<AttachCredentialRequest>()
            io {
                credentials.attach(
                    organization = context.organization,
                    actor = context.actor,
                    appId = call.appIdParam(),
                    credentialId = credentialIdOf(request.credentialId),
                    purpose = request.purpose,
                )
            }
            call.respond(HttpStatusCode.NoContent)
        }

        delete("/{credential}") {
            val context = call.orgContext(console.organizations, console.memberships)
            val purpose =
                call.request.queryParameters["purpose"]?.let { raw ->
                    CredentialPurpose.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                        ?: throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Neznámé použití '$raw'")
                } ?: CredentialPurpose.REVIEWS
            io {
                credentials.detach(
                    organization = context.organization,
                    actor = context.actor,
                    appId = call.appIdParam(),
                    credentialId = call.credentialIdParam(),
                    purpose = purpose,
                )
            }
            call.respond(HttpStatusCode.NoContent)
        }

        /**
         * Ověření proti storu. Neplatný klíč **není chyba requestu**: klient má dostat
         * srozumitelné „nemá oprávnění", ne pětistovku — proto 200 s `valid: false`.
         */
        post("/{credential}/validate") {
            val context = call.orgContext(console.organizations, console.memberships)
            val outcome =
                credentials.validate(
                    organization = context.organization,
                    actor = context.actor,
                    appId = call.appIdParam(),
                    credentialId = call.credentialIdParam(),
                )
            call.respond(ValidationResponse(outcome.valid, outcome.message))
        }
    }
}

private fun CredentialMeta.toResponse() =
    CredentialResponse(
        id = id.toString(),
        type = type,
        label = label,
        fingerprint = fingerprint,
        hint = hint,
        validationStatus = validationStatus,
        validationError = validationError,
        validatedAt = validatedAt?.toString(),
    )
