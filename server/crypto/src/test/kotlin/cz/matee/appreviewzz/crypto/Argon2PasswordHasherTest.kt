package cz.matee.appreviewzz.crypto

import cz.matee.appreviewzz.core.model.SecretPayload
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith

/** Nízké parametry: test má ověřit formát a chování, ne čekat na 19 MiB práce u každého případu. */
private val hasher = Argon2PasswordHasher(memoryKib = 256, iterations = 1, parallelism = 1)

class Argon2PasswordHasherTest :
    StringSpec({
        val password = SecretPayload("naprosto-tajne-heslo")

        "hash ověří vlastní heslo a odmítne cizí" {
            val hash = hasher.hash(password)
            hasher.verify(password, hash) shouldBe true
            hasher.verify(SecretPayload("naprosto-tajne-hesla"), hash) shouldBe false
        }

        "dvě zahašování téhož hesla se liší solí" {
            hasher.hash(password) shouldNotBe hasher.hash(password)
        }

        "zápis je standardní PHC řetězec s parametry" {
            hasher.hash(password) shouldStartWith "\$argon2id\$v=19\$m=256,t=1,p=1\$"
        }

        "ověření čte parametry ze zápisu, ne z konfigurace" {
            // Kdyby se ověřovalo se současnými parametry, po jejich zvýšení by se nikdo nepřihlásil.
            val old = Argon2PasswordHasher(memoryKib = 512, iterations = 2, parallelism = 1).hash(password)
            hasher.verify(password, old) shouldBe true
        }

        "poškozený nebo cizí zápis znamená 'nesedí', ne výjimku" {
            listOf(
                "",
                "nesmysl",
                "\$argon2i\$v=19\$m=256,t=1,p=1\$c29sc29sc29sc29s\$aGFzaA",
                "\$argon2id\$v=16\$m=256,t=1,p=1\$c29sc29sc29sc29s\$aGFzaA",
                "\$argon2id\$v=19\$m=abc,t=1,p=1\$c29sc29sc29sc29s\$aGFzaA",
                "\$argon2id\$v=19\$m=256,t=1,p=1\$@@@\$aGFzaA",
            ).forEach { hasher.verify(password, it) shouldBe false }
        }

        "prázdné heslo se dá zahašovat — délku hlídá use-case, ne hasher" {
            val hash = hasher.hash(SecretPayload(""))
            hasher.verify(SecretPayload(""), hash) shouldBe true
        }
    })
