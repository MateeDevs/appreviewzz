package cz.matee.appreviewzz.ai

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith

class ReplyLengthTest :
    FunSpec({
        test("kratší text nechá být, jen ořízne bílé znaky") {
            clampToLimit("  Díky za zpětnou vazbu.  ", 350) shouldBe "Díky za zpětnou vazbu."
        }

        test("delší text řeže na konci věty") {
            val text = "Mrzí nás to. Chybu už opravujeme. Napiš nám prosím na podporu, ať to doladíme."

            val clamped = clampToLimit(text, 40)

            clamped shouldBe "Mrzí nás to. Chybu už opravujeme."
            clamped.length shouldBeLessThanOrEqual 40
        }

        test("jedno dlouhé souvětí se ořízne na slovo a naznačí pokračování") {
            val text = "Děkujeme za podrobný popis problému s přihlášením, který jsme právě reprodukovali"

            val clamped = clampToLimit(text, 30)

            clamped shouldEndWith "…"
            clamped.length shouldBeLessThanOrEqual 30
            // Řez nesmí spadnout doprostřed slova.
            clamped shouldBe "Děkujeme za podrobný popis…"
        }

        test("limit platí i pro text bez mezer") {
            clampToLimit("a".repeat(100), 10).length shouldBeLessThanOrEqual 10
        }
    })
