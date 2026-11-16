package net.eratiem.randogeddon.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How the themes are sorted where one is picked: Randogeddon's own, and the ones inspired by a maker's consoles or
 * system.
 */
enum class ThemeGroup { OWN, NINTENDO, PLAYSTATION, XBOX, STEAM }

/**
 * A look of the app: a light and a dark [ThemeColors] each ([palettes]). Stored by [code] (at most 16 characters: the
 * column's width). The order here is the order they are offered in, within their [group].
 */
@Serializable
enum class Theme(val code: String, val group: ThemeGroup = ThemeGroup.OWN) {
  @SerialName("randogeddon") RANDOGEDDON("randogeddon"),
  @SerialName("ocean") OCEAN("ocean"),
  @SerialName("forest") FOREST("forest"),
  @SerialName("ember") EMBER("ember"),
  @SerialName("contrast") CONTRAST("contrast"),
  @SerialName("midnight") MIDNIGHT("midnight"),
  @SerialName("sakura") SAKURA("sakura"),
  @SerialName("terminal") TERMINAL("terminal"),
  @SerialName("sepia") SEPIA("sepia"),
  // Inspired by consoles: their colours only (the names are their owners' trademarks; see the notice under the cards)
  @SerialName("nes") NES("nes", ThemeGroup.NINTENDO),
  @SerialName("snes") SNES("snes", ThemeGroup.NINTENDO),
  @SerialName("n64") N64("n64", ThemeGroup.NINTENDO),
  @SerialName("gamecube") GAMECUBE("gamecube", ThemeGroup.NINTENDO),
  @SerialName("wii") WII("wii", ThemeGroup.NINTENDO),
  @SerialName("wiiu") WIIU("wiiu", ThemeGroup.NINTENDO),
  @SerialName("switch") SWITCH("switch", ThemeGroup.NINTENDO),
  @SerialName("switch2") SWITCH2("switch2", ThemeGroup.NINTENDO),
  @SerialName("ps1") PS1("ps1", ThemeGroup.PLAYSTATION),
  @SerialName("ps2") PS2("ps2", ThemeGroup.PLAYSTATION),
  @SerialName("ps3") PS3("ps3", ThemeGroup.PLAYSTATION),
  @SerialName("ps4") PS4("ps4", ThemeGroup.PLAYSTATION),
  @SerialName("ps5") PS5("ps5", ThemeGroup.PLAYSTATION),
  @SerialName("xbox") XBOX("xbox", ThemeGroup.XBOX),
  @SerialName("xbox360") XBOX360("xbox360", ThemeGroup.XBOX),
  @SerialName("xbox-series") XBOX_SERIES("xbox-series", ThemeGroup.XBOX),
  @SerialName("steamos") STEAMOS("steamos", ThemeGroup.STEAM),
  ;

  companion object {
    val DEFAULT = RANDOGEDDON

    fun of(code: String?): Theme? = entries.firstOrNull { it.code == code }
  }
}

/** Light, dark, or automatic: the device's light/dark setting, following it when it changes. */
@Serializable
enum class ThemeMode(val code: String) {
  @SerialName("light") LIGHT("light"),
  @SerialName("dark") DARK("dark"),
  @SerialName("auto") AUTO("auto"),
  ;

  /** The next one for the top card's button: light, dark, automatic, light, … */
  val next: ThemeMode get() = entries[(ordinal + 1) % entries.size]

  companion object {
    val DEFAULT = AUTO

    fun of(code: String?): ThemeMode? = entries.firstOrNull { it.code == code }
  }
}

/**
 * One palette, as ARGB numbers (the web app makes Compose colours of them; the JVM tests check their contrast).
 * [onBrand]: text and icons on the brand gradient and the accent; [liveDot]/[offlineDot]: the top card's state.
 */
data class ThemeColors(
  val dark: Boolean,
  val background: Long,
  val panel: Long,
  val panelAlt: Long,
  val border: Long,
  val text: Long,
  val muted: Long,
  val brandStart: Long,
  val brandEnd: Long,
  val onBrand: Long,
  val progression: Long,
  val useful: Long,
  val trap: Long,
  val ok: Long,
  val warn: Long,
  val key: Long,
  val locked: Long,
  val barBackground: Long,
  val shadow: Long,
  val liveDot: Long,
  val offlineDot: Long,
  /** Avatar and game chip colours, picked by a stable hash of the name. */
  val hues: List<Long>,
)

/** The light and the dark palette of [theme]. */
fun palettes(theme: Theme): Pair<ThemeColors, ThemeColors> = when (theme) {
  Theme.RANDOGEDDON -> RANDOGEDDON_LIGHT to RANDOGEDDON_DARK
  Theme.OCEAN -> OCEAN_LIGHT to OCEAN_DARK
  Theme.FOREST -> FOREST_LIGHT to FOREST_DARK
  Theme.EMBER -> EMBER_LIGHT to EMBER_DARK
  Theme.CONTRAST -> CONTRAST_LIGHT to CONTRAST_DARK
  Theme.MIDNIGHT -> MIDNIGHT_LIGHT to MIDNIGHT_DARK
  Theme.SAKURA -> SAKURA_LIGHT to SAKURA_DARK
  Theme.TERMINAL -> TERMINAL_LIGHT to TERMINAL_DARK
  Theme.SEPIA -> SEPIA_LIGHT to SEPIA_DARK
  Theme.NES -> NES_LIGHT to NES_DARK
  Theme.SNES -> SNES_LIGHT to SNES_DARK
  Theme.N64 -> N64_LIGHT to N64_DARK
  Theme.GAMECUBE -> GAMECUBE_LIGHT to GAMECUBE_DARK
  Theme.WII -> WII_LIGHT to WII_DARK
  Theme.WIIU -> WIIU_LIGHT to WIIU_DARK
  Theme.SWITCH -> SWITCH_LIGHT to SWITCH_DARK
  Theme.SWITCH2 -> SWITCH2_LIGHT to SWITCH2_DARK
  Theme.PS1 -> PS1_LIGHT to PS1_DARK
  Theme.PS2 -> PS2_LIGHT to PS2_DARK
  Theme.PS3 -> PS3_LIGHT to PS3_DARK
  Theme.PS4 -> PS4_LIGHT to PS4_DARK
  Theme.PS5 -> PS5_LIGHT to PS5_DARK
  Theme.XBOX -> XBOX_LIGHT to XBOX_DARK
  Theme.XBOX360 -> XBOX360_LIGHT to XBOX360_DARK
  Theme.XBOX_SERIES -> XBOX_SERIES_LIGHT to XBOX_SERIES_DARK
  Theme.STEAMOS -> STEAMOS_LIGHT to STEAMOS_DARK
}

