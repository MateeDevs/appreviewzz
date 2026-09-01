package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.CredentialPurpose
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.ValidationStatus
import cz.matee.appreviewzz.core.port.ChannelRepository
import cz.matee.appreviewzz.core.port.CredentialRepository

/** Co appce chybí, aby recenze doopravdy tekly. Obojí zadává klient sám, po přidání appky. */
enum class SetupGap {
    /** Aspoň jeden sledovaný store nemá klíč — není čím recenze stáhnout. */
    STORE_KEY,

    /**
     * Klíč je připojený, ale zatím neprošel ověřením — typicky pozvánka service accountu,
     * na jejíž propsání v Play Console se čeká. Je to **jiný stav než chybějící klíč**:
     * klient udělal, co měl, a čekat se dá jen na Google, ne na něj.
     */
    STORE_KEY_WAITING,

    /** Ani jeden zapnutý kanál — recenze nemají kam přijít. */
    CHANNEL,
}

/**
 * Stav nastavení jedné appky.
 *
 * Appka vzniká z odkazu na store a od té chvíle je `enabled`, jenže sama o sobě nedělá nic:
 * bez klíče se nemá čím do storu přihlásit a bez kanálu nemá komu psát. Console proto
 * potřebuje rozlišit „sledujeme" od „čeká na nastavení" — jinak klient čeká na zprávy,
 * které nemají odkud přijít, a vypadá to jako porucha.
 *
 * Připojený klíč nestačí: **musí být ověřený**. Nahraný, ale nefungující klíč vypadal do teď
 * jako hotovo a klient pak čekal na recenze, které neměly odkud přijít — přesně ta porucha,
 * které se má tenhle stav vyhýbat.
 */
data class AppSetup(
    val gaps: List<SetupGap>,
    /** Které storu chybí klíč — abychom uměli říct který, ne jen „nějaký". */
    val platformsWithoutKey: List<Platform>,
    /** Které store má klíč, který ještě neprošel ověřením. */
    val platformsWaitingForKey: List<Platform> = emptyList(),
) {
    val ready: Boolean get() = gaps.isEmpty()
}

/**
 * Čtení stavu nastavení. Ptá se přesně na to, na co se při ingestu ptá [IngestReviews] a při
 * doručení [ChannelService] — kdyby se odpovědi rozešly, console by tvrdila, že je hotovo,
 * a recenze by stejně nikam nešly.
 */
class AppSetupCheck(
    private val credentials: CredentialRepository,
    private val channels: ChannelRepository,
) {
    fun of(app: App): AppSetup {
        val keys =
            app.platforms().associateWith { platform ->
                credentials.findForApp(app.orgId, app.id, CredentialPurpose.REVIEWS, credentialType(platform))
            }
        val withoutKey = keys.filterValues { it == null }.keys.toList()
        val waiting =
            keys
                .filterValues { it != null && it.validationStatus != ValidationStatus.VALID }
                .keys
                .toList()
        // Vypnutý kanál se nepočítá: recenze do něj nejdou stejně jako do neexistujícího.
        val delivers = channels.listByApp(app.orgId, app.id).any { it.enabled }
        return AppSetup(
            gaps =
                buildList {
                    if (withoutKey.isNotEmpty()) add(SetupGap.STORE_KEY)
                    if (waiting.isNotEmpty()) add(SetupGap.STORE_KEY_WAITING)
                    if (!delivers) add(SetupGap.CHANNEL)
                },
            platformsWithoutKey = withoutKey,
            platformsWaitingForKey = waiting,
        )
    }

    private fun credentialType(platform: Platform): CredentialType =
        when (platform) {
            Platform.ANDROID -> CredentialType.GP_SERVICE_ACCOUNT
            Platform.IOS -> CredentialType.ASC_API_KEY
        }
}
