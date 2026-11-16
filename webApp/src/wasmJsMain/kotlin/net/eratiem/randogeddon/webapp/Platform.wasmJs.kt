package net.eratiem.randogeddon.webapp

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

actual object Platform {
  actual fun now(): Long = jsNow().toLong()

  actual fun clock(epochMillis: Long, tag: String): String = jsClock(epochMillis.toDouble(), tag)

  actual suspend fun request(method: String, path: String, body: String?): HttpResult? = suspendCancellableCoroutine { cont ->
    jsFetch(
      method, path, body,
      { status, text ->
        // An ended login shows the login form, whichever request noticed it
        LoginWatch.seen(status, text)
        if (cont.isActive) cont.resume(HttpResult(status, text))
      },
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

  actual fun showPasswordChange(name: String, onDone: (Boolean) -> Unit) = jsShowPasswordChange(name, onDone)

  actual fun date(epochMillis: Long, tag: String, numeric: Boolean): String = jsDate(epochMillis.toDouble(), tag, numeric)

  actual fun browserLanguage(): String? = jsBrowserLanguage()

  actual fun setPageLanguage(code: String) = jsSetPageLanguage(code)

  actual fun pickFiles(accept: String, binary: Boolean, onPicked: (String) -> Unit) = jsPickFiles(accept, binary, onPicked)

  actual fun copy(text: String) = jsCopy(text)

  actual fun openLink(url: String) = jsOpenLink(url)

  actual fun download(path: String) = jsDownload(path)

  actual fun systemDarkMode(): Boolean = jsDarkMode()

  actual fun onSystemDarkModeChange(onChange: (Boolean) -> Unit) = jsOnDarkModeChange(onChange)

  actual fun setPageTheme(json: String) = jsSetPageTheme(json)

  actual fun touchScreen(): Boolean = jsTouchScreen()

  actual fun pageUrl(): String = jsPageUrl()

  actual fun hash(): String = jsHash()

  actual fun setHash(hash: String, replace: Boolean) = jsSetHash(hash, replace)

  actual fun onHashChange(onChange: (String) -> Unit): () -> Unit {
    val listener = jsOnHashChange(onChange)
    return { jsOffHashChange(listener) }
  }

  actual fun loadSetting(key: String): String? = jsLoadSetting(key)

  actual fun saveSetting(key: String, value: String?) = jsSaveSetting(key, value)
}

private fun jsNow(): Double = js("Date.now()")

private fun jsDate(ms: Double, tag: String, numeric: Boolean): String = js(
  """new Date(ms).toLocaleString(tag, { day: numeric ? '2-digit' : 'numeric', month: numeric ? '2-digit' : 'short',
       year: 'numeric', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })"""
)

private fun jsClock(ms: Double, tag: String): String =
  js("new Date(ms).toLocaleTimeString(tag, { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })")

private fun jsBrowserLanguage(): String? =
  js("(navigator.languages && navigator.languages.length ? navigator.languages[0] : navigator.language) || null")

// index.html's forms say the same (window.randogeddonLogin.language), and <html lang> follows
private fun jsSetPageLanguage(code: String): Unit = js("window.randogeddonLogin.language(code)")

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

private fun jsShowPasswordChange(name: String, onDone: (Boolean) -> Unit): Unit =
  js("window.randogeddonLogin.password(name, onDone)")

// A hidden file input; every chosen file is read as text, or as base64 (from a data: URL)
private fun jsPickFiles(accept: String, binary: Boolean, onPicked: (String) -> Unit): Unit = js(
  """{ const input = document.createElement('input');
       input.type = 'file'; input.multiple = true; input.accept = accept;
       const read = f => binary
         ? new Promise(done => { const r = new FileReader();
             r.onload = () => done(r.result.substring(r.result.indexOf(',') + 1)); r.readAsDataURL(f); })
         : f.text();
       input.addEventListener('change', () => {
         Promise.all(Array.from(input.files).map(f => read(f).then(t => ({ fileName: f.name, content: t }))))
           .then(files => onPicked(JSON.stringify(files)));
       });
       input.click(); }"""
)

private fun jsCopy(text: String): Unit = js("{ try { navigator.clipboard.writeText(text); } catch (e) {} }")

private fun jsOpenLink(url: String): Unit = js("{ window.open(url, '_blank', 'noopener'); }")

// A link with `download`: the browser saves the answer (its Content-Disposition names the file) instead of opening it
private fun jsDownload(path: String): Unit =
  js("{ const a = document.createElement('a'); a.href = path; a.download = ''; document.body.appendChild(a); a.click(); a.remove(); }")

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

private fun jsOnDarkModeChange(onChange: (Boolean) -> Unit): Unit =
  js("window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', e => onChange(e.matches))")

// index.html applies it to its login form and keeps it (window.randogeddonTheme)
private fun jsSetPageTheme(json: String): Unit = js("window.randogeddonTheme(json)")

private fun jsTouchScreen(): Boolean = js("window.matchMedia('(pointer: coarse)').matches")

// Storage can be blocked (private mode, disabled site data); the dashboard works without it
private fun jsLoadSetting(key: String): String? =
  js("{ try { return localStorage.getItem(key); } catch (e) { return null; } }")

private fun jsSaveSetting(key: String, value: String?): Unit = js(
  """{ try { if (value === null) localStorage.removeItem(key); else localStorage.setItem(key, value); } catch (e) {} }"""
)

private fun jsHash(): String = js("window.location.hash")

private fun jsPageUrl(): String = js("window.location.origin + window.location.pathname")

// replaceState: no new history entry (and no hashchange event; the caller has the route already)
private fun jsSetHash(hash: String, replace: Boolean): Unit =
  js("{ if (replace) history.replaceState(null, '', hash); else if (window.location.hash !== hash) window.location.hash = hash; }")

private fun jsOnHashChange(onChange: (String) -> Unit): JsAny = js(
  """{ const listener = () => onChange(window.location.hash);
       window.addEventListener('hashchange', listener);
       return listener; }"""
)

private fun jsOffHashChange(listener: JsAny): Unit = js("window.removeEventListener('hashchange', listener)")
