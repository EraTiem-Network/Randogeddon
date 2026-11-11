package net.eratiem.randogeddon.webapp

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

actual object Platform {
  actual fun now(): Long = jsNow().toLong()

  actual fun clock(epochMillis: Long): String = jsClock(epochMillis.toDouble())

  actual suspend fun request(method: String, path: String, body: String?): HttpResult? = suspendCancellableCoroutine { cont ->
    jsFetch(
      method, path, body,
      { status, text -> if (cont.isActive) cont.resume(HttpResult(status, text)) },
      { if (cont.isActive) cont.resume(null) },
    )
  }

  actual fun stream(path: String, onState: (String) -> Unit, onConnection: (Boolean) -> Unit): () -> Unit {
    val source = jsOpenStream(path, onState, { onConnection(true) }, { onConnection(false) })
    return { jsCloseStream(source) }
  }

  actual fun events(
    path: String,
    events: List<String>,
    onEvent: (String, String) -> Unit,
    onConnection: (Boolean) -> Unit,
  ): () -> Unit {
    val source = jsOpenEvents(path, events.joinToString(","), onEvent, { onConnection(true) }, { onConnection(false) })
    return { jsCloseStream(source) }
  }

  actual fun showLogin(message: String?, onLoggedIn: () -> Unit) = jsShowLogin(message, onLoggedIn)

  actual fun hideLogin() = jsHideLogin()

  actual fun copy(text: String) = jsCopy(text)

  actual fun systemDarkMode(): Boolean = jsDarkMode()

  actual fun loadSetting(key: String): String? = jsLoadSetting(key)

  actual fun saveSetting(key: String, value: String?) = jsSaveSetting(key, value)
}

private fun jsNow(): Double = js("Date.now()")

private fun jsClock(ms: Double): String =
  js("new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })")

// Same-origin requests carry the session cookie by default
private fun jsFetch(method: String, url: String, body: String?, ok: (Int, String) -> Unit, fail: () -> Unit): Unit = js(
  """{ const init = { method: method, cache: 'no-store' };
       if (body !== null) { init.body = body; init.headers = { 'Content-Type': 'application/json' }; }
       fetch(url, init)
         .then(r => r.text().then(t => ok(r.status, t)), () => fail()); }"""
)

// The form and its script are in index.html (window.randogeddonLogin)
private fun jsShowLogin(message: String?, onLoggedIn: () -> Unit): Unit =
  js("window.randogeddonLogin.show(message, onLoggedIn)")

private fun jsHideLogin(): Unit = js("window.randogeddonLogin.hide()")

private fun jsCopy(text: String): Unit = js("{ try { navigator.clipboard.writeText(text); } catch (e) {} }")

private fun jsOpenStream(url: String, onState: (String) -> Unit, onOpen: () -> Unit, onError: () -> Unit): JsAny = js(
  """{ const es = new EventSource(url);
       es.addEventListener('state', e => onState(e.data));
       es.onopen = () => onOpen();
       es.onerror = () => onError();
       return es; }"""
)

private fun jsOpenEvents(
  url: String,
  events: String,
  onEvent: (String, String) -> Unit,
  onOpen: () -> Unit,
  onError: () -> Unit,
): JsAny = js(
  """{ const es = new EventSource(url);
       events.split(',').forEach(name => es.addEventListener(name, e => onEvent(name, e.data)));
       es.onopen = () => onOpen();
       es.onerror = () => onError();
       return es; }"""
)

private fun jsCloseStream(source: JsAny): Unit = js("source.close()")

private fun jsDarkMode(): Boolean = js("window.matchMedia('(prefers-color-scheme: dark)').matches")

// Storage can be blocked (private mode, disabled site data); the dashboard works without it
private fun jsLoadSetting(key: String): String? =
  js("{ try { return localStorage.getItem(key); } catch (e) { return null; } }")

private fun jsSaveSetting(key: String, value: String?): Unit = js(
  """{ try { if (value === null) localStorage.removeItem(key); else localStorage.setItem(key, value); } catch (e) {} }"""
)
