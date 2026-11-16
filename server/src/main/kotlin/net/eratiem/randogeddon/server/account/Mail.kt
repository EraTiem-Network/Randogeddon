package net.eratiem.randogeddon.server.account

import net.eratiem.randogeddon.shared.i18n.MailTexts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.eratiem.randogeddon.server.config.MailConfig
import net.eratiem.randogeddon.server.config.MailSecurity
import net.eratiem.randogeddon.server.config.TrackerConfig
import net.eratiem.randogeddon.server.util.logger
import org.koin.core.annotation.Single
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.invoke.MethodHandles
import java.net.InetSocketAddress
import java.net.Socket
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

private val logger = logger(MethodHandles.lookup().lookupClass())

/** A plain-text mail to one address. */
data class Mail(val to: String, val subject: String, val text: String)

/** Sends the mails: through the SMTP server of [TrackerConfig.mail], or only into the log without one. */
interface Mailer {
  /** Throws [IOException] if the mail server refused it or can't be reached. */
  suspend fun send(mail: Mail)
}

@Single(binds = [Mailer::class])
class MailSender(private val config: TrackerConfig) : Mailer {
  private val smtp = config.mail.takeIf { it.host.isNotBlank() }?.let(::SmtpClient)

  init {
    if (smtp == null) logger.warn("No MAIL_HOST: mails are only written to the log")
  }

  override suspend fun send(mail: Mail) {
    if (smtp == null) {
      logger.info("Mail to {} (not sent, no MAIL_HOST): {}\n{}", mail.to, mail.subject, mail.text)
      return
    }
    withContext(Dispatchers.IO) { smtp.send(config.mail.from, mail) }
    logger.info("Mail to {}: {}", mail.to, mail.subject)
  }
}

/**
 * Just enough SMTP for our mails (RFC 5321): EHLO, STARTTLS or TLS from the start, AUTH PLAIN, one recipient, a
 * plain-text body in UTF-8 (base64, so no line or character is a problem). The server's certificate is checked against
 * [MailConfig.host], so that has to be the name it was issued for ([MailConfig.address] can point elsewhere).
 */
internal class SmtpClient(private val config: MailConfig, private val timeoutMs: Int = 30_000) {
  fun send(from: String, mail: Mail) {
    val target = InetSocketAddress(config.address.ifBlank { config.host }, config.port)
    var socket: Socket = Socket().apply { connect(target, timeoutMs) }
    if (config.security == MailSecurity.TLS) socket = tls(socket)
    socket.soTimeout = timeoutMs
    try {
      var io = Connection(socket.getInputStream(), socket.getOutputStream())
      io.reply(220)
      var capabilities = io.ehlo()
      if (config.security == MailSecurity.STARTTLS) {
        if (capabilities.none { it.equals("STARTTLS", ignoreCase = true) }) {
          throw IOException("${config.host} doesn't offer STARTTLS")
        }
        io.command("STARTTLS", 220)
        socket = tls(socket)
        io = Connection(socket.getInputStream(), socket.getOutputStream())
        capabilities = io.ehlo()
      }
      if (config.user.isNotBlank()) {
        if (capabilities.none { it.uppercase().startsWith("AUTH") && "PLAIN" in it.uppercase() }) {
          throw IOException("${config.host} doesn't offer AUTH PLAIN")
        }
        val plain = Base64.getEncoder().encodeToString("\u0000${config.user}\u0000${config.password}".toByteArray())
        io.command("AUTH PLAIN $plain", 235, shown = "AUTH PLAIN ***")
      }
      io.command("MAIL FROM:<$from>", 250)
      io.command("RCPT TO:<${mail.to}>", 250, 251)
      io.command("DATA", 354)
      io.write(message(from, mail))
      io.command(".", 250)
      runCatching { io.command("QUIT", 221) }
    } finally {
      runCatching { socket.close() }
    }
  }