/** Calm surfaces, vivid accents: the coral → violet brand gradient. */
private val RANDOGEDDON_LIGHT = ThemeColors(
  dark = false,
  background = 0xFFF5F4FA, panel = 0xFFFFFFFF, panelAlt = 0xFFF7F6FC, border = 0xFFE7E4F0,
  text = 0xFF1B1A2B, muted = 0xFF6C6A80,
  brandStart = 0xFFFF7A45, brandEnd = 0xFF7C5CFF, onBrand = 0xFFFFFFFF,
  progression = 0xFF7C5CFF, useful = 0xFF2E90FA, trap = 0xFFF04438,
  ok = 0xFF12B76A, warn = 0xFFF79009, key = 0xFFDC9A06, locked = 0xFF8A8AA3,
  barBackground = 0xFFECE9F5, shadow = 0x1A2B2260, liveDot = 0xFF6CF0A9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFFFF7A45, 0xFF7C5CFF, 0xFF12B76A, 0xFF2E90FA, 0xFFE0409A, 0xFF0BA5EC, 0xFFF79009, 0xFF6172F3),
)

/** Near-black surfaces; the accents stay vivid. */
private val RANDOGEDDON_DARK = ThemeColors(
  dark = true,
  background = 0xFF07070C, panel = 0xFF101118, panelAlt = 0xFF171821, border = 0xFF22232F,
  text = 0xFFE9E8F2, muted = 0xFF8D8BA3,
  brandStart = 0xFFFF7A4D, brandEnd = 0xFF8E72FF, onBrand = 0xFFFFFFFF,
  progression = 0xFFA48FFF, useful = 0xFF53B1FD, trap = 0xFFF97066,
  ok = 0xFF32D583, warn = 0xFFFDB022, key = 0xFFFAC515, locked = 0xFF8E8CA8,
  barBackground = 0xFF22232F, shadow = 0x66000000, liveDot = 0xFF6CF0A9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFFFF8A5C, 0xFF9B83FF, 0xFF32D583, 0xFF53B1FD, 0xFFF670C7, 0xFF36BFFA, 0xFFFDB022, 0xFF8098F9),
)

/** Ocean: teal → blue, cool blue-grey surfaces. */
private val OCEAN_LIGHT = ThemeColors(
  dark = false,
  background = 0xFFF2F7FA, panel = 0xFFFFFFFF, panelAlt = 0xFFF5FAFC, border = 0xFFDCE8EF, text = 0xFF0F2233,
  muted = 0xFF55697A, brandStart = 0xFF0EA5A4, brandEnd = 0xFF2563EB, onBrand = 0xFFFFFFFF, progression = 0xFF4F46E5,
  useful = 0xFF0E7490, trap = 0xFFE11D48, ok = 0xFF059669, warn = 0xFFD97706, key = 0xFFCA8A04, locked = 0xFF7C8A99,
  barBackground = 0xFFE3EDF3, shadow = 0x1A0F2A44, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFF0EA5A4, 0xFF2563EB, 0xFF0891B2, 0xFF4F46E5, 0xFF059669, 0xFF0284C7, 0xFFD97706, 0xFF7C3AED),
)

private val OCEAN_DARK = ThemeColors(
  dark = true,
  background = 0xFF04111A, panel = 0xFF0B1B27, panelAlt = 0xFF102331, border = 0xFF1C3343, text = 0xFFE3F1F8,
  muted = 0xFF8BA3B3, brandStart = 0xFF0D9488, brandEnd = 0xFF3B82F6, onBrand = 0xFFFFFFFF, progression = 0xFF818CF8,
  useful = 0xFF22D3EE, trap = 0xFFFB7185, ok = 0xFF34D399, warn = 0xFFFBBF24, key = 0xFFFDE047, locked = 0xFF8FA3B3,
  barBackground = 0xFF1C3343, shadow = 0x66000000, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFF2DD4BF, 0xFF60A5FA, 0xFF22D3EE, 0xFF818CF8, 0xFF34D399, 0xFF38BDF8, 0xFFFBBF24, 0xFFA78BFA),
)

/** Forest: lime → green, warm green-grey surfaces. */
private val FOREST_LIGHT = ThemeColors(
  dark = false,
  background = 0xFFF4F7F2, panel = 0xFFFFFFFF, panelAlt = 0xFFF7FAF5, border = 0xFFDDE7D8, text = 0xFF14231A,
  muted = 0xFF56685C, brandStart = 0xFF65A30D, brandEnd = 0xFF15803D, onBrand = 0xFFFFFFFF, progression = 0xFF9333EA,
  useful = 0xFF0E7490, trap = 0xFFDC2626, ok = 0xFF166534, warn = 0xFFCA8A04, key = 0xFFA16207, locked = 0xFF7D8B80,
  barBackground = 0xFFE6EEE2, shadow = 0x1A14301C, liveDot = 0xFFB9F27A, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFF15803D, 0xFF65A30D, 0xFF0E7490, 0xFF9333EA, 0xFFCA8A04, 0xFF0D9488, 0xFFB45309, 0xFF4D7C0F),
)

private val FOREST_DARK = ThemeColors(
  dark = true,
  background = 0xFF08110B, panel = 0xFF0F1A13, panelAlt = 0xFF142219, border = 0xFF213326, text = 0xFFE6F2E8,
  muted = 0xFF8FA596, brandStart = 0xFFA3E635, brandEnd = 0xFF22C55E, onBrand = 0xFF08110B, progression = 0xFFC084FC,
  useful = 0xFF22D3EE, trap = 0xFFF87171, ok = 0xFF4ADE80, warn = 0xFFFACC15, key = 0xFFFDE68A, locked = 0xFF94A89A,
  barBackground = 0xFF213326, shadow = 0x66000000, liveDot = 0xFFB9F27A, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFF4ADE80, 0xFFA3E635, 0xFF22D3EE, 0xFFC084FC, 0xFFFACC15, 0xFF2DD4BF, 0xFFFB923C, 0xFF86EFAC),
)

/** Ember: red → amber, warm surfaces; dark text on the brand. */
private val EMBER_LIGHT = ThemeColors(
  dark = false,
  background = 0xFFFBF6F2, panel = 0xFFFFFFFF, panelAlt = 0xFFFDF8F4, border = 0xFFEFE1D6, text = 0xFF2A1710,
  muted = 0xFF6E5A50, brandStart = 0xFFDC2626, brandEnd = 0xFFC2570A, onBrand = 0xFFFFFFFF, progression = 0xFF7C3AED,
  useful = 0xFF2563EB, trap = 0xFFB91C1C, ok = 0xFF15803D, warn = 0xFFC2410C, key = 0xFFB45309, locked = 0xFF8B7B72,
  barBackground = 0xFFF3E6DC, shadow = 0x1A3A1C10, liveDot = 0xFFFFD27A, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFFDC2626, 0xFFF59E0B, 0xFFEA580C, 0xFFB45309, 0xFFE11D48, 0xFFD97706, 0xFF7C3AED, 0xFF2563EB),
)

private val EMBER_DARK = ThemeColors(
  dark = true,
  background = 0xFF120A07, panel = 0xFF1C120D, panelAlt = 0xFF241812, border = 0xFF36251C, text = 0xFFF6E9E1,
  muted = 0xFFA89184, brandStart = 0xFFF87171, brandEnd = 0xFFFBBF24, onBrand = 0xFF1F1206, progression = 0xFFC4B5FD,
  useful = 0xFF93C5FD, trap = 0xFFF87171, ok = 0xFF4ADE80, warn = 0xFFFB923C, key = 0xFFFCD34D, locked = 0xFFA8978C,
  barBackground = 0xFF36251C, shadow = 0x66000000, liveDot = 0xFFFFD27A, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFFF87171, 0xFFFBBF24, 0xFFFB923C, 0xFFFCD34D, 0xFFFDA4AF, 0xFFF59E0B, 0xFFC4B5FD, 0xFF93C5FD),
)

