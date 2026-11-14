package net.eratiem.randogeddon.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A look of the app: a light and a dark [ThemeColors] each ([palettes]). Stored by [code]. */
@Serializable
enum class Theme(val code: String) {
  @SerialName("randogeddon") RANDOGEDDON("randogeddon"),
  @SerialName("ocean") OCEAN("ocean"),
  @SerialName("forest") FOREST("forest"),
  @SerialName("ember") EMBER("ember"),
  @SerialName("contrast") CONTRAST("contrast"),
  // Inspired by Nintendo's consoles: their colours only
  @SerialName("n64") N64("n64"),
  @SerialName("gamecube") GAMECUBE("gamecube"),
  @SerialName("wii") WII("wii"),
  @SerialName("wiiu") WIIU("wiiu"),
  @SerialName("switch") SWITCH("switch"),
  @SerialName("switch2") SWITCH2("switch2"),
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
  Theme.N64 -> N64_LIGHT to N64_DARK
  Theme.GAMECUBE -> GAMECUBE_LIGHT to GAMECUBE_DARK
  Theme.WII -> WII_LIGHT to WII_DARK
  Theme.WIIU -> WIIU_LIGHT to WIIU_DARK
  Theme.SWITCH -> SWITCH_LIGHT to SWITCH_DARK
  Theme.SWITCH2 -> SWITCH2_LIGHT to SWITCH2_DARK
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
  muted = 0xFF6E5A50, brandStart = 0xFFDC2626, brandEnd = 0xFFF59E0B, onBrand = 0xFF1F1206, progression = 0xFF7C3AED,
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
