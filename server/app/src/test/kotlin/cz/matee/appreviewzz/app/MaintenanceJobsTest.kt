package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.app.cli.TestDatabase
import cz.matee.appreviewzz.core.model.OpaqueTokens
import cz.matee.appreviewzz.core.model.UserTokenPurpose
import cz.matee.appreviewzz.jobs.MaintenanceJobs
import cz.matee.appreviewzz.persistence.repository.ExposedSessionRepository
import cz.matee.appreviewzz.persistence.repository.ExposedUserRepository
import cz.matee.appreviewzz.persistence.repository.ExposedUserTokenRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.days

class MaintenanceJobsTest :
    StringSpec({

        beforeTest { TestDatabase.reset() }

        "prošlé relace a uplatněné tokeny se po odkladu smažou, čerstvé zůstanou" {
            val exposed = TestDatabase.database.exposed
            val users = ExposedUserRepository(exposed)
            val sessions = ExposedSessionRepository(exposed)
            val tokens = ExposedUserTokenRepository(exposed)
            val clock = TestClock()
            val now = clock.current
            val user = users.create("uklid@example.com", "Tester")

            val expired = OpaqueTokens.generate()
            sessions.create(
                userId = user.id,
                tokenHash = OpaqueTokens.hash(expired),
                createdAt = now - 120.days,
                expiresAt = now - 100.days,
                userAgent = null,
                clientIp = null,
            )
            val live = OpaqueTokens.generate()
            sessions.create(
                userId = user.id,
                tokenHash = OpaqueTokens.hash(live),
                createdAt = now,
                expiresAt = now + 14.days,
                userAgent = null,
                clientIp = null,
            )

            val old = OpaqueTokens.generate()
            tokens.create(user.id, UserTokenPurpose.PASSWORD_RESET, OpaqueTokens.hash(old), now - 100.days, now - 100.days)
            val fresh = OpaqueTokens.generate()
            tokens.create(user.id, UserTokenPurpose.EMAIL_VERIFICATION, OpaqueTokens.hash(fresh), now + 3.days, now)

            MaintenanceJobs(sessions, tokens, clock = clock).cleanUp()

            sessions.listActive(user.id, now) shouldHaveSize 1
            sessions.findValid(OpaqueTokens.hash(live), now).shouldNotBeNull()
            // Prošlý token se smazal, ten platný ne — jinak by úklid rušil rozdělané registrace.
            tokens.consume(UserTokenPurpose.PASSWORD_RESET, OpaqueTokens.hash(old), now).shouldBeNull()
            tokens.consume(UserTokenPurpose.EMAIL_VERIFICATION, OpaqueTokens.hash(fresh), now).shouldNotBeNull()
        }

        "co vypršelo teprve nedávno, se ještě nemaže" {
            val exposed = TestDatabase.database.exposed
            val users = ExposedUserRepository(exposed)
            val sessions = ExposedSessionRepository(exposed)
            val tokens = ExposedUserTokenRepository(exposed)
            val clock = TestClock()
            val now = clock.current
            val user = users.create("cerstve@example.com", null)

            // Vypršelo včera. Přesně tohle se při vyšetřování incidentu hledá jako první,
            // takže odklad není lenost, ale záměr.
            sessions.create(
                userId = user.id,
                tokenHash = OpaqueTokens.hash(OpaqueTokens.generate()),
                createdAt = now - 15.days,
                expiresAt = now - 1.days,
                userAgent = null,
                clientIp = null,
            )

            MaintenanceJobs(sessions, tokens, clock = clock).cleanUp()

            // Úklid ji nechal být; smaže ji až ten za měsíc.
            sessions.deleteExpired(now) shouldBe 1
        }
    })