/** High contrast: black and white, strong borders, deep item colours. */
private val CONTRAST_LIGHT = ThemeColors(
  dark = false,
  background = 0xFFFFFFFF, panel = 0xFFFFFFFF, panelAlt = 0xFFF2F2F2, border = 0xFF000000, text = 0xFF000000,
  muted = 0xFF3A3A3A, brandStart = 0xFF000000, brandEnd = 0xFF1A1A1A, onBrand = 0xFFFFFFFF, progression = 0xFF5B21B6,
  useful = 0xFF0B4FD8, trap = 0xFFB00020, ok = 0xFF0A6B2E, warn = 0xFF8A4B00, key = 0xFF6B5000, locked = 0xFF4A4A4A,
  barBackground = 0xFFD9D9D9, shadow = 0x33000000, liveDot = 0xFF00C853, offlineDot = 0xFFFF5252,
  hues = listOf(0xFF000000, 0xFF0B4FD8, 0xFFB00020, 0xFF0A6B2E, 0xFF5B21B6, 0xFF8A4B00, 0xFF006064, 0xFF6B5000),
)

private val CONTRAST_DARK = ThemeColors(
  dark = true,
  background = 0xFF000000, panel = 0xFF000000, panelAlt = 0xFF121212, border = 0xFFFFFFFF, text = 0xFFFFFFFF,
  muted = 0xFFCFCFCF, brandStart = 0xFFFFFFFF, brandEnd = 0xFFE6E6E6, onBrand = 0xFF000000, progression = 0xFFC9B8FF,
  useful = 0xFF8CC8FF, trap = 0xFFFF8A8A, ok = 0xFF7CFFA0, warn = 0xFFFFC870, key = 0xFFFFEB70, locked = 0xFFBDBDBD,
  barBackground = 0xFF333333, shadow = 0x00000000, liveDot = 0xFF00E676, offlineDot = 0xFFFF5252,
  hues = listOf(0xFFFFFFFF, 0xFF8CC8FF, 0xFFFF8A8A, 0xFF7CFFA0, 0xFFC9B8FF, 0xFFFFC870, 0xFF80DEEA, 0xFFFFEB70),
)

/** N64: charcoal, the controller's red, yellow, green and blue. */
private val N64_LIGHT = ThemeColors(
  dark = false,
  background = 0xFFF1F1F3, panel = 0xFFFFFFFF, panelAlt = 0xFFF6F6F8, border = 0xFFDCDCE0, text = 0xFF1E1E24,
  muted = 0xFF5C5C66, brandStart = 0xFFD0202A, brandEnd = 0xFF1E5BB8, onBrand = 0xFFFFFFFF, progression = 0xFF1E5BB8,
  useful = 0xFF0B8A42, trap = 0xFFC81E28, ok = 0xFF2E7D32, warn = 0xFFB7791F, key = 0xFF8A6D00, locked = 0xFF77777F,
  barBackground = 0xFFE4E4E8, shadow = 0x1A202030, liveDot = 0xFF4CE07A, offlineDot = 0xFFFF9A9A,
  hues = listOf(0xFFD0202A, 0xFFE0A100, 0xFF0B8A42, 0xFF1E5BB8, 0xFF6B3FA0, 0xFFC2410C, 0xFF0E7490, 0xFF5C5C66),
)

private val N64_DARK = ThemeColors(
  dark = true,
  background = 0xFF121214, panel = 0xFF1B1B1F, panelAlt = 0xFF232328, border = 0xFF303036, text = 0xFFECECEF,
  muted = 0xFF9C9CA6, brandStart = 0xFFF0434B, brandEnd = 0xFF3B82D6, onBrand = 0xFFFFFFFF, progression = 0xFF6EA8F5,
  useful = 0xFF3CC97A, trap = 0xFFFF6B70, ok = 0xFF8BE08F, warn = 0xFFFFC14D, key = 0xFFFFE066, locked = 0xFFA0A0AA,
  barBackground = 0xFF303036, shadow = 0x66000000, liveDot = 0xFF4CE07A, offlineDot = 0xFFFF9A9A,
  hues = listOf(0xFFF0434B, 0xFFFFC14D, 0xFF3CC97A, 0xFF6EA8F5, 0xFFB794F4, 0xFFFF8A4C, 0xFF4FD1C5, 0xFFA0A0AA),
)

/** GameCube: indigo with spice orange. */
private val GAMECUBE_LIGHT = ThemeColors(
  dark = false,
  background = 0xFFF4F3FA, panel = 0xFFFFFFFF, panelAlt = 0xFFF7F6FC, border = 0xFFE1DEF0, text = 0xFF1C1838,
  muted = 0xFF5F5A80, brandStart = 0xFFE8781A, brandEnd = 0xFF4B3FB8, onBrand = 0xFFFFFFFF, progression = 0xFF5B4BD6,
  useful = 0xFF1C7ED6, trap = 0xFFD6336C, ok = 0xFF2B9348, warn = 0xFFE8590C, key = 0xFFC27C0E, locked = 0xFF8A86A3,
  barBackground = 0xFFE9E6F5, shadow = 0x1A2B2260, liveDot = 0xFF7CF0B0, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFF4B3FB8, 0xFFE8781A, 0xFF5B4BD6, 0xFF1C7ED6, 0xFFD6336C, 0xFF2B9348, 0xFFC27C0E, 0xFF7048E8),
)

private val GAMECUBE_DARK = ThemeColors(
  dark = true,
  background = 0xFF0C0B1A, panel = 0xFF15132B, panelAlt = 0xFF1C1A36, border = 0xFF2B2849, text = 0xFFECEAFB,
  muted = 0xFF9D98C2, brandStart = 0xFFE8701A, brandEnd = 0xFF8576F0, onBrand = 0xFFFFFFFF, progression = 0xFFA99CFF,
  useful = 0xFF66B2FF, trap = 0xFFFF6F9A, ok = 0xFF5CD68C, warn = 0xFFFF9A3D, key = 0xFFFFD166, locked = 0xFF9E99BD,
  barBackground = 0xFF2B2849, shadow = 0x66000000, liveDot = 0xFF7CF0B0, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFF8576F0, 0xFFFF9A3D, 0xFFA99CFF, 0xFF66B2FF, 0xFFFF6F9A, 0xFF5CD68C, 0xFFFFD166, 0xFFC084FC),
)

/** Wii: white and silver with its blue. */
private val WII_LIGHT = ThemeColors(
  dark = false,
  background = 0xFFF4F6F8, panel = 0xFFFFFFFF, panelAlt = 0xFFF7F9FB, border = 0xFFDDE2E8, text = 0xFF1F2730,
  muted = 0xFF5E6873, brandStart = 0xFF1A9FD0, brandEnd = 0xFF0083B5, onBrand = 0xFFFFFFFF, progression = 0xFF7048E8,
  useful = 0xFF0077B6, trap = 0xFFE03131, ok = 0xFF2F9E44, warn = 0xFFE67700, key = 0xFFB78A00, locked = 0xFF8A939D,
  barBackground = 0xFFE6EAEE, shadow = 0x1A1F2A3A, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFF34BEED, 0xFF0077B6, 0xFF7048E8, 0xFF2F9E44, 0xFFE67700, 0xFFE03131, 0xFF0CA678, 0xFFB78A00),
)

