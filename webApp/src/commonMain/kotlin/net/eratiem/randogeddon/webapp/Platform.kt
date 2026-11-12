package net.eratiem.randogeddon.webapp

/** An HTTP answer: its status code and body. */
data class HttpResult(val status: Int, val body: String)

/** What the dashboard needs from the platform; the browser implementation is in wasmJsMain. */
expect object Platform {
  fun now(): Long

  /** Wall-clock time of [epochMillis] as `HH:mm` in the viewer's time zone. */
  fun clock(epochMillis: Long): String

  /** Date and time of [epochMillis] in the viewer's locale and time zone, e.g. `13 Nov 2026, 20:15`. */
  fun date(epochMillis: Long): String

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
   * Lets the user pick files (e.g. `.yaml,.yml` for [accept]); [onPicked] gets them as a JSON array of
   * `{"fileName", "content"}` (nothing if the dialog is cancelled). The content is the text, or base64 with [binary].
   */
  fun pickFiles(accept: String, binary: Boolean = false, onPicked: (String) -> Unit)

  /** Puts [text] on the clipboard. */
  fun copy(text: String)

  fun systemDarkMode(): Boolean

  /** The main pointer is a finger (a phone or tablet), not a mouse. */
  fun touchScreen(): Boolean

  /** The URL's hash, e.g. `#/admin`; empty without one. */
  fun hash(): String

  /** Goes to [hash]: a new history entry (back returns), or in place of the current one with [replace]. */
  fun setHash(hash: String, replace: Boolean)

  /** Calls [onChange] with the new hash whenever it changes (back, forward, a link); returns a function that stops it. */
  fun onHashChange(onChange: (String) -> Unit): () -> Unit

  /** Per-viewer settings; missing (or blocked storage) is `null`. */
  fun loadSetting(key: String): String?
  fun saveSetting(key: String, value: String?)
}
