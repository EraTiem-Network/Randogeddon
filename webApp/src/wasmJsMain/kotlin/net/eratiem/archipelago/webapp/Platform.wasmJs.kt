package net.eratiem.archipelago.webapp

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

actual object Platform {
  actual fun now(): Long = jsNow().toLong()

  actual fun clock(epochMillis: Long): String = jsClock(epochMillis.toDouble())

  actual suspend fun get(path: String): String? = suspendCancellableCoroutine { cont ->
    jsFetchText(path, { text -> if (cont.isActive) cont.resume(text) }, { if (cont.isActive) cont.resume(null) })
  }

  actual fun stream(path: String, onState: (String) -> Unit, onConnection: (Boolean) -> Unit): () -> Unit {
    val source = jsOpenStream(path, onState, { onConnection(true) }, { onConnection(false) })
    return { jsCloseStream(source) }
  }

  actual fun systemDarkMode(): Boolean = jsDarkMode()

  actual fun loadSetting(key: String): String? = jsLoadSetting(key)

  actual fun saveSetting(key: String, value: String?) = jsSaveSetting(key, value)
}

private fun jsNow(): Double = js("Date.now()")

private fun jsClock(ms: Double): String =
  js("new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })")

private fun jsFetchText(url: String, ok: (String) -> Unit, fail: () -> Unit): Unit = js(
  """{ fetch(url, { cache: 'no-store' })
         .then(r => r.ok ? r.text() : Promise.reject(r.status))
         .then(t => ok(t), () => fail()); }"""
)

private fun jsOpenStream(url: String, onState: (String) -> Unit, onOpen: () -> Unit, onError: () -> Unit): JsAny = js(
  """{ const es = new EventSource(url);
       es.addEventListener('state', e => onState(e.data));
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
