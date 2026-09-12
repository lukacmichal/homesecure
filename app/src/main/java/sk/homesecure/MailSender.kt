package sk.homesecure

import java.io.File
import java.util.Properties
import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

/**
 * Odoslanie jedneho mailu s fotkami cez SMTP.
 *
 * Blokujuce a vyhadzuje vynimku, ked sa nepodari odoslat - volajuci (fronta
 * v MonitorService) to berie ako signal, ze ma skusit neskor.
 *
 * Poznamka k Gmailu: obycajne heslo k uctu nefunguje, treba vygenerovat
 * "app password". Odporucam na to zalozit samostatny ucet - keby ti niekto
 * telefon zobral, ziska pristup len k tomu odosielaciemu, nie k tvojej hlavnej
 * schranke.
 */
object MailSender {

    fun send(cfg: MailConfig, subject: String, body: String, attachments: List<File>) {
        require(cfg.isUsable) { "Nastavenia mailu nie su kompletne." }

        val props = Properties().apply {
            put("mail.smtp.host", cfg.smtpHost)
            put("mail.smtp.port", cfg.smtpPort.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.connectiontimeout", "20000")
            put("mail.smtp.timeout", "40000")
            put("mail.smtp.writetimeout", "60000")
            if (cfg.smtpPort == 465) {
                put("mail.smtp.ssl.enable", "true")
            } else {
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
            }
            put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3")
        }

        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() =
                PasswordAuthentication(cfg.username, cfg.password)
        })

        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(cfg.username))
            // Prijemcov moze byt viac, oddelenych ciarkou.
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(cfg.to, false))
            setSubject(subject, "UTF-8")
            sentDate = java.util.Date()
        }

        val multipart = MimeMultipart()
        multipart.addBodyPart(MimeBodyPart().apply { setText(body, "UTF-8") })
        for (file in attachments) {
            if (!file.exists()) continue
            multipart.addBodyPart(MimeBodyPart().apply {
                dataHandler = DataHandler(FileDataSource(file))
                fileName = file.name
            })
        }
        message.setContent(multipart)

        Transport.send(message)
    }
}