  /** TLS over [plain], checking the certificate against the host name. */
  private fun tls(plain: Socket): SSLSocket {
    val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
    return (factory.createSocket(plain, config.host, config.port, true) as SSLSocket).apply {
      sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
      soTimeout = timeoutMs
      startHandshake()
    }
  }

  private class Connection(private val input: InputStream, private val output: OutputStream) {
    /** EHLO; the server's capabilities (one per line of its answer, after the greeting line). */
    fun ehlo(): List<String> = command("EHLO randogeddon", 250).drop(1)

    fun command(line: String, vararg expected: Int, shown: String = line): List<String> {
      write("$line\r\n")
      return try {
        reply(*expected)
      } catch (e: IOException) {
        throw IOException("$shown: ${e.message}", e)
      }
    }

    fun write(text: String) {
      output.write(text.toByteArray(Charsets.UTF_8))
      output.flush()
    }

    /** One answer (several lines while they read `250-…`); its text lines. */
    fun reply(vararg expected: Int): List<String> {
      val lines = mutableListOf<String>()
      while (true) {
        val line = readLine() ?: throw IOException("the mail server closed the connection")
        lines += line
        if (line.length < 4 || line[3] != '-') break
      }
      val code = lines.last().take(3).toIntOrNull()
      if (code !in expected.toList()) throw IOException("the mail server answered: ${lines.joinToString(" ")}")
      return lines.map { it.drop(4) }
    }

    /** Byte by byte: nothing may be read ahead of a STARTTLS handshake. */
    private fun readLine(): String? {
      val bytes = java.io.ByteArrayOutputStream()
      while (true) {
        val b = input.read()
        if (b < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.UTF_8)
        if (b == '\n'.code) return bytes.toString(Charsets.UTF_8).trimEnd('\r')
        bytes.write(b)
      }
    }
  }

  companion object {
    /** The mail with its headers, ready for DATA (CRLF line ends; the base64 lines never start with a dot). */
    fun message(from: String, mail: Mail, now: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC)): String {
      val domain = from.substringAfter('@', "randogeddon")
      val text = mail.text.replace("\r\n", "\n").replace("\n", "\r\n")
      val body = Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(text.toByteArray(Charsets.UTF_8))
      return listOf(
        "Date: ${DateTimeFormatter.RFC_1123_DATE_TIME.format(now)}",
        "From: Randogeddon <$from>",
        "To: <${mail.to}>",
        "Subject: ${encodedWord(mail.subject)}",
        "Message-ID: <${UUID.randomUUID()}@$domain>",
        "MIME-Version: 1.0",
        "Content-Type: text/plain; charset=UTF-8",
        "Content-Transfer-Encoding: base64",
        "",
        body,
        "",
      ).joinToString("\r\n")
    }

    /** A header value as it is if plain ASCII, as an RFC 2047 encoded word otherwise. */
    fun encodedWord(text: String): String =
      if (text.all { it.code in 32..126 }) text
      else "=?UTF-8?B?${Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))}?="
  }
}

/** The mails Randogeddon sends, plain text, in the recipient's language ([MailTexts]). */
object Mails {
  fun invitation(to: String, link: String, validDays: Int, texts: MailTexts) =
    Mail(to, texts.invitationSubject, texts.invitation(link, validDays))

  fun emailConfirmation(to: String, name: String, link: String, validDays: Int, texts: MailTexts) =
    Mail(to, texts.confirmationSubject, texts.confirmation(name, link, validDays))

  fun addressChange(to: String, name: String, newAddress: String, texts: MailTexts) =
    Mail(to, texts.addressChangeSubject, texts.addressChange(name, newAddress))

  fun deletionWarning(to: String, name: String, deletesOn: String, settingsLink: String, texts: MailTexts) =
    Mail(to, texts.deletionSubject(deletesOn), texts.deletionWarning(name, deletesOn, settingsLink))
}
