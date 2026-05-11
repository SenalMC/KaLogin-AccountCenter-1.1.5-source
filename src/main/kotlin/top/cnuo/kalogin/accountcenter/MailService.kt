package top.cnuo.kalogin.accountcenter

import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.util.Properties

class MailService(private val plugin: KaLoginAccountCenterPlugin) {
    fun sendCode(to: String, subject: String, playerName: String, code: String, typeName: String) {
        val cfg = plugin.config
        val props = Properties()
        props["mail.smtp.host"] = cfg.getString("mail.smtp-host")
        props["mail.smtp.port"] = cfg.getInt("mail.smtp-port", 465).toString()
        props["mail.smtp.auth"] = "true"
        props["mail.smtp.connectiontimeout"] = cfg.getLong("mail.connection-timeout-ms", 10000L).toString()
        props["mail.smtp.timeout"] = cfg.getLong("mail.timeout-ms", 10000L).toString()
        props["mail.smtp.writetimeout"] = cfg.getLong("mail.write-timeout-ms", 10000L).toString()
        if (cfg.getBoolean("mail.ssl", true)) {
            props["mail.smtp.ssl.enable"] = "true"
        }
        if (cfg.getBoolean("mail.starttls", false)) {
            props["mail.smtp.starttls.enable"] = "true"
        }

        val username = cfg.getString("mail.username") ?: ""
        val password = cfg.getString("mail.password") ?: ""
        val fromAddress = cfg.getString("mail.from-address", username) ?: username
        val fromName = cfg.getString("mail.from-name", "云墨工艺账号中心") ?: "云墨工艺账号中心"

        if (cfg.getBoolean("mail.debug", false) || plugin.config.getBoolean("debug", false)) {
            val host = cfg.getString("mail.smtp-host")
            val port = cfg.getInt("mail.smtp-port", 465)
            val ssl = cfg.getBoolean("mail.ssl", true)
            val starttls = cfg.getBoolean("mail.starttls", false)
            plugin.logger.info("[MailDebug] SMTP host=$host, port=$port, ssl=$ssl, starttls=$starttls, user=$username, from=$fromAddress, to=$to")
        }

        val session = Session.getInstance(props, object : jakarta.mail.Authenticator() {
            override fun getPasswordAuthentication(): jakarta.mail.PasswordAuthentication {
                return jakarta.mail.PasswordAuthentication(username, password)
            }
        })

        session.debug = cfg.getBoolean("mail.debug", false)

        val message = MimeMessage(session)
        message.setFrom(InternetAddress(fromAddress, fromName, "UTF-8"))
        message.setRecipient(Message.RecipientType.TO, InternetAddress(to))
        message.subject = subject
        message.setText(
            """
            云墨工艺账号中心

            玩家：$playerName
            操作：$typeName
            验证码：$code

            验证码有效期为 ${cfg.getInt("verification.expire-minutes", 10)} 分钟。
            如果这不是你本人操作，请忽略本邮件。
            """.trimIndent(),
            "UTF-8"
        )
        if (cfg.getBoolean("mail.debug", false) || plugin.config.getBoolean("debug", false)) {
            plugin.logger.info("[MailDebug] Sending mail subject='$subject' to=$to")
        }
        Transport.send(message)
        if (cfg.getBoolean("mail.debug", false) || plugin.config.getBoolean("debug", false)) {
            plugin.logger.info("[MailDebug] Mail sent successfully to=$to")
        }
    }
}