private val WII_DARK = ThemeColors(
  dark = true,
  background = 0xFF0D1114, panel = 0xFF161C21, panelAlt = 0xFF1C232A, border = 0xFF2A333C, text = 0xFFEEF3F7,
  muted = 0xFF9AA7B3, brandStart = 0xFF5ED0F5, brandEnd = 0xFF22A8DB, onBrand = 0xFF0D1114, progression = 0xFFB197FC,
  useful = 0xFF4DABF7, trap = 0xFFFF8787, ok = 0xFF69DB7C, warn = 0xFFFFA94D, key = 0xFFFFD43B, locked = 0xFFA5B0BA,
  barBackground = 0xFF2A333C, shadow = 0x66000000, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFF5ED0F5, 0xFF4DABF7, 0xFFB197FC, 0xFF69DB7C, 0xFFFFA94D, 0xFFFF8787, 0xFF38D9A9, 0xFFFFD43B),
)

/** Wii U: black and white with its blue. */
private val WIIU_LIGHT = ThemeColors(
  dark = false,
  background = 0xFFF5F5F5, panel = 0xFFFFFFFF, panelAlt = 0xFFF8F8F8, border = 0xFFDEDEDE, text = 0xFF111111,
  muted = 0xFF5A5A5A, brandStart = 0xFF0AA2CF, brandEnd = 0xFF0084AD, onBrand = 0xFFFFFFFF, progression = 0xFF6741D9,
  useful = 0xFF1971C2, trap = 0xFFE03131, ok = 0xFF2B8A3E, warn = 0xFFE8590C, key = 0xFFB8860B, locked = 0xFF868686,
  barBackground = 0xFFE8E8E8, shadow = 0x1A000000, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFF0084AD, 0xFF00B5E5, 0xFF6741D9, 0xFF1971C2, 0xFF2B8A3E, 0xFFE8590C, 0xFFE03131, 0xFFB8860B),
)

private val WIIU_DARK = ThemeColors(
  dark = true,
  background = 0xFF0A0A0A, panel = 0xFF141414, panelAlt = 0xFF1C1C1C, border = 0xFF2A2A2A, text = 0xFFF2F2F2,
  muted = 0xFFA3A3A3, brandStart = 0xFF0E9CC8, brandEnd = 0xFF009AC7, onBrand = 0xFFFFFFFF, progression = 0xFFA78BFA,
  useful = 0xFF4DABF7, trap = 0xFFFF6B6B, ok = 0xFF51CF66, warn = 0xFFFF922B, key = 0xFFFCC419, locked = 0xFF9E9E9E,
  barBackground = 0xFF2A2A2A, shadow = 0x66000000, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFF33C6F0, 0xFF009AC7, 0xFFA78BFA, 0xFF4DABF7, 0xFF51CF66, 0xFFFF922B, 0xFFFF6B6B, 0xFFFCC419),
)

/** Switch: neon red and neon blue. */
private val SWITCH_LIGHT = ThemeColors(
  dark = false,
  background = 0xFFF3F4F6, panel = 0xFFFFFFFF, panelAlt = 0xFFF7F8FA, border = 0xFFDFE2E6, text = 0xFF1A1D21,
  muted = 0xFF5B6069, brandStart = 0xFFE8303F, brandEnd = 0xFF0089A8, onBrand = 0xFFFFFFFF, progression = 0xFF7B3FE4,
  useful = 0xFF0077A0, trap = 0xFFE8303F, ok = 0xFF2B9348, warn = 0xFFE07800, key = 0xFFB98900, locked = 0xFF888E96,
  barBackground = 0xFFE7E9EC, shadow = 0x1A1A1D21, liveDot = 0xFF4FE0F5, offlineDot = 0xFFFF9AA2,
  hues = listOf(0xFFE8303F, 0xFF0096B8, 0xFF7B3FE4, 0xFF2B9348, 0xFFE07800, 0xFF00A3C4, 0xFFD6336C, 0xFFB98900),
)

private val SWITCH_DARK = ThemeColors(
  dark = true,
  background = 0xFF121316, panel = 0xFF1C1E22, panelAlt = 0xFF23262B, border = 0xFF31353B, text = 0xFFEEF0F3,
  muted = 0xFF9EA4AD, brandStart = 0xFFFF4554, brandEnd = 0xFF00C3E3, onBrand = 0xFF121316, progression = 0xFFB59CFF,
  useful = 0xFF3FD6F0, trap = 0xFFFF6B76, ok = 0xFF5BD68A, warn = 0xFFFFA54D, key = 0xFFFFD54D, locked = 0xFFA3A8B0,
  barBackground = 0xFF31353B, shadow = 0x66000000, liveDot = 0xFF4FE0F5, offlineDot = 0xFFFF9AA2,
  hues = listOf(0xFFFF4554, 0xFF00C3E3, 0xFFB59CFF, 0xFF5BD68A, 0xFFFFA54D, 0xFF3FD6F0, 0xFFFF6B76, 0xFFFFD54D),
)

/** Switch 2: graphite with calmer neon red and blue. */
private val SWITCH2_LIGHT = ThemeColors(
  dark = false,
  background = 0xFFEEF0F2, panel = 0xFFFAFBFC, panelAlt = 0xFFF2F4F6, border = 0xFFD6DADF, text = 0xFF16191D,
  muted = 0xFF565C64, brandStart = 0xFFD63A4A, brandEnd = 0xFF1F86A8, onBrand = 0xFFFFFFFF, progression = 0xFF6F42C1,
  useful = 0xFF1C7EA8, trap = 0xFFC92A3A, ok = 0xFF2F855A, warn = 0xFFC05621, key = 0xFFA67C00, locked = 0xFF7F868E,
  barBackground = 0xFFE2E5E9, shadow = 0x1A16191D, liveDot = 0xFF5CD6EE, offlineDot = 0xFFFF9AA2,
  hues = listOf(0xFFD63A4A, 0xFF1F86A8, 0xFF6F42C1, 0xFF2F855A, 0xFFC05621, 0xFF1C7EA8, 0xFFB83280, 0xFFA67C00),
)

private val SWITCH2_DARK = ThemeColors(
  dark = true,
  background = 0xFF16181B, panel = 0xFF1F2226, panelAlt = 0xFF262A2F, border = 0xFF343940, text = 0xFFE9ECEF,
  muted = 0xFF9AA1A9, brandStart = 0xFFE85A68, brandEnd = 0xFF3BB4D8, onBrand = 0xFF16181B, progression = 0xFFA78BFA,
  useful = 0xFF5CC4E4, trap = 0xFFF2707C, ok = 0xFF63C98C, warn = 0xFFF5A25D, key = 0xFFE9C46A, locked = 0xFF9AA1A9,
  barBackground = 0xFF343940, shadow = 0x66000000, liveDot = 0xFF5CD6EE, offlineDot = 0xFFFF9AA2,
  hues = listOf(0xFFE85A68, 0xFF3BB4D8, 0xFFA78BFA, 0xFF63C98C, 0xFFF5A25D, 0xFF5CC4E4, 0xFFF2707C, 0xFFE9C46A),
)

