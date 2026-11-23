package net.eratiem.randogeddon.server.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the first step of a forgotten password shows of an address, and how the second compares the typed one. */
class AddressHintsTest {
  private val box = SecretBox(ByteArray(32) { it.toByte() })

  private fun madeUp(input: String, key: SecretBox = box) =
    madeUpAddress(key.mac("password reset hints", input.trim().lowercase()), input)

  /** The shape [hiddenAddress] gives: a character, the dots, `@`, a character, the dots, the last label. */
  private val shape = Regex("[^@•]•••@[^@•]•••\\.[^@•.]+")

  @Test
  fun `an address shows its first characters, a fixed number of dots and its last label, in lower case`() {
    assertEquals("m•••@p•••.me", hiddenAddress("mia.smith@proton.me"))
    assertEquals("m•••@p•••.me", hiddenAddress(" Mia.Smith@Proton.ME "))
    // Not the lengths: a short one and a long one look alike
    assertEquals("a•••@b•••.de", hiddenAddress("a@b.de"))
    assertEquals("a•••@m•••.uk", hiddenAddress("alexandra.longname@mail.example.co.uk"))
    // A character outside the BMP whole
    assertEquals("😀•••@e•••.org", hiddenAddress("😀x@example.org"))
  }

  @Test
  fun `a made-up address is the same for the same input, in any case, and has a real one's shape`() {
    for (input in listOf("Nobody", "zed", "x", "Mallory_99", "not-an-address@", "someone")) {
      val address = madeUp(input)
      assertTrue(shape.matches(address), "$input: $address")
      assertEquals(address, madeUp(input.uppercase()))
      assertEquals(address, madeUp(" $input "))
    }
    // Another key makes others: nobody without it can tell them from real ones by working them out
    val inputs = (1..50).map { "name$it" }
    assertTrue(inputs.count { madeUp(it) != madeUp(it, SecretBox(ByteArray(32))) } > 25)
    // Many names, many addresses
    assertTrue(inputs.map { madeUp(it) }.toSet().size > 20)
  }

  @Test
  fun `the typed address is compared without regard to case or spaces around it`() {
    assertTrue(sameAddress("Mia.Smith@proton.me", "mia.smith@PROTON.ME"))
    assertTrue(sameAddress("mia@proton.me", "  mia@proton.me "))
    assertFalse(sameAddress("mia@proton.me", "mia@proton.de"))
    assertFalse(sameAddress("mia@proton.me", "mia@proton.me.evil.example"))
    assertFalse(sameAddress("mia@proton.me", ""))
  }
}
