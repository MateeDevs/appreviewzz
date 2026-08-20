package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.core.port.MailException
import cz.matee.appreviewzz.core.port.Mailer
import cz.matee.appreviewzz.core.port.OutgoingMail
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.util.Properties

private val logger = KotlinLogging.logger {}

/**
 * Odeslání pošty přes SMTP.
 *
 * Vědomě jednoduché: žádná fronta, žádné opakování. Když pošta nevyjde, dozví se to volající
 * a člověk v consoli — pozvánka i obnova hesla se dají poslat znovu jedním kliknutím, což je
 * lepší než tichá fronta, o které nikdo neví, že v ní něco vázne.
 *
 * Selhání **nesmí shodit operaci**, kvůli které e-mail vzniká (pozvánka je založená i tak),
 * proto se výjimka překlápí na [MailException] a route ji hlásí jako varování.
 */
class SmtpMailer(
    private val from: String,
    private val host: String,
    private val port: Int,
    private val user: String?,
    private val password: String?,
    private val startTls: Boolean,
) : Mailer {
    private val session: Session by lazy {
        val properties =
            Properties().apply {
                put("mail.smtp.host", host)
                put("mail.smtp.port", port.toString())
                put("mail.smtp.auth", (user != null).toString())
                put("mail.smtp.starttls.enable", startTls.toString())
                // Bez tohohle by se STARTTLS tiše přeskočil, kdyby ho server nenabídl —
                // a heslo k SMTP by šlo po drátě otevřeně.
                put("mail.smtp.starttls.required", startTls.toString())
                put("mail.smtp.connectiontimeout", TIMEOUT_MS.toString())
                put("mail.smtp.timeout", TIMEOUT_MS.toString())
                put("mail.smtp.writetimeout", TIMEOUT_MS.toString())
            }
        if (user != null && password != null) {
            Session.getInstance(
                properties,
                object : Authenticator() {
                    override fun getPasswordAuthentication() = PasswordAuthentication(user, password)
                },
            )
        } else {
            Session.getInstance(properties)
        }
    }

    override fun send(mail: OutgoingMail) {
        try {
            val message =
                MimeMessage(session).apply {
                    // `from` uvnitř apply je vlastnost MimeMessage (pole adres), ne naše konfigurace.
                    setFrom(InternetAddress(this@SmtpMailer.from))
                    setRecipient(Message.RecipientType.TO, InternetAddress(mail.to))
                    setSubject(mail.subject, Charsets.UTF_8.name())
                    setText(mail.body, Charsets.UTF_8.name())
                }
            Transport.send(message)
            logger.info { "E-mail '${mail.subject}' odeslaný na ${mail.to}" }
        } catch (error: MessagingException) {
            // Do logu jde předmět, ne tělo: v těle je jednorázový odkaz.
            logger.error(error) { "E-mail '${mail.subject}' se nepodařilo odeslat na ${mail.to}" }
            throw MailException("E-mail se nepodařilo odeslat: ${error.message}", error)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
    }
}
