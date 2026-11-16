package net.eratiem.randogeddon.shared

/**
 * The font a theme brings: its [family]'s name (for the licences' list), and its files among the web app's static
 * files (`fonts/<the theme's code>-regular.ttf`, and `-bold.ttf` if it has a bold of its own; made by
 * `scripts/fonts.py`). A [display] font (a pixel font, a wide techno one) is hard to read small: it is for headings
 * only, unless the account asks for it everywhere. All are free to ship (SIL OFL or Apache 2.0).
 */
data class ThemeFont(
  val theme: Theme,
  val family: String,
  val display: Boolean = false,
  val bold: Boolean = true,
  /** How large it is drawn against the original at the same size: a font with wide, tall letters is drawn smaller. */
  val scale: Float = 1f,
) {
  val regularFile: String get() = "fonts/${theme.code}-regular.ttf"
  val boldFile: String? get() = "fonts/${theme.code}-bold.ttf".takeIf { bold }
}

private val FONTS: Map<Theme, ThemeFont> = listOf(
  // Randogeddon's own, by their mood (the default theme keeps the original: what the app draws with anyway)
  ThemeFont(Theme.OCEAN, "Mulish"),
  ThemeFont(Theme.FOREST, "Roboto Slab"),
  ThemeFont(Theme.EMBER, "Alegreya Sans"),
  ThemeFont(Theme.CONTRAST, "Atkinson Hyperlegible"),
  ThemeFont(Theme.MIDNIGHT, "Space Grotesk"),
  ThemeFont(Theme.SAKURA, "Varela Round", bold = false),
  ThemeFont(Theme.TERMINAL, "JetBrains Mono"),
  ThemeFont(Theme.SEPIA, "Literata"),
  // Free fonts that go with each console's look; none is a console's own
  ThemeFont(Theme.NES, "Press Start 2P", display = true, bold = false, scale = 0.75f),
  ThemeFont(Theme.SNES, "Pixelify Sans", display = true),
  ThemeFont(Theme.N64, "Baloo 2"),
  ThemeFont(Theme.GAMECUBE, "Jost"),
  ThemeFont(Theme.WII, "Nunito"),
  ThemeFont(Theme.WIIU, "Fredoka"),
  ThemeFont(Theme.SWITCH, "Rubik"),
  ThemeFont(Theme.SWITCH2, "Figtree"),
  ThemeFont(Theme.PS1, "Chakra Petch"),
  ThemeFont(Theme.PS2, "Orbitron", display = true, bold = false),
  ThemeFont(Theme.PS3, "Outfit"),
  ThemeFont(Theme.PS4, "Public Sans"),
  ThemeFont(Theme.PS5, "Montserrat"),
  ThemeFont(Theme.XBOX, "Oxanium"),
  ThemeFont(Theme.XBOX360, "Exo 2"),
  ThemeFont(Theme.XBOX_SERIES, "Selawik"),
  ThemeFont(Theme.STEAMOS, "Inter"),
).associateBy { it.theme }

/** The font [theme] brings; `null`: none of its own (Randogeddon's original). */
fun fontOf(theme: Theme): ThemeFont? = FONTS[theme]

/**
 * Which font an account reads the app in, as stored: `null` is the font of whatever theme is chosen, [ORIGINAL] is
 * Randogeddon's original with every theme, and a theme's code is that theme's font with every theme.
 */
object FontChoice {
  const val ORIGINAL = "original"

  /** What may be stored: nothing, [ORIGINAL], or the code of a theme that brings a font. */
  fun valid(choice: String?): Boolean = choice == null || choice == ORIGINAL || Theme.of(choice)?.let(::fontOf) != null

  /** The font that shows with [theme] for [choice]; `null`: the original. An unknown choice counts as none. */
  fun font(theme: Theme, choice: String?): ThemeFont? = when {
    choice == ORIGINAL -> null
    choice != null && valid(choice) -> Theme.of(choice)?.let(::fontOf)
    else -> fontOf(theme)
  }

  /** Everything there is to choose besides "the theme's": the original and each theme with a font. */
  val themes: List<Theme> get() = Theme.entries.filter { fontOf(it) != null }
}

/**
 * When a font changes, the letters change one by one in no order: the turn (0 until [turns]) at which the letter at
 * [index] of a text takes the new font. [seed] is the text's own (its hash), so every text has its own order, and the
 * same one each time it is drawn while the change runs.
 */
fun letterTurn(seed: Int, index: Int, turns: Int): Int {
  var h = seed xor (index * -1640531527)
  h = h xor (h ushr 15)
  h *= -2048144789
  h = h xor (h ushr 13)
  return (h and Int.MAX_VALUE) % turns
}
