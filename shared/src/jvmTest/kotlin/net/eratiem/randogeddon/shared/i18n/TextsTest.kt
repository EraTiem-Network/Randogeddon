package net.eratiem.randogeddon.shared.i18n

import java.lang.reflect.Method
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class TextsTest {
  /** The groups of [Texts] (`format`, `common`, …): their getters, each returning one group interface. */
  private val groups: List<Method> = Texts::class.java.methods
    .filter { it.parameterCount == 0 && it.returnType.isInterface && it.returnType.packageName == Texts::class.java.packageName }

  private fun Method.groupOf(texts: Texts): Any = invoke(texts)

  @Test
  fun `every language has its own words for every group`() {
    assertTrue("getAccount" in groups.map { it.name }, "found the groups: ${groups.map { it.name }}")
    for (language in Language.entries.filter { it != Language.DEFAULT }) {
      val texts = texts(language)
      assertEquals(language, texts.language)
      for (group in groups) {
        val own = group.groupOf(texts)
        // `override val x = English.x`, or `object : XTexts by English.x { … }` (Kotlin keeps the delegate in a field)
        if (own === group.groupOf(English)) fail("${language.label}: ${group.name} is English's")
        val delegate = own.javaClass.declaredFields.firstOrNull { it.name.startsWith("\$\$delegate") }
        if (delegate != null) fail("${language.label}: ${group.name} takes texts from English")
      }
    }
  }

  @Test
  fun `every text keeps the values it is given`() {
    for (language in Language.entries) {
      val texts = texts(language)
      for (group in groups) {
        val instance = group.groupOf(texts)
        // The problems have a test of their own (their values come as a map)
        for (method in group.returnType.methods.filter { it.parameterCount > 0 && Map::class.java !in it.parameterTypes }) {
          for (args in arguments(method)) {
            val text = method.invoke(instance, *args) as String
            val where = "${language.label} ${group.name}.${method.name}"
            assertTrue(text.isNotBlank(), "$where is empty")
            // A text whose words depend on a yes/no may leave a number out (`free hints` for 0 %), one that depends on a
            // kind of thing a value too (a cheat's release has no item); otherwise every value is in it
            val byKind = method.parameterTypes.any { it.isEnum }
            val checkNumbers = !byKind && method.parameterTypes.none { it == Boolean::class.javaPrimitiveType }
            args.filter { (it is String && !byKind) || (checkNumbers && (it is Int || it is Long)) }.forEach {
              assertTrue(it.toString() in text, "$where drops $it: $text")
            }
          }
        }
      }
    }
  }

  @Test
  fun `every template has the same marks in every language`() {
    val marks = Regex("""\{(\w+)\}""")
    for (group in groups) {
      for (method in group.returnType.methods.filter { it.parameterCount == 0 && it.returnType == String::class.java }) {
        val english = method.invoke(group.groupOf(English)) as String
        val expected = marks.findAll(english).map { it.value }.sorted().toList()
        for (language in Language.entries) {
          val text = method.invoke(group.groupOf(texts(language))) as String
          assertEquals(expected, marks.findAll(text).map { it.value }.sorted().toList(), "${language.label} ${group.name}.${method.name}: $text")
        }
      }
    }
  }

  @Test
  fun `every problem is worded in every language, with the same values`() {
    val keys = listOf(
      "name", "id", "address", "provider", "roles", "status", "reason", "running", "limit", "capacity", "names", "ids",
      "version", "versions", "slots", "max", "sure", "chance", "least", "most", "suspects", "slot", "value", "days",
      "action", "file", "files", "n", "game",
    )
    val args = keys.associateWith { "⟦$it⟧" } + mapOf("roles" to "manager,support", "action" to "RELEASE")
    for (problem in net.eratiem.randogeddon.shared.Problem.entries) {
      val used = Language.entries.associateWith { language ->
        val text = texts(language).problems.text(problem, args)
        assertTrue(text.isNotBlank(), "${language.label} $problem is empty")
        keys.filter { "⟦$it⟧" in text }.toSet()
      }
      assertEquals(used.getValue(Language.EN), used.getValue(Language.DE), "$problem uses other values in German")
    }
  }

  /** Calls of [method] to try: distinct markers for names and numbers, every value of an enum, both of a yes/no. */
  private fun arguments(method: Method): List<Array<Any>> {
    var calls = listOf(emptyList<Any>())
    method.parameterTypes.forEachIndexed { i, type ->
      val values: List<Any> = when {
        type == String::class.java -> listOf("⟦$i⟧")
        type == Int::class.javaPrimitiveType -> listOf(7300 + i)
        type == Long::class.javaPrimitiveType -> listOf(7300L + i)
        type == Boolean::class.javaPrimitiveType -> listOf(true, false)
        type.isEnum -> type.enumConstants.toList()
        else -> fail("${method.name}: no test values for ${type.name}")
      }
      calls = calls.flatMap { call -> values.map { call + it } }
    }
    return calls.map { it.toTypedArray() }
  }

  @Test
  fun `a logic warning without its reason leaves the logic's words out`() {
    for (language in Language.entries) {
      val dashboard = texts(language).dashboard
      for (text in listOf(dashboard.availableMismatch(""), dashboard.mismatchBanner(""), dashboard.availableMismatch(" "))) {
        assertTrue(text.isNotBlank(), "${language.label}: $text")
        assertTrue("The logic says" !in text && "Die Logik sagt" !in text, "${language.label}: $text")
        assertTrue(!text.endsWith(":") && !text.endsWith(": ") && !text.endsWith("\n"), "${language.label}: $text")
      }
      // With one, it is there
      assertTrue(dashboard.mismatchBanner("Chest B").endsWith("Chest B"), language.label)
      assertTrue(dashboard.availableMismatch("Chest B").endsWith("Chest B"), language.label)
    }
  }

  @Test
  fun `numbers and times are written as the language writes them`() {
    assertEquals("12,345", English.number(12345))
    assertEquals("12.345", German.number(12345))
    assertEquals("-1,234,567", English.number(-1234567))
    assertEquals("12.3", English.decimal(12.34))
    assertEquals("12,3", German.decimal(12.34))
    assertEquals("0.5", English.decimal(0.45))
    assertEquals("88,5 KB", German.fileSize(90_624))
    val now = 10_000_000_000L
    assertEquals("5m ago", English.ago(now - 5 * 60_000, now))
    assertEquals("vor 5 Min.", German.ago(now - 5 * 60_000, now))
    assertEquals("vor 1 Tag", German.ago(now - 86_400_000, now))
    assertEquals("never", English.ago(null, now))
    assertEquals("4h 12m", English.duration((4 * 60 + 12) * 60_000L))
    assertEquals("4 Std. 12 Min.", German.duration((4 * 60 + 12) * 60_000L))
    assertEquals("<1m", English.duration(30_000))
  }

  @Test
  fun `a language is found by its code or a browser's tag`() {
    assertEquals(Language.DE, Language.of("de"))
    assertEquals(Language.DE, Language.of("de-AT"))
    assertEquals(Language.EN, Language.of("en_US"))
    assertEquals(null, Language.of("fr"))
    assertEquals(null, Language.of(null))
  }
}
