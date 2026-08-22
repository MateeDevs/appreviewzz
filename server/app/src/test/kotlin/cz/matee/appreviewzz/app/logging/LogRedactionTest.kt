package cz.matee.appreviewzz.app.logging

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class LogRedactionTest :
    StringSpec({

        "privátní klíč z .p8 nezůstane v logu" {
            val text =
                """
                Publikace odpovědi selhala pro klíč
                -----BEGIN PRIVATE KEY-----
                MIGTAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBHkwdwIBAQQg
                bnVsbGEgc2VjcmV0YSBoaWM=
                -----END PRIVATE KEY-----
                """.trimIndent()

            val redacted = LogRedaction.redact(text)

            redacted shouldNotContain "MIGTAgEAMBMGByqGSM49"
            // Zůstává vidět, že tam klíč byl — jinak by se chyba četla hůř, ne líp.
            redacted shouldContain "BEGIN PRIVATE KEY"
        }

        "private_key ze service accountu zmizí i v JSONu" {
            val text =
                """{"type":"service_account","private_key":"-----BEGIN PRIVATE KEY-----\nabc\n-----END","client_email":"x@y.iam"}"""

            val redacted = LogRedaction.redact(text)

            redacted shouldNotContain "abc"
            // Adresa service accountu je naopak to, co při ladění potřebuješ vidět.
            redacted shouldContain "x@y.iam"
        }

        "slackový token zmizí, jeho okolí zůstane" {
            val redacted =
                LogRedaction.redact("chat.postMessage selhalo s tokenem xoxb-123456789012-abcdefghijkl (invalid_auth)")

            redacted shouldNotContain "xoxb-123456789012"
            redacted shouldContain "invalid_auth"
        }

        "JWT od Bot Connectoru zmizí" {
            val jwt = "eyJhbGciOiJSUzI1NiIsImtpZCI6IngifQ.eyJpc3MiOiJodHRwczovL2FwaS5ib3QifQ.c2lnbmF0dXJlLWhlcmU"

            LogRedaction.redact("Authorization header: Bearer $jwt") shouldNotContain "eyJhbGciOiJSUzI1"
        }

        "hlavička Authorization se začerní celá" {
            val redacted = LogRedaction.redact("""GET /api/x, headers: {Authorization: "Basic YWRtaW46aGVzbG8="}""")

            redacted shouldNotContain "YWRtaW46aGVzbG8"
        }

        "pojmenovaná tajemství v přiřazení" {
            listOf(
                "client_secret=Qx7~aB9tuvw",
                """"apiKey": "AIzaSyDdI0hCZtE6vySjMm"""",
                "password=tajne-heslo-klienta",
            ).forEach { line ->
                val redacted = LogRedaction.redact(line)

                redacted shouldContain "redacted"
                redacted shouldNotContain "tajne-heslo-klienta"
                redacted shouldNotContain "Qx7~aB9tuvw"
                redacted shouldNotContain "AIzaSyDdI0hCZtE6vySjMm"
            }
        }

        "běžná hláška se nemění" {
            val text = "Ingest aplikace 5f2a: 12 nových recenzí, 3 aktualizované"

            LogRedaction.redact(text) shouldBe text
        }

        "prázdný text projde beze změny" {
            LogRedaction.redact("") shouldBe ""
        }
    })
