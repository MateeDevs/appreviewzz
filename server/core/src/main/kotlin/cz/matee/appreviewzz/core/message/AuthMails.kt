package cz.matee.appreviewzz.core.message

import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.User
import cz.matee.appreviewzz.core.port.OutgoingMail
import kotlin.time.Duration

/**
 * Transakční e-maily console. Schválně nejsou v [MessageKey]: ten je parita s n8n katalogem
 * pro zprávy do Slacku a Teams, tohle je jiný kanál i jiný adresát.
 *
 * Prostý text bez HTML — projde všude, nikdo ho neoznačí za phishing kvůli sledovacím
 * pixelům a hlavně: v e-mailu nikdy není nic tajného kromě jednorázového odkazu.
 */
object AuthMails {
    fun emailVerification(
        user: User,
        link: String,
        locale: MessageLocale = MessageLocale.CS,
    ): OutgoingMail =
        when (locale) {
            MessageLocale.CS ->
                OutgoingMail(
                    to = user.email,
                    subject = "Potvrď svůj e-mail v appreviewzz",
                    body =
                        """
                        ${greeting(user, locale)}

                        potvrď prosím kliknutím, že tenhle e-mail patří tobě:

                        $link

                        Pokud sis účet nezakládal(a), nic nedělej — bez potvrzení se s ním nedá pracovat.
                        """.trimIndent(),
                )

            MessageLocale.EN ->
                OutgoingMail(
                    to = user.email,
                    subject = "Confirm your e-mail for appreviewzz",
                    body =
                        """
                        ${greeting(user, locale)}

                        please confirm this e-mail address belongs to you:

                        $link

                        If you did not create an account, ignore this message — the account stays unusable.
                        """.trimIndent(),
                )
        }

    fun passwordReset(
        user: User,
        link: String,
        validFor: Duration,
        locale: MessageLocale = MessageLocale.CS,
    ): OutgoingMail =
        when (locale) {
            MessageLocale.CS ->
                OutgoingMail(
                    to = user.email,
                    subject = "Obnovení hesla do appreviewzz",
                    body =
                        """
                        ${greeting(user, locale)}

                        nové heslo si nastavíš tímhle odkazem (platí ${validFor.inWholeMinutes} minut):

                        $link

                        Pokud jsi o obnovu nežádal(a), nic nedělej — heslo zůstává, jaké bylo.
                        """.trimIndent(),
                )

            MessageLocale.EN ->
                OutgoingMail(
                    to = user.email,
                    subject = "Reset your appreviewzz password",
                    body =
                        """
                        ${greeting(user, locale)}

                        set a new password using this link (valid for ${validFor.inWholeMinutes} minutes):

                        $link

                        If you did not ask for this, ignore the message — your password stays unchanged.
                        """.trimIndent(),
                )
        }

    private fun greeting(
        user: User,
        locale: MessageLocale,
    ): String {
        val name = user.displayName?.takeIf { it.isNotBlank() }
        return when (locale) {
            MessageLocale.CS -> if (name != null) "Ahoj $name," else "Dobrý den,"
            MessageLocale.EN -> if (name != null) "Hi $name," else "Hello,"
        }
    }
}