/** Midnight: deep indigo and navy, cyan to violet. */
private val MIDNIGHT_LIGHT = ThemeColors(
  dark = false,
  background = 0xFFF1F2FA, panel = 0xFFFFFFFF, panelAlt = 0xFFF5F6FC, border = 0xFFD9DCEE, text = 0xFF12143A,
  muted = 0xFF555A85, brandStart = 0xFF0891B2, brandEnd = 0xFF5B3FD9, onBrand = 0xFFFFFFFF, progression = 0xFF5B3FD9,
  useful = 0xFF0E7490, trap = 0xFFE03131, ok = 0xFF2B8A3E, warn = 0xFFE8590C, key = 0xFFB8860B, locked = 0xFF868686,
  barBackground = 0xFFE4E6F4, shadow = 0x1A12143A, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFF0891B2, 0xFF5B3FD9, 0xFF5B3FD9, 0xFF0E7490, 0xFF2B8A3E, 0xFFE8590C, 0xFFE03131, 0xFFB8860B),
)

private val MIDNIGHT_DARK = ThemeColors(
  dark = true,
  background = 0xFF070818, panel = 0xFF0F1126, panelAlt = 0xFF151833, border = 0xFF252A52, text = 0xFFE8EAFB,
  muted = 0xFF9499C7, brandStart = 0xFF22D3EE, brandEnd = 0xFF8B7BFF, onBrand = 0xFF070818, progression = 0xFFB3A6FF,
  useful = 0xFF38BDF8, trap = 0xFFFF6B6B, ok = 0xFF51CF66, warn = 0xFFFF922B, key = 0xFFFCC419, locked = 0xFF9E9E9E,
  barBackground = 0xFF252A52, shadow = 0x66000000, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFF22D3EE, 0xFF8B7BFF, 0xFFB3A6FF, 0xFF38BDF8, 0xFF51CF66, 0xFFFF922B, 0xFFFF6B6B, 0xFFFCC419),
)

/** Sakura: soft pink and plum, rose. */
private val SAKURA_LIGHT = ThemeColors(
  dark = false,
  background = 0xFFFBF3F6, panel = 0xFFFFFFFF, panelAlt = 0xFFFDF7F9, border = 0xFFF0DCE4, text = 0xFF2E1522,
  muted = 0xFF7A5466, brandStart = 0xFFF06595, brandEnd = 0xFFC2255C, onBrand = 0xFFFFFFFF, progression = 0xFF6741D9,
  useful = 0xFF1971C2, trap = 0xFFD9480F, ok = 0xFF2B8A3E, warn = 0xFFB45309, key = 0xFF8D6E00, locked = 0xFF868686,
  barBackground = 0xFFF5E6EC, shadow = 0x1A2E1522, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFFF06595, 0xFFC2255C, 0xFF6741D9, 0xFF1971C2, 0xFF2B8A3E, 0xFFB45309, 0xFFD9480F, 0xFF8D6E00),
)

private val SAKURA_DARK = ThemeColors(
  dark = true,
  background = 0xFF160B11, panel = 0xFF21121A, panelAlt = 0xFF2A1822, border = 0xFF402534, text = 0xFFFBEAF1,
  muted = 0xFFC49BAE, brandStart = 0xFFFFA8C5, brandEnd = 0xFFF783AC, onBrand = 0xFF160B11, progression = 0xFFA78BFA,
  useful = 0xFF4DABF7, trap = 0xFFFF8A5C, ok = 0xFF51CF66, warn = 0xFFE8A33D, key = 0xFFFFE066, locked = 0xFF9E9E9E,
  barBackground = 0xFF402534, shadow = 0x66000000, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFFFFA8C5, 0xFFF783AC, 0xFFA78BFA, 0xFF4DABF7, 0xFF51CF66, 0xFFE8A33D, 0xFFFF8A5C, 0xFFFFE066),
)

/** Terminal: green phosphor on near-black; in the light, ink on pale green. */
private val TERMINAL_LIGHT = ThemeColors(
  dark = false,
  background = 0xFFEEF5EC, panel = 0xFFFAFDF9, panelAlt = 0xFFF2F8F0, border = 0xFFD3E2CF, text = 0xFF0E2412,
  muted = 0xFF4A6350, brandStart = 0xFF2F9E44, brandEnd = 0xFF1B6E2B, onBrand = 0xFFFFFFFF, progression = 0xFF6741D9,
  useful = 0xFF1971C2, trap = 0xFFE03131, ok = 0xFF1B6E2B, warn = 0xFFE8590C, key = 0xFFB8860B, locked = 0xFF868686,
  barBackground = 0xFFE0ECDD, shadow = 0x1A0E2412, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFF2F9E44, 0xFF1B6E2B, 0xFF6741D9, 0xFF1971C2, 0xFF1B6E2B, 0xFFE8590C, 0xFFE03131, 0xFFB8860B),
)

private val TERMINAL_DARK = ThemeColors(
  dark = true,
  background = 0xFF030803, panel = 0xFF091209, panelAlt = 0xFF0E190E, border = 0xFF1C2E1C, text = 0xFFD6F5D0,
  muted = 0xFF7FB07A, brandStart = 0xFF69DB7C, brandEnd = 0xFF40C057, onBrand = 0xFF030803, progression = 0xFFA78BFA,
  useful = 0xFF4DABF7, trap = 0xFFFF6B6B, ok = 0xFF8CE99A, warn = 0xFFFF922B, key = 0xFFFCC419, locked = 0xFF9E9E9E,
  barBackground = 0xFF1C2E1C, shadow = 0x66000000, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFF69DB7C, 0xFF40C057, 0xFFA78BFA, 0xFF4DABF7, 0xFF8CE99A, 0xFFFF922B, 0xFFFF6B6B, 0xFFFCC419),
)

/** Sepia: warm paper, brown ink, amber. */
private val SEPIA_LIGHT = ThemeColors(
  dark = false,
  background = 0xFFF6F0E4, panel = 0xFFFCF8EF, panelAlt = 0xFFF8F3E8, border = 0xFFE4D9C3, text = 0xFF2B2014,
  muted = 0xFF6B5B45, brandStart = 0xFFD9822B, brandEnd = 0xFF9A5B13, onBrand = 0xFFFFFFFF, progression = 0xFF6741D9,
  useful = 0xFF1971C2, trap = 0xFFE03131, ok = 0xFF2B8A3E, warn = 0xFFD9480F, key = 0xFFA67C00, locked = 0xFF8C8273,
  barBackground = 0xFFECE3D1, shadow = 0x1A2B2014, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFFD9822B, 0xFF9A5B13, 0xFF6741D9, 0xFF1971C2, 0xFF2B8A3E, 0xFFD9480F, 0xFFE03131, 0xFFA67C00),
)

