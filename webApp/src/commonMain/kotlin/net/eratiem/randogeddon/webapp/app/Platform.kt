package net.eratiem.randogeddon.webapp.app

/** An HTTP answer: its status code and body. */
data class HttpResult(val status: Int, val body: String)

/** What the dashboard needs from the platform; the browser implementation is in wasmJsMain. */
expect object Platform {
  fun now(): Long

  /** Wall-clock time of [epochMillis] as `HH:mm` in the viewer's time zone, for the language [tag]. */
  fun clock(epochMillis: Long, tag: String): String

  /**
   * Date and time of [epochMillis] in the viewer's time zone, for the language [tag]: `13 Nov 2026, 20:15`, or with
   * [numeric] the month in digits (`13.11.2026, 20:15`).
   */
  fun date(epochMillis: Long, tag: String, numeric: Boolean): String

  /** The browser's first language (e.g. `de-DE`); `null` if it names none. */
  fun browserLanguage(): String?

  /** Tells the page (index.html's forms, `<html lang>`) the language the app is in now: its code. */
  fun setPageLanguage(code: String)

  /** [method] [path] relative to the page, with a JSON [body]; `null` if the server can't be reached. */
  suspend fun request(method: String, path: String, body: String? = null): HttpResult?

  /**
   * Opens the live stream at [path] and calls [onState] with the data of every `state` event. Reconnects by itself;
   * [onConnection] tells whether the stream is currently open. Returns a function that closes it.
   */
  fun stream(path: String, onState: (String) -> Unit, onConnection: (Boolean) -> Unit): () -> Unit

  /** Like [stream], for a stream with several event types: [onEvent] gets the type and the data of each of [events]. */
  fun events(
    path: String,
    events: List<String>,
    onEvent: (String, String) -> Unit,
    onConnection: (Boolean) -> Unit,
  ): () -> Unit

  /**
   * Shows the login form of index.html (a real HTML form, so password managers can save and fill it), with
   * [message] above it if given. [onLoggedIn] is called once the server accepted a login.
   */
  fun showLogin(message: String?, onLoggedIn: () -> Unit)

  fun hideLogin()

  /**
   * The password form of index.html (for the password manager), for the user [name]; it changes the password itself.
   * [onDone] gets whether it was changed (or cancelled).
   */
  fun showPasswordChange(name: String, onDone: (Boolean) -> Unit)

  /**
   * Lets the user pick files (e.g. `.yaml,.yml` for [accept]); [onPicked] gets them as a JSON array of
   * `{"fileName", "content"}` (nothing if the dialog is cancelled). The content is the text, or base64 with [binary].
   * The picker of the same name with a kind, without a size limit: a file that can't be read is left out silently.
   */
  fun pickFiles(accept: String, binary: Boolean = false, onPicked: (String) -> Unit)

  /**
   * Lets the user pick files of a [kind] (`yaml`, `apworld`, `seed`: letters, digits, `_` and `-`, at most 32): where
   * the browser can (`showOpenFilePicker`, Chromium's), the dialog opens in the folder that kind was last picked from,
   * each kind its own; elsewhere (Firefox, Safari) it is the plain file dialog, which opens where the browser likes.
   * [accept]: the endings offered (`.yaml,.yml`), a hint only: check what arrives. [multiple]: more than one at once.
   * Each file larger than [maxBytes] is left out unread, as is one the browser can't read: [onProblem] gets its name
   * and why, once per file. [onPicked] gets the others as a JSON array of `{"fileName", "content"}` (the text, or
   * base64 with [binary]); not at all if none is left or the dialog is cancelled.
   */
  fun pickFiles(
    kind: String,
    accept: String,
    binary: Boolean,
    maxBytes: Long,
    multiple: Boolean,
    onProblem: (fileName: String, problem: PickProblem) -> Unit,
    onPicked: (String) -> Unit,
  )

  /** Puts [text] on the clipboard. */
  fun copy(text: String, onDone: (Boolean) -> Unit = {})

  /** Opens [url] in a new tab; only a web address (`https://`, `http://`), anything else not at all. */
  fun openLink(url: String)

  /** The browser saves the file at [path] (an API path; the login cookie goes along, same origin). */
  fun download(path: String)

  fun systemDarkMode(): Boolean

  /** The browser's time zone by its name (`Europe/Berlin`); `null`: it doesn't say. */
  fun timeZone(): String?

  /** Calls [onChange] whenever the device switches between light and dark. */
  fun onSystemDarkModeChange(onChange: (Boolean) -> Unit)

  /** The page is shown: its tab is the one in front, and its window isn't minimised (`document.visibilityState`). */
  fun pageVisible(): Boolean

  /** Calls [onChange] whenever the page is shown or hidden ([pageVisible]). */
  fun onVisibilityChange(onChange: (Boolean) -> Unit)

  /**
   * Gives the page around the app the theme's colours: [json] has the mode and the light and dark colours of
   * index.html's login form; it keeps them for the next visit (before the app has loaded).
   */
  fun setPageTheme(json: String)

  /** The main pointer is a finger (a phone or tablet), not a mouse. */
  fun touchScreen(): Boolean

  /** The page's address without its hash, e.g. `https://randogeddon.eratiem.net/` (for links to share). */
  fun pageUrl(): String

  /** The URL's hash, e.g. `#/admin`; empty without one. */
  fun hash(): String

  /** Goes to [hash]: a new history entry (back returns), or in place of the current one with [replace]. */
  fun setHash(hash: String, replace: Boolean)

  /** Calls [onChange] with the new hash whenever it changes (back, forward, a link); returns a function that stops it. */
  fun onHashChange(onChange: (String) -> Unit): () -> Unit

  /** Takes the page to full screen, or back out of it. The browser allows the way in only from a click. */
  fun fullScreen(on: Boolean)

  /**
   * Calls [onChange] whenever the page enters or leaves full screen (Esc leaves it too, past the page); returns a
   * function that stops it.
   */
  fun onFullScreenChange(onChange: (Boolean) -> Unit): () -> Unit

  /** A static file of the app (a font) as bytes; `null` if it can't be loaded. */
  suspend fun bytes(path: String): ByteArray?

  /** A font of a file's [data] ([identity]: a name of its own, the same for the same data). */
  fun font(identity: String, data: ByteArray, bold: Boolean): androidx.compose.ui.text.font.Font

  /** Per-viewer settings; missing (or blocked storage) is `null`. */
  fun loadSetting(key: String): String?
  fun saveSetting(key: String, value: String?)
}

/** Why [Platform.pickFiles] left a picked file out. */
enum class PickProblem {
  /** Larger than the picker's limit (the tracker's for that kind of file). */
  TOO_LARGE,

  /** The browser couldn't read it (moved, deleted, no permission, a folder). */
  UNREADABLE;

  companion object {
    /** The picker's code for it (`too-large`, `unreadable`); an unknown one counts as unreadable. */
    fun of(code: String): PickProblem = if (code == "too-large") TOO_LARGE else UNREADABLE
  }
}

/**
 * The largest file each picker reads, as the tracker and its gateway take them (a larger one would be refused after
 * the whole upload): YAMLs go together in a request of at most 8 MB; an APWorld and a seed may each be 64 MB.
 */
object PickLimits {
  const val YAML_BYTES = 8L * 1024 * 1024
  const val APWORLD_BYTES = 64L * 1024 * 1024
  const val SEED_BYTES = 64L * 1024 * 1024
}
