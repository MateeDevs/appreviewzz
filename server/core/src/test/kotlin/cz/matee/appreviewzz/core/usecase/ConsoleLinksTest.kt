package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.SecretPayload
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private val TOKEN = SecretPayload("abc123")

/**
 * Odkaz v e-mailu je jediné místo, kde se doména vybírá za člověka — a zároveň místo, kde
 * špatná volba pošle adresáta do cizího prostředí (nebo útočníkovi). Proto se tu ověřuje
 * obojí: že se odkaz drží domény, na které se to stalo, i že cizí `Host` neprojde.
 */
class ConsoleLinksTest :
    FunSpec({

        test("odkaz vede na doménu, na které požadavek přišel") {
            val links = ConsoleLinks("https://appreviewzz.com", setOf("*.appreviewzz.com"))

            links.invitation(TOKEN, "https://staging.appreviewzz.com") shouldBe
                "https://staging.appreviewzz.com/pozvanka?token=abc123"
        }

        test("doména z baseUrl je povolená, aniž by se psala podruhé") {
            val links = ConsoleLinks("https://appreviewzz.com")

            links.emailVerification(TOKEN, "https://appreviewzz.com") shouldBe
                "https://appreviewzz.com/overeni?token=abc123"
        }

        test("cizí hostitel z požadavku se zahodí — jinak by odkaz na reset vedl útočníkovi") {
            val links = ConsoleLinks("https://appreviewzz.com")

            links.passwordReset(TOKEN, "https://utocnik.example") shouldBe
                "https://appreviewzz.com/obnova-hesla?token=abc123"
        }

        test("bez požadavku (CLI, worker) platí nakonfigurovaná adresa") {
            ConsoleLinks("https://appreviewzz.com/").invitation(TOKEN) shouldBe
                "https://appreviewzz.com/pozvanka?token=abc123"
        }

        test("nesmyslný origin se ignoruje stejně jako cizí") {
            val links = ConsoleLinks("https://appreviewzz.com")

            links.base("https://appreviewzz.com/../evil") shouldBe "https://appreviewzz.com"
            links.base("javascript:alert(1)") shouldBe "https://appreviewzz.com"
            links.base("https://appreviewzz.com\nX-Injected: 1") shouldBe "https://appreviewzz.com"
        }

        test("bez konfigurace se věří požadavku — localhost natvrdo nefunguje nikomu") {
            val links = ConsoleLinks()

            links.base("http://staging.appreviewzz.com") shouldBe "http://staging.appreviewzz.com"
            links.base(null) shouldBe "http://localhost:8080"
        }

        test("port se porovnává jen tam, kde ho vzor uvádí") {
            // S portem ve vzoru sedí jen tentýž port…
            ConsoleLinks("http://localhost:5173").base("http://localhost:8080") shouldBe "http://localhost:5173"
            // …bez něj projde konzole na libovolném portu, což chce vývoj přes vite proxy.
            ConsoleLinks("http://localhost:5173", setOf("localhost")).base("http://localhost:8080") shouldBe
                "http://localhost:8080"
        }
    })