private val SEPIA_DARK = ThemeColors(
  dark = true,
  background = 0xFF14100A, panel = 0xFF1E1810, panelAlt = 0xFF261F15, border = 0xFF3A3022, text = 0xFFF3E9D8,
  muted = 0xFFB5A48A, brandStart = 0xFFF0A04B, brandEnd = 0xFFD9822B, onBrand = 0xFF14100A, progression = 0xFFA78BFA,
  useful = 0xFF4DABF7, trap = 0xFFFF6B6B, ok = 0xFF51CF66, warn = 0xFFFF922B, key = 0xFFFCC419, locked = 0xFFA8997F,
  barBackground = 0xFF3A3022, shadow = 0x66000000, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFFF0A04B, 0xFFD9822B, 0xFFA78BFA, 0xFF4DABF7, 0xFF51CF66, 0xFFFF922B, 0xFFFF6B6B, 0xFFFCC419),
)

/** NES: its grey with the red of its letters. */
private val NES_LIGHT = ThemeColors(
  dark = false,
  background = 0xFFF1F0EE, panel = 0xFFFFFFFF, panelAlt = 0xFFF6F5F3, border = 0xFFDDDAD6, text = 0xFF1E1C1C,
  muted = 0xFF5F5B5B, brandStart = 0xFFE4000F, brandEnd = 0xFFB3000C, onBrand = 0xFFFFFFFF, progression = 0xFF6741D9,
  useful = 0xFF1971C2, trap = 0xFFC2255C, ok = 0xFF2B8A3E, warn = 0xFFE8590C, key = 0xFFB8860B, locked = 0xFF868686,
  barBackground = 0xFFE7E5E2, shadow = 0x1A1E1C1C, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFFE4000F, 0xFFB3000C, 0xFF6741D9, 0xFF1971C2, 0xFF2B8A3E, 0xFFE8590C, 0xFFC2255C, 0xFFB8860B),
)

private val NES_DARK = ThemeColors(
  dark = true,
  background = 0xFF121111, panel = 0xFF1C1B1B, panelAlt = 0xFF232222, border = 0xFF333131, text = 0xFFF1EFEF,
  muted = 0xFFA8A3A3, brandStart = 0xFFE4000F, brandEnd = 0xFFFF4D4D, onBrand = 0xFFFFFFFF, progression = 0xFFA78BFA,
  useful = 0xFF4DABF7, trap = 0xFFF783AC, ok = 0xFF51CF66, warn = 0xFFFF922B, key = 0xFFFCC419, locked = 0xFF9E9E9E,
  barBackground = 0xFF333131, shadow = 0x66000000, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFFE4000F, 0xFFFF4D4D, 0xFFA78BFA, 0xFF4DABF7, 0xFF51CF66, 0xFFFF922B, 0xFFF783AC, 0xFFFCC419),
)

/** SNES: its grey with lavender and purple, the four button colours. */
private val SNES_LIGHT = ThemeColors(
  dark = false,
  background = 0xFFF0EFF4, panel = 0xFFFFFFFF, panelAlt = 0xFFF5F4F9, border = 0xFFDAD8E3, text = 0xFF1F1D2B,
  muted = 0xFF5E5B70, brandStart = 0xFF7B6FC4, brandEnd = 0xFF4F43AE, onBrand = 0xFFFFFFFF, progression = 0xFF7B3FE4,
  useful = 0xFF1F5FD0, trap = 0xFFD62839, ok = 0xFF1E8F4E, warn = 0xFFE8590C, key = 0xFFB8860B, locked = 0xFF868686,
  barBackground = 0xFFE6E4EE, shadow = 0x1A1F1D2B, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFF7B6FC4, 0xFF4F43AE, 0xFF7B3FE4, 0xFF1F5FD0, 0xFF1E8F4E, 0xFFE8590C, 0xFFD62839, 0xFFB8860B),
)

private val SNES_DARK = ThemeColors(
  dark = true,
  background = 0xFF100F16, panel = 0xFF1A1924, panelAlt = 0xFF21202D, border = 0xFF302E40, text = 0xFFEEEDF6,
  muted = 0xFFA19EB8, brandStart = 0xFFB5ABEF, brandEnd = 0xFF8C7FE0, onBrand = 0xFF100F16, progression = 0xFFC4A7FF,
  useful = 0xFF5C9DFF, trap = 0xFFFF6B7A, ok = 0xFF4FD08A, warn = 0xFFFF922B, key = 0xFFFFD43B, locked = 0xFF9E9E9E,
  barBackground = 0xFF302E40, shadow = 0x66000000, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFFB5ABEF, 0xFF8C7FE0, 0xFFC4A7FF, 0xFF5C9DFF, 0xFF4FD08A, 0xFFFF922B, 0xFFFF6B7A, 0xFFFFD43B),
)

/** PlayStation: its grey with the logo's red, yellow, green and blue. */
private val PS1_LIGHT = ThemeColors(
  dark = false,
  background = 0xFFEFEFEF, panel = 0xFFFAFAFA, panelAlt = 0xFFF3F3F3, border = 0xFFD8D8D8, text = 0xFF1A1A1A,
  muted = 0xFF5A5A5A, brandStart = 0xFFDF0024, brandEnd = 0xFF0C4DA2, onBrand = 0xFFFFFFFF, progression = 0xFF6741D9,
  useful = 0xFF0C4DA2, trap = 0xFFDF0024, ok = 0xFF00897B, warn = 0xFFE8590C, key = 0xFFB58900, locked = 0xFF868686,
  barBackground = 0xFFE4E4E4, shadow = 0x1A1A1A1A, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFFDF0024, 0xFF0C4DA2, 0xFF6741D9, 0xFF0C4DA2, 0xFF00897B, 0xFFE8590C, 0xFFDF0024, 0xFFB58900),
)

private val PS1_DARK = ThemeColors(
  dark = true,
  background = 0xFF111213, panel = 0xFF1B1C1E, panelAlt = 0xFF222426, border = 0xFF323437, text = 0xFFEDEEEF,
  muted = 0xFFA2A5A9, brandStart = 0xFFF0364F, brandEnd = 0xFF4F8FE6, onBrand = 0xFFFFFFFF, progression = 0xFFA78BFA,
  useful = 0xFF5C9DED, trap = 0xFFFF6B81, ok = 0xFF2CC9B5, warn = 0xFFFF922B, key = 0xFFF3C623, locked = 0xFF9E9E9E,
  barBackground = 0xFF323437, shadow = 0x66000000, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFFF0364F, 0xFF4F8FE6, 0xFFA78BFA, 0xFF5C9DED, 0xFF2CC9B5, 0xFFFF922B, 0xFFFF6B81, 0xFFF3C623),
)

/** PlayStation 2: black with its deep blue. */
private val PS2_LIGHT = ThemeColors(
  dark = false,
  background = 0xFFEEF1F7, panel = 0xFFFFFFFF, panelAlt = 0xFFF4F6FB, border = 0xFFD5DBE8, text = 0xFF0E1730,
  muted = 0xFF4F5B78, brandStart = 0xFF3B5BDB, brandEnd = 0xFF00247D, onBrand = 0xFFFFFFFF, progression = 0xFF6741D9,
  useful = 0xFF0B7285, trap = 0xFFE03131, ok = 0xFF2B8A3E, warn = 0xFFE8590C, key = 0xFFB8860B, locked = 0xFF868686,
  barBackground = 0xFFE2E7F1, shadow = 0x1A0E1730, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFF3B5BDB, 0xFF00247D, 0xFF6741D9, 0xFF0B7285, 0xFF2B8A3E, 0xFFE8590C, 0xFFE03131, 0xFFB8860B),
)

