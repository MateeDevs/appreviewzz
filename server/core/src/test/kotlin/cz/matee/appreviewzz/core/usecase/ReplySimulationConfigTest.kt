package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.PlatformSettings
import cz.matee.appreviewzz.core.model.UserId
import cz.matee.appreviewzz.core.port.PlatformSettingRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

private class SimulationSettings : PlatformSettingRepository {
    val values = mutableMapOf<String, String>()

    override fun all(): Map<String, String> = values.toMap()

    override fun upsert(
        key: String,
        value: String,
        actor: UserId?,
        at: Instant,
    ) {
        values[key] = value
    }

    override fun delete(key: String): Boolean = values.remove(key) != null
}

class ReplySimulationConfigTest :
    FunSpec({
        test("staging bez nastavení chrání store a změna obchází TTL i v jiném procesu") {
            val settings = SimulationSettings()
            val api = PlatformConfig(settings, replySimulationAvailable = true)
            val worker = PlatformConfig(settings, replySimulationAvailable = true)
            api.resolveAll()
            worker.resolveAll()
            worker.simulateReplies() shouldBe true
            settings.values[PlatformSettings.REPLY_SIMULATION] = "false"
            api.simulateReplies() shouldBe false
            worker.simulateReplies() shouldBe false
            settings.values[PlatformSettings.REPLY_SIMULATION] = "true"
            worker.simulateReplies() shouldBe true
            settings.values[PlatformSettings.REPLY_SIMULATION] = "poškozená hodnota"
            worker.simulateReplies() shouldBe true
        }

        test("produkce ignoruje uložený testovací režim i po převzetí stagingové databáze") {
            val settings = SimulationSettings()
            settings.values[PlatformSettings.REPLY_SIMULATION] = "true"
            val production = PlatformConfig(settings)
            production.simulateReplies() shouldBe false
            production.resolveAll().any { it.definition.key == PlatformSettings.REPLY_SIMULATION } shouldBe false
        }
    })
