package cz.matee.appreviewzz.connectors.googleplay

import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreErrorKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class GoogleServiceAccountTest :
    FunSpec({
        test("načte e-mail a klíč z reálné struktury service account JSONu") {
            val account = GoogleServiceAccount.parse(TestServiceAccount.payload())

            account.clientEmail shouldBe TestServiceAccount.CLIENT_EMAIL
            account.projectId shouldBe "islegrow"
            account.tokenUri shouldBe TestServiceAccount.TOKEN_URI
            account.privateKey.algorithm shouldBe "RSA"
        }

        test("privátní klíč se nedostane do toString") {
            val account = GoogleServiceAccount.parse(TestServiceAccount.payload())

            account.toString() shouldNotContain "PRIVATE KEY"
            account.toString() shouldContain TestServiceAccount.CLIENT_EMAIL
        }

        test("nápověda do console je client_email, ne obsah klíče") {
            GoogleServiceAccount.hint(TestServiceAccount.payload()) shouldBe TestServiceAccount.CLIENT_EMAIL
            GoogleServiceAccount.hint(SecretPayload("{}")) shouldBe null
        }

        test("místo service accountu nahraný OAuth klient se pozná hned") {
            val payload = SecretPayload("""{"type":"authorized_user","client_id":"x","client_secret":"y"}""")

            val error = shouldThrow<StoreConnectorException> { GoogleServiceAccount.parse(payload) }
            error.kind shouldBe StoreErrorKind.AUTH
            error.message shouldContain "authorized_user"
        }

        test("nesmyslný obsah nebo chybějící klíč skončí srozumitelnou chybou") {
            shouldThrow<StoreConnectorException> { GoogleServiceAccount.parse(SecretPayload("tohle není JSON")) }

            val missingKey =
                SecretPayload("""{"type":"service_account","client_email":"a@b.iam.gserviceaccount.com"}""")
            shouldThrow<StoreConnectorException> { GoogleServiceAccount.parse(missingKey) }
                .message shouldContain "private_key"

            val brokenKey =
                SecretPayload(
                    """{"type":"service_account","client_email":"a@b.iam.gserviceaccount.com",""" +
                        """"private_key":"-----BEGIN PRIVATE KEY-----!!!!-----END PRIVATE KEY-----"}""",
                )
            shouldThrow<StoreConnectorException> { GoogleServiceAccount.parse(brokenKey) }.kind shouldBe
                StoreErrorKind.AUTH
        }
    })