private val PS2_DARK = ThemeColors(
  dark = true,
  background = 0xFF05070D, panel = 0xFF0D111C, panelAlt = 0xFF131827, border = 0xFF222A40, text = 0xFFE6EBF7,
  muted = 0xFF93A0BF, brandStart = 0xFF3B5BDB, brandEnd = 0xFF5C7CFA, onBrand = 0xFFFFFFFF, progression = 0xFFA78BFA,
  useful = 0xFF3BC9DB, trap = 0xFFFF6B6B, ok = 0xFF51CF66, warn = 0xFFFF922B, key = 0xFFFCC419, locked = 0xFF9E9E9E,
  barBackground = 0xFF222A40, shadow = 0x66000000, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFF3B5BDB, 0xFF5C7CFA, 0xFFA78BFA, 0xFF3BC9DB, 0xFF51CF66, 0xFFFF922B, 0xFFFF6B6B, 0xFFFCC419),
)

/** PlayStation 3: black and silver with its red. */
private val PS3_LIGHT = ThemeColors(
  dark = false,
  background = 0xFFF2F2F3, panel = 0xFFFFFFFF, panelAlt = 0xFFF6F6F7, border = 0xFFDCDCDF, text = 0xFF141416,
  muted = 0xFF58585E, brandStart = 0xFF8E8E96, brandEnd = 0xFFC8102E, onBrand = 0xFFFFFFFF, progression = 0xFF6741D9,
  useful = 0xFF1971C2, trap = 0xFFC8102E, ok = 0xFF2B8A3E, warn = 0xFFE8590C, key = 0xFFB8860B, locked = 0xFF8A8A93,
  barBackground = 0xFFE8E8EA, shadow = 0x1A141416, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFF8E8E96, 0xFFC8102E, 0xFF6741D9, 0xFF1971C2, 0xFF2B8A3E, 0xFFE8590C, 0xFFC8102E, 0xFFB8860B),
)

private val PS3_DARK = ThemeColors(
  dark = true,
  background = 0xFF0B0B0C, panel = 0xFF151517, panelAlt = 0xFF1C1C1F, border = 0xFF2C2C30, text = 0xFFF0F0F2,
  muted = 0xFFA4A4AC, brandStart = 0xFF6E6E78, brandEnd = 0xFFF0465C, onBrand = 0xFFFFFFFF, progression = 0xFFA78BFA,
  useful = 0xFF4DABF7, trap = 0xFFFF6B81, ok = 0xFF51CF66, warn = 0xFFFF922B, key = 0xFFFCC419, locked = 0xFF9E9E9E,
  barBackground = 0xFF2C2C30, shadow = 0x66000000, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFF6E6E78, 0xFFF0465C, 0xFFA78BFA, 0xFF4DABF7, 0xFF51CF66, 0xFFFF922B, 0xFFFF6B81, 0xFFFCC419),
)

/** PlayStation 4: black with its blue. */
private val PS4_LIGHT = ThemeColors(
  dark = false,
  background = 0xFFEFF3F8, panel = 0xFFFFFFFF, panelAlt = 0xFFF4F7FB, border = 0xFFD6DEE8, text = 0xFF0F1B2D,
  muted = 0xFF52607A, brandStart = 0xFF0070D1, brandEnd = 0xFF003791, onBrand = 0xFFFFFFFF, progression = 0xFF6741D9,
  useful = 0xFF0070D1, trap = 0xFFE03131, ok = 0xFF2B8A3E, warn = 0xFFE8590C, key = 0xFFB8860B, locked = 0xFF868686,
  barBackground = 0xFFE3E9F1, shadow = 0x1A0F1B2D, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFF0070D1, 0xFF003791, 0xFF6741D9, 0xFF0070D1, 0xFF2B8A3E, 0xFFE8590C, 0xFFE03131, 0xFFB8860B),
)

private val PS4_DARK = ThemeColors(
  dark = true,
  background = 0xFF0A0E14, panel = 0xFF121821, panelAlt = 0xFF18202B, border = 0xFF263242, text = 0xFFE8EEF6,
  muted = 0xFF97A6BA, brandStart = 0xFF0070D1, brandEnd = 0xFF2E8FE8, onBrand = 0xFFFFFFFF, progression = 0xFFA78BFA,
  useful = 0xFF5CB3FF, trap = 0xFFFF6B6B, ok = 0xFF51CF66, warn = 0xFFFF922B, key = 0xFFFCC419, locked = 0xFF9E9E9E,
  barBackground = 0xFF263242, shadow = 0x66000000, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFF0070D1, 0xFF2E8FE8, 0xFFA78BFA, 0xFF5CB3FF, 0xFF51CF66, 0xFFFF922B, 0xFFFF6B6B, 0xFFFCC419),
)

/** PlayStation 5: white and black with its blue. */
private val PS5_LIGHT = ThemeColors(
  dark = false,
  background = 0xFFF4F5F7, panel = 0xFFFFFFFF, panelAlt = 0xFFF7F8FA, border = 0xFFDEE1E6, text = 0xFF0B0C0F,
  muted = 0xFF555A63, brandStart = 0xFF101114, brandEnd = 0xFF0064D2, onBrand = 0xFFFFFFFF, progression = 0xFF6741D9,
  useful = 0xFF0064D2, trap = 0xFFE03131, ok = 0xFF2B8A3E, warn = 0xFFE8590C, key = 0xFFB8860B, locked = 0xFF868686,
  barBackground = 0xFFE9EBEF, shadow = 0x1A0B0C0F, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFF101114, 0xFF0064D2, 0xFF6741D9, 0xFF0064D2, 0xFF2B8A3E, 0xFFE8590C, 0xFFE03131, 0xFFB8860B),
)

private val PS5_DARK = ThemeColors(
  dark = true,
  background = 0xFF08090B, panel = 0xFF121317, panelAlt = 0xFF191B20, border = 0xFF2A2D34, text = 0xFFF5F6F8,
  muted = 0xFFA3A8B3, brandStart = 0xFFE9ECF2, brandEnd = 0xFF8FC2FF, onBrand = 0xFF08090B, progression = 0xFFA78BFA,
  useful = 0xFF5CAEFF, trap = 0xFFFF6B6B, ok = 0xFF51CF66, warn = 0xFFFF922B, key = 0xFFFCC419, locked = 0xFF9E9E9E,
  barBackground = 0xFF2A2D34, shadow = 0x66000000, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFFE9ECF2, 0xFF8FC2FF, 0xFFA78BFA, 0xFF5CAEFF, 0xFF51CF66, 0xFFFF922B, 0xFFFF6B6B, 0xFFFCC419),
)

