package net.eratiem.randogeddon.server.config

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import net.eratiem.randogeddon.shared.addressKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// The address ranges, TRUSTED_PROXIES, the client's address and what counts as one: AddressesTest in shared (jvmTest)
class ProxiesTest {
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
