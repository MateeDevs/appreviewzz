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
 * Odkaz v těle je jednorázový token: kdo vidí log, umí potvrdit e-mail nebo změnit heslo.
 * V produkci s nastavenou poštou se sem nedostane nic.
 */
class LoggingMailer(
    private val from: String,
) : Mailer {
    override fun send(mail: OutgoingMail) {
        logger.warn {
            "Pošta není nastavená (chybí SMTP), e-mail se jen vypisuje do logu.\n" +
                "Od: $from\nKomu: ${mail.to}\nPředmět: ${mail.subject}\n\n${mail.body}"
        }
    }
}