/** Xbox: black with its acid green. */
private val XBOX_LIGHT = ThemeColors(
  dark = false,
  background = 0xFFF1F4EE, panel = 0xFFFFFFFF, panelAlt = 0xFFF6F9F3, border = 0xFFDAE2D2, text = 0xFF141A10,
  muted = 0xFF566050, brandStart = 0xFF5DC21E, brandEnd = 0xFF2A7F0C, onBrand = 0xFF0E1A05, progression = 0xFF6741D9,
  useful = 0xFF1971C2, trap = 0xFFE03131, ok = 0xFF2E8B0E, warn = 0xFFE8590C, key = 0xFFB8860B, locked = 0xFF868686,
  barBackground = 0xFFE5EBDF, shadow = 0x1A141A10, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFF5DC21E, 0xFF2A7F0C, 0xFF6741D9, 0xFF1971C2, 0xFF2E8B0E, 0xFFE8590C, 0xFFE03131, 0xFFB8860B),
)

private val XBOX_DARK = ThemeColors(
  dark = true,
  background = 0xFF070A05, panel = 0xFF10150C, panelAlt = 0xFF161D11, border = 0xFF263120, text = 0xFFECF5E4,
  muted = 0xFF9DAE90, brandStart = 0xFFC6FF4D, brandEnd = 0xFF9BF00B, onBrand = 0xFF0A1204, progression = 0xFFA78BFA,
  useful = 0xFF4DABF7, trap = 0xFFFF6B6B, ok = 0xFF9BF00B, warn = 0xFFFF922B, key = 0xFFFCC419, locked = 0xFF9E9E9E,
  barBackground = 0xFF263120, shadow = 0x66000000, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFFC6FF4D, 0xFF9BF00B, 0xFFA78BFA, 0xFF4DABF7, 0xFF9BF00B, 0xFFFF922B, 0xFFFF6B6B, 0xFFFCC419),
)

/** Xbox 360: white and silver with its green. */
private val XBOX360_LIGHT = ThemeColors(
  dark = false,
  background = 0xFFF3F5F4, panel = 0xFFFFFFFF, panelAlt = 0xFFF7F9F8, border = 0xFFDDE2DF, text = 0xFF181C1A,
  muted = 0xFF59615D, brandStart = 0xFF6DB02F, brandEnd = 0xFF107C10, onBrand = 0xFFFFFFFF, progression = 0xFF6741D9,
  useful = 0xFF1971C2, trap = 0xFFE03131, ok = 0xFF107C10, warn = 0xFFE8590C, key = 0xFFB8860B, locked = 0xFF868686,
  barBackground = 0xFFE8ECEA, shadow = 0x1A181C1A, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFF6DB02F, 0xFF107C10, 0xFF6741D9, 0xFF1971C2, 0xFF107C10, 0xFFE8590C, 0xFFE03131, 0xFFB8860B),
)

private val XBOX360_DARK = ThemeColors(
  dark = true,
  background = 0xFF0E100F, panel = 0xFF181B19, panelAlt = 0xFF1F2321, border = 0xFF2F3532, text = 0xFFEEF2F0,
  muted = 0xFFA1ABA6, brandStart = 0xFF9BD45A, brandEnd = 0xFF6CBF2C, onBrand = 0xFF0E100F, progression = 0xFFA78BFA,
  useful = 0xFF4DABF7, trap = 0xFFFF6B6B, ok = 0xFF6CBF2C, warn = 0xFFFF922B, key = 0xFFFCC419, locked = 0xFF9E9E9E,
  barBackground = 0xFF2F3532, shadow = 0x66000000, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFF9BD45A, 0xFF6CBF2C, 0xFFA78BFA, 0xFF4DABF7, 0xFF6CBF2C, 0xFFFF922B, 0xFFFF6B6B, 0xFFFCC419),
)

/** Xbox Series X|S: black with its green. */
private val XBOX_SERIES_LIGHT = ThemeColors(
  dark = false,
  background = 0xFFF2F2F2, panel = 0xFFFFFFFF, panelAlt = 0xFFF7F7F7, border = 0xFFDCDCDC, text = 0xFF101010,
  muted = 0xFF575757, brandStart = 0xFF107C10, brandEnd = 0xFF0B5A0B, onBrand = 0xFFFFFFFF, progression = 0xFF6741D9,
  useful = 0xFF1971C2, trap = 0xFFE03131, ok = 0xFF107C10, warn = 0xFFE8590C, key = 0xFFB8860B, locked = 0xFF868686,
  barBackground = 0xFFE8E8E8, shadow = 0x1A101010, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFF107C10, 0xFF0B5A0B, 0xFF6741D9, 0xFF1971C2, 0xFF107C10, 0xFFE8590C, 0xFFE03131, 0xFFB8860B),
)

private val XBOX_SERIES_DARK = ThemeColors(
  dark = true,
  background = 0xFF0A0A0A, panel = 0xFF141414, panelAlt = 0xFF1B1B1B, border = 0xFF2B2B2B, text = 0xFFF3F3F3,
  muted = 0xFFA6A6A6, brandStart = 0xFF5BE05B, brandEnd = 0xFF2CB32C, onBrand = 0xFF0A0A0A, progression = 0xFFA78BFA,
  useful = 0xFF4DABF7, trap = 0xFFFF6B6B, ok = 0xFF3DD13D, warn = 0xFFFF922B, key = 0xFFFCC419, locked = 0xFF9E9E9E,
  barBackground = 0xFF2B2B2B, shadow = 0x66000000, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFF5BE05B, 0xFF2CB32C, 0xFFA78BFA, 0xFF4DABF7, 0xFF3DD13D, 0xFFFF922B, 0xFFFF6B6B, 0xFFFCC419),
)

/** SteamOS: its dark blue-grey with the light blue. */
private val STEAMOS_LIGHT = ThemeColors(
  dark = false,
  background = 0xFFEEF2F6, panel = 0xFFFFFFFF, panelAlt = 0xFFF4F7FA, border = 0xFFD5DEE7, text = 0xFF16202D,
  muted = 0xFF4F6074, brandStart = 0xFF1A9FFF, brandEnd = 0xFF1B6FB5, onBrand = 0xFFFFFFFF, progression = 0xFF6741D9,
  useful = 0xFF1B6FB5, trap = 0xFFE03131, ok = 0xFF2B8A3E, warn = 0xFFE8590C, key = 0xFFB8860B, locked = 0xFF868686,
  barBackground = 0xFFE2E9F0, shadow = 0x1A16202D, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFF1A9FFF, 0xFF1B6FB5, 0xFF6741D9, 0xFF1B6FB5, 0xFF2B8A3E, 0xFFE8590C, 0xFFE03131, 0xFFB8860B),
)

private val STEAMOS_DARK = ThemeColors(
  dark = true,
  background = 0xFF171A21, panel = 0xFF1B2838, panelAlt = 0xFF223247, border = 0xFF2A475E, text = 0xFFE3EDF5,
  muted = 0xFF9DB2C4, brandStart = 0xFF1A9FFF, brandEnd = 0xFF66C0F4, onBrand = 0xFF0B141E, progression = 0xFFA78BFA,
  useful = 0xFF66C0F4, trap = 0xFFFF6B6B, ok = 0xFF51CF66, warn = 0xFFFF922B, key = 0xFFFCC419, locked = 0xFF9E9E9E,
  barBackground = 0xFF2A475E, shadow = 0x66000000, liveDot = 0xFF6CF0C9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFF1A9FFF, 0xFF66C0F4, 0xFFA78BFA, 0xFF66C0F4, 0xFF51CF66, 0xFFFF922B, 0xFFFF6B6B, 0xFFFCC419),
)
