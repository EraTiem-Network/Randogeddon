package net.eratiem.randogeddon.server.account

import net.eratiem.randogeddon.server.config.MailConfig
import net.eratiem.randogeddon.server.config.MailSecurity
import java.io.IOException
import java.net.ServerSocket
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Base64
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MailTest {
  @Test
  fun `the message has its headers, a UTF-8 subject as an encoded word, and the text in base64`() {
    val mail = Mail("bob@example.org", "Grüße", "Hi Bob,\n\n.a line starting with a dot\n")
    val message = SmtpClient.message("randogeddon@example.org", mail, ZonedDateTime.of(2026, 11, 13, 10, 0, 0, 0, ZoneOffset.UTC))
    val (head, body) = message.split("\r\n\r\n", limit = 2)
    assertTrue("Date: Fri, 13 Nov 2026 10:00:00 GMT" in head)
    assertTrue("From: Randogeddon <randogeddon@example.org>" in head)
    assertTrue("To: <bob@example.org>" in head)
    assertTrue("Subject: =?UTF-8?B?R3LDvMOfZQ==?=" in head)
    assertTrue(Regex("Message-ID: <[0-9a-f-]+@example.org>").containsMatchIn(head))
    assertEquals("Hi Bob,\r\n\r\n.a line starting with a dot\r\n", String(Base64.getMimeDecoder().decode(body.trim())))
    assertEquals("Plain", SmtpClient.encodedWord("Plain"))
  }

  /** A fake SMTP server on a free port: answers each command, records the conversation, then the mail's data. */
  private fun fakeServer(answers: Map<String, String>, conversation: MutableList<String>): Int {
    val server = ServerSocket(0)
    thread(isDaemon = true) {
      server.use {
        server.accept().use { socket ->
          val input = socket.getInputStream().bufferedReader()
          val output = socket.getOutputStream().bufferedWriter()
          fun say(text: String) = output.apply { write(text + "\r\n"); flush() }
          say("220 fake ESMTP")
          var data = false
          while (true) {
            val line = input.readLine() ?: break
            if (data) {
              if (line == ".") { data = false; say("250 queued") } else conversation += "DATA> $line"
              continue
            }
            conversation += line
            val verb = line.substringBefore(' ').uppercase()
            if (verb == "DATA") data = true
            say(answers[verb] ?: answers[line] ?: "250 ok")
            if (verb == "QUIT") break
          }
        }
      }
    }
    return server.localPort
  }

  @Test
  fun `sends through a server without TLS, logging in with AUTH PLAIN`() {
    val conversation = mutableListOf<String>()
    val port = fakeServer(mapOf("EHLO" to "250-fake\r\n250-AUTH LOGIN PLAIN\r\n250 8BITMIME", "AUTH" to "235 ok", "DATA" to "354 go", "QUIT" to "221 bye"), conversation)
    val config = MailConfig(host = "localhost", port = port, security = MailSecurity.NONE, user = "randogeddon@example.org", password = "secret")
    SmtpClient(config, timeoutMs = 5_000).send("randogeddon@example.org", Mail("bob@example.org", "Hi", "Hello"))

    val plain = Base64.getEncoder().encodeToString("\u0000randogeddon@example.org\u0000secret".toByteArray())
    assertEquals(
      listOf("EHLO randogeddon", "AUTH PLAIN $plain", "MAIL FROM:<randogeddon@example.org>", "RCPT TO:<bob@example.org>", "DATA"),
      conversation.filterNot { it.startsWith("DATA>") }.take(5),
    )
    assertTrue("DATA> Subject: Hi" in conversation)
    assertEquals("QUIT", conversation.last())
  }

  @Test
  fun `a refused recipient fails the mail, and a server without STARTTLS is refused`() {
    val refused = fakeServer(mapOf("RCPT" to "550 no such user"), mutableListOf())
    val error = assertFailsWith<IOException> {
      SmtpClient(MailConfig(host = "localhost", port = refused, security = MailSecurity.NONE), 5_000)
        .send("a@example.org", Mail("nobody@example.org", "Hi", "Hello"))
    }
    assertTrue("550 no such user" in error.message.orEmpty(), error.message)

    val plainOnly = fakeServer(emptyMap(), mutableListOf())
    val noTls = assertFailsWith<IOException> {
      SmtpClient(MailConfig(host = "localhost", port = plainOnly, security = MailSecurity.STARTTLS), 5_000)
        .send("a@example.org", Mail("b@example.org", "Hi", "Hello"))
    }
    assertTrue("STARTTLS" in noTls.message.orEmpty(), noTls.message)
  }
}
