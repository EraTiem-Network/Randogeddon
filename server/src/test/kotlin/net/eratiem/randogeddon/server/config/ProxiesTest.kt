package net.eratiem.randogeddon.server.config

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProxiesTest {
  @Test
  fun `addresses and ranges are read as literals`() {
    val docker = assertNotNull(AddressRange.parse("172.16.0.0/12"))
    assertTrue("172.18.0.3" in docker)
    assertTrue("172.31.255.255" in docker)
    assertFalse("172.32.0.1" in docker)
    assertFalse("10.0.0.1" in docker)
    // An IPv4 address written as IPv6 is that address
    assertTrue("::ffff:172.18.0.3" in docker)
    val one = assertNotNull(AddressRange.parse(" 203.0.113.7 "))
    assertTrue("203.0.113.7" in one)
    assertFalse("203.0.113.8" in one)
    val v6 = assertNotNull(AddressRange.parse("fd00::/8"))
    assertTrue("fd12:3456::1" in v6)
    assertTrue("[fd12:3456::1]" in v6)
    assertFalse("fe80::1" in v6)
    assertFalse("172.18.0.3" in v6)
    assertTrue("anything" !in docker)
    // Names are never looked up, and nothing else is a range
    for (text in listOf("traefik", "localhost", "300.1.2.3", "1.2.3", "172.16.0.0/33", "fd00::/129", "10.0.0.0/x", "")) {
      assertNull(AddressRange.parse(text), text)
    }
  }

  @Test
  fun `the setting lists the proxies, and one that is no address stops the start`() {
    assertEquals(emptyList(), trustedProxies(""))
    assertEquals(emptyList(), trustedProxies(" , "))
    val two = trustedProxies("172.16.0.0/12, 10.0.0.5")
    assertEquals(2, two.size)
    assertTrue("10.0.0.5" in two[1])
    assertFailsWith<IllegalArgumentException> { trustedProxies("172.16.0.0/12,traefik") }
  }

  @Test
  fun `the forwarded address is believed only from a trusted proxy`() {
    val traefik = trustedProxies("172.16.0.0/12")
    // Through Traefik: the address it saw (the last; earlier ones the client wrote itself)
    assertEquals("203.0.113.7", clientAddress("172.18.0.2", listOf("198.51.100.1, 203.0.113.7"), traefik))
    assertEquals("203.0.113.7", clientAddress("172.18.0.2", listOf("198.51.100.1", " 203.0.113.7 "), traefik))
    assertEquals("172.18.0.2", clientAddress("172.18.0.2", emptyList(), traefik))
    // Past Traefik, straight to the port: whatever the header says, the peer counts
    assertEquals("192.0.2.50", clientAddress("192.0.2.50", listOf("203.0.113.99"), traefik))
    // Nothing set: every peer is believed, as before the setting
    assertEquals("203.0.113.99", clientAddress("192.0.2.50", listOf("203.0.113.99"), emptyList()))
    assertEquals("192.0.2.50", clientAddress("192.0.2.50", listOf(" , "), emptyList()))
  }

  @Test
  fun `a share link's streams are counted per address, an IPv6 network as one`() = runBlocking {
    val hold = CompletableDeferred<Unit>()
    val key = "watch:" + addressKey("2001:db8:7:7::1")
    val open = (1..OpenStreams.MAX).map { async { OpenStreams.counted(key) { hold.await(); "streamed" } } }
    yield()
    // One more from the same /64 isn't let in, one from elsewhere is
    assertNull(OpenStreams.counted("watch:" + addressKey("2001:db8:7:7:ffff::2")) { "streamed" })
    assertEquals("streamed", OpenStreams.counted("watch:" + addressKey("2001:db8:7:8::1")) { "streamed" })
    hold.complete(Unit)
    assertEquals(List(OpenStreams.MAX) { "streamed" }, open.awaitAll())
    // Closed again: room for new ones
    assertEquals("streamed", OpenStreams.counted(key) { "streamed" })
  }

  @Test
  fun `as many streams per address as the Administrators set, or without a limit`() = runBlocking {
    val hold = CompletableDeferred<Unit>()
    val two = (1..2).map { async { OpenStreams.counted("watch:two", max = 2) { hold.await(); "streamed" } } }
    val many = (1..OpenStreams.MAX + 5).map { async { OpenStreams.counted("watch:many", max = 0) { hold.await(); "streamed" } } }
    yield()
    assertNull(OpenStreams.counted("watch:two", max = 2) { "streamed" })
    assertEquals("streamed", OpenStreams.counted("watch:many", max = 0) { "streamed" })
    hold.complete(Unit)
    assertEquals(List(2) { "streamed" }, two.awaitAll())
    assertEquals(List(OpenStreams.MAX + 5) { "streamed" }, many.awaitAll())
  }
}
