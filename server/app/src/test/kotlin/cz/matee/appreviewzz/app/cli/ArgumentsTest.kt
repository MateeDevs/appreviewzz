package cz.matee.appreviewzz.app.cli

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class ArgumentsTest :
    StringSpec({

        val known = setOf("org", "name")

        "volba se dá zadat mezerou i rovnítkem" {
            val args = Arguments.parse(listOf("--org", "islegrow", "--name=Isle Grow"), known)
            args.required("org") shouldBe "islegrow"
            args.required("name") shouldBe "Isle Grow"
        }

        "volba bez hodnoty je chyba" {
            // `--org --name Isle` by jinak uložilo do --org řetězec "--name".
            shouldThrow<UsageException> { Arguments.parse(listOf("--org", "--name", "Isle"), known) }
            shouldThrow<UsageException> { Arguments.parse(listOf("--org"), known) }
        }

        "nezadaná volba je null, respektive chyba u povinné" {
            val args = Arguments.parse(listOf("--org", "islegrow"), known)
            args.optional("name") shouldBe null
            shouldThrow<UsageException> { args.required("name") }.message shouldContain "--name"
        }

        "překlep ve volbě příkaz zastaví" {
            // Bez tohohle by `app create --gp-packge …` tiše založilo appku bez Androidu.
            val error = shouldThrow<UsageException> { Arguments.parse(listOf("--nmae", "Isle"), known) }
            error.message shouldContain "--nmae"
            error.message shouldContain "--name"
        }

        "argument bez pomlček je chyba" {
            shouldThrow<UsageException> { Arguments.parse(listOf("islegrow"), known) }
        }

        "dvakrát zadaná volba je chyba" {
            shouldThrow<UsageException> { Arguments.parse(listOf("--org", "a", "--org", "b"), known) }
        }

        "prázdná hodnota se chová jako nezadaná" {
            val args = Arguments.parse(listOf("--name="), known)
            args.optional("name") shouldBe null
        }

        "číselná volba hlásí nečíselnou hodnotu" {
            val args = Arguments.parse(listOf("--name", "třicet"), known)
            shouldThrow<UsageException> { args.int("name") }.message shouldContain "celé číslo"
        }
    })
