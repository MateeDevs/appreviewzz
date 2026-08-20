package cz.matee.appreviewzz.core.message

import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.OrgRole
import cz.matee.appreviewzz.core.port.OutgoingMail
import kotlin.time.Duration

/**
 * Pozvánka do organizace. Text je schválně konkrétní — kdo zve, kam a s jakou rolí —
 * protože příjemce od nás nikdy nic nedostal a musí poznat, že to není phishing.
 */
object InvitationMails {
    fun invitation(
        to: String,
        organization: String,
        invitedBy: String?,
        role: OrgRole,
        link: String,
        validFor: Duration,
        locale: MessageLocale = MessageLocale.CS,
    ): OutgoingMail =
        when (locale) {
            MessageLocale.CS ->
                OutgoingMail(
                    to = to,
                    subject = "Pozvánka do organizace $organization v appreviewzz",
                    body =
                        """
                        Dobrý den,

                        ${invitedBy ?: "někdo z týmu"} vás zve do organizace $organization v appreviewzz —
                        nástroji, který sbírá recenze z Google Play a App Store a nechává na ně
                        odpovídat rovnou ze Slacku.

                        Role: ${roleName(role, locale)}

                        Pozvánku přijmete tímhle odkazem (platí ${validFor.inWholeDays} dní):

                        $link

                        Pokud vám to nic neříká, e-mail ignorujte — bez kliknutí se nic nestane.
                        """.trimIndent(),
                )

            MessageLocale.EN ->
                OutgoingMail(
                    to = to,
                    subject = "You are invited to $organization on appreviewzz",
                    body =
                        """
                        Hello,

                        ${invitedBy ?: "someone from the team"} invited you to $organization on appreviewzz —
                        a tool that collects Google Play and App Store reviews and lets you reply
                        to them straight from Slack.

                        Role: ${roleName(role, locale)}

                        Accept the invitation here (valid for ${validFor.inWholeDays} days):

                        $link

                        If this means nothing to you, ignore this e-mail — nothing happens without the click.
                        """.trimIndent(),
                )
        }

    private fun roleName(
        role: OrgRole,
        locale: MessageLocale,
    ): String =
        when (locale) {
            MessageLocale.CS ->
                when (role) {
                    OrgRole.OWNER -> "vlastník (spravuje organizaci i členy)"
                    OrgRole.ADMIN -> "správce (spravuje aplikace, klíče a kanály)"
                    OrgRole.MEMBER -> "člen (vidí recenze a odpovídá na ně)"
                }

            MessageLocale.EN ->
                when (role) {
                    OrgRole.OWNER -> "owner (manages the organization and its members)"
                    OrgRole.ADMIN -> "admin (manages apps, keys and channels)"
                    OrgRole.MEMBER -> "member (sees reviews and replies to them)"
                }
        }
}
