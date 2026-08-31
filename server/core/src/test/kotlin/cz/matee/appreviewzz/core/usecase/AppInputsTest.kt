package cz.matee.appreviewzz.core.usecase

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Klient přidává appku tak, že vloží odkaz, který má zrovna v prohlížeči. Package name ani
 * číselné App ID nezná — a kdyby je měl opisovat, spletl by se právě v tom jednom znaku,
 * po kterém se pak měsíc nestahují recenze.
 */
class AppInputsTest :
    FunSpec({

        test("package name se vytáhne z odkazu na Play, ať je za ním cokoli") {
            AppInputs.playPackage(
                "https://play.google.com/store/apps/details?id=cz.matee.islegrow&hl=cs&gl=CZ",
                "gpPackageName",
            ) shouldBe "cz.matee.islegrow"
            AppInputs.playPackage("market://details?id=cz.matee.islegrow", "gpPackageName") shouldBe "cz.matee.islegrow"
        }

        test("holé package name projde beze změny") {
            AppInputs.playPackage("  cz.matee.islegrow ", "gpPackageName") shouldBe "cz.matee.islegrow"
        }

        test("odkaz na App Store místo Play skončí větou, ne uloženým nesmyslem") {
            shouldThrow<ConsoleException> {
                AppInputs.playPackage("https://apps.apple.com/cz/app/islegrow/id1490577875", "gpPackageName")
            }.message shouldContain "odkaz na Google Play"
        }

        test("App ID se vytáhne z odkazu i z tvaru id<číslo>") {
            AppInputs.appStoreId("https://apps.apple.com/cz/app/islegrow/id1490577875?l=cs", "ascAppId") shouldBe "1490577875"
            AppInputs.appStoreId("https://itunes.apple.com/us/app/islegrow/id1490577875?mt=8", "ascAppId") shouldBe "1490577875"
            AppInputs.appStoreId("id1490577875", "ascAppId") shouldBe "1490577875"
            AppInputs.appStoreId("1490577875", "ascAppId") shouldBe "1490577875"
        }

        test("název appky místo ID se odmítne") {
            shouldThrow<ConsoleException> { AppInputs.appStoreId("IsleGrow", "ascAppId") }
                .message shouldContain "odkaz na App Store"
        }
    })
