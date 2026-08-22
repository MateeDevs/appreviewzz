package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.core.port.Mailer
import cz.matee.appreviewzz.core.port.OutgoingMail
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Náhradní odesílatel, dokud není nastavené SMTP: e-mail se vypíše do logu.
 *
 * Není to jen vývojářská pomůcka — self-host bez poštovního serveru se s ním dá plnohodnotně
 * projít, jen si odkaz musí správce vyzobat z logu. Proto ta hláška říká i **proč** je tam.
 *
 * Odkaz v těle je jednorázový token: **kdo vidí log, umí změnit cizí heslo.** Proto se tělo
 * vypisuje jen tam, kde si to provozovatel zapnul (`MAIL_LOG_LINKS`, výchozí jen lokálně) —
 * jinak z logu vyleze adresát a předmět a věta, co s tím. Do F5 se tělo vypisovalo vždycky,
 * takže produkce bez SMTP měla obnovovací odkazy rozeseté v logu.
 */
class LoggingMailer(
    private val from: String,
    private val logLinks: Boolean,
) : Mailer {
    override fun send(mail: OutgoingMail) {
        if (logLinks) {
            logger.warn {
                "Pošta není nastavená (chybí SMTP), e-mail se jen vypisuje do logu.\n" +
                    "Od: $from\nKomu: ${mail.to}\nPředmět: ${mail.subject}\n\n${mail.body}"
            }
        } else {
            logger.warn {
                "Pošta není nastavená (chybí MAIL_SMTP_HOST), e-mail se neodeslal. " +
                    "Komu: ${mail.to}, předmět: ${mail.subject}. " +
                    "Odkaz do logu nepíšu, byl by z něj klíč k cizímu účtu — nastav SMTP, " +
                    "nebo vědomě zapni MAIL_LOG_LINKS=true."
            }
        }
    }
}
