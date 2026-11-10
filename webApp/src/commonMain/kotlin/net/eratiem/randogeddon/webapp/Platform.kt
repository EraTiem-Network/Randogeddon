package net.eratiem.randogeddon.webapp

/** What the dashboard needs from the platform; the browser implementation is in wasmJsMain. */
expect object Platform {
  fun now(): Long

  /** Wall-clock time of [epochMillis] as `HH:mm` in the viewer's time zone. */
  fun clock(epochMillis: Long): String

  /** GET [path] relative to the page; the body, or `null` on any error. */
  suspend fun get(path: String): String?

  /**
   * Opens the live stream at [path] and calls [onState] with the data of every `state` event. Reconnects by itself;
   * [onConnection] tells whether the stream is currently open. Returns a function that closes it.
   */
  fun stream(path: String, onState: (String) -> Unit, onConnection: (Boolean) -> Unit): () -> Unit

  fun systemDarkMode(): Boolean

  /** Per-viewer settings; missing (or blocked storage) is `null`. */
  fun loadSetting(key: String): String?
  fun saveSetting(key: String, value: String?)
}
