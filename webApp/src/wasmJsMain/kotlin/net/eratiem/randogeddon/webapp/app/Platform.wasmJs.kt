package net.eratiem.randogeddon.webapp.app

import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import net.eratiem.randogeddon.webapp.api.LoginWatch

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

  actual suspend fun bytes(path: String): ByteArray? = suspendCancellableCoroutine { cont ->
    jsFetchBytes(
      path,
      { data -> if (cont.isActive) cont.resume(ByteArray(jsLength(data)) { jsByte(data, it) }) },
      { if (cont.isActive) cont.resume(null) },
    )
  }

  actual fun font(identity: String, data: ByteArray, bold: Boolean): androidx.compose.ui.text.font.Font =
    androidx.compose.ui.text.platform.Font(
      identity, data, if (bold) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
    )

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

  // Its own kind for each ending (`.apworld`: `apworld`), so it remembers its folder too
  actual fun pickFiles(accept: String, binary: Boolean, onPicked: (String) -> Unit) =
    pickFiles(accept.filter { it.isLetterOrDigit() }.take(32), accept, binary, Long.MAX_VALUE, true, { _, _ -> }, onPicked)

  actual fun pickFiles(
    kind: String,
    accept: String,
    binary: Boolean,
    maxBytes: Long,
    multiple: Boolean,
    onProblem: (fileName: String, problem: PickProblem) -> Unit,
    onPicked: (String) -> Unit,
  ) = jsPickFiles(
    kind, accept, binary, if (maxBytes == Long.MAX_VALUE) Double.POSITIVE_INFINITY else maxBytes.toDouble(), multiple,
    { name, code -> onProblem(name, PickProblem.of(code)) }, onPicked,
  )

  actual fun copy(text: String, onDone: (Boolean) -> Unit) = jsCopy(text, onDone)

  actual fun openLink(url: String) {
    if (url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)) jsOpenLink(url)
  }

  actual fun download(path: String) = jsDownload(path)

  actual fun systemDarkMode(): Boolean = jsDarkMode()

  actual fun timeZone(): String? = jsTimeZone()

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

  actual fun fullScreen(on: Boolean) = jsFullScreen(on)

  actual fun onFullScreenChange(onChange: (Boolean) -> Unit): () -> Unit {
    val listener = jsOnFullScreenChange(onChange)
    return { jsOffFullScreenChange(listener) }
  }

  actual fun loadSetting(key: String): String? = jsLoadSetting(key)

  actual fun saveSetting(key: String, value: String?) = jsSaveSetting(key, value)
}

private fun jsNow(): Double = js("Date.now()")

private fun jsFetchBytes(path: String, onDone: (JsAny) -> Unit, onFailed: () -> Unit): Unit =
  js("{ fetch(path).then(r => r.ok ? r.arrayBuffer() : Promise.reject(r.status)).then(b => onDone(new Int8Array(b)), () => onFailed()); }")

private fun jsLength(bytes: JsAny): Int = js("bytes.length")

private fun jsByte(bytes: JsAny, index: Int): Byte = js("bytes[index]")

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

// The File System Access API's dialog where there is one (its `id` makes the browser remember each kind's folder),
// else a hidden file input. Each chosen file's size is checked first; then it is read as text, or as base64 (from a
// data: URL). A file too large or unreadable is reported and left out; nothing is kept (no object URLs, no handles)
private fun jsPickFiles(
  kind: String,
  accept: String,
  binary: Boolean,
  maxBytes: Double,
  multiple: Boolean,
  onProblem: (String, String) -> Unit,
  onPicked: (String) -> Unit,
): Unit = js(
  """{ const read = f => new Promise(done => {
         const fail = () => { onProblem(f.name, 'unreadable'); done(null); };
         if (f.size > maxBytes) { onProblem(f.name, 'too-large'); done(null); return; }
         const r = new FileReader();
         r.onload = () => done(binary ? r.result.substring(r.result.indexOf(',') + 1) : r.result);
         r.onerror = fail; r.onabort = fail;
         try { if (binary) r.readAsDataURL(f); else r.readAsText(f); } catch (e) { fail(); }
       });
       const deliver = files => {
         if (!files.length) return;
         Promise.all(files.map(f => read(f).then(t => t === null ? null : ({ fileName: f.name, content: t }))))
           .then(all => { const ok = all.filter(x => x !== null); if (ok.length) onPicked(JSON.stringify(ok)); });
       };
       const input = () => {
         const i = document.createElement('input');
         i.type = 'file'; i.multiple = multiple; i.accept = accept;
         i.addEventListener('change', () => { const files = Array.from(i.files || []); i.value = ''; deliver(files); });
         i.click();
       };
       if (typeof window.showOpenFilePicker !== 'function') { input(); return; }
       const id = kind.replace(/[^A-Za-z0-9_-]/g, '').slice(0, 32);
       const endings = accept.split(',').map(s => s.trim()).filter(s => /^\.[A-Za-z0-9]{1,16}$/.test(s));
       const options = { multiple: multiple };
       if (id) options.id = id;
       if (endings.length) options.types = [{ description: endings.join(', '), accept: { 'application/octet-stream': endings } }];
       window.showOpenFilePicker(options)
         .then(handles => Promise.all(handles.map(h => h.getFile().catch(() => { onProblem(h.name, 'unreadable'); return null; }))))
         .then(files => deliver(files.filter(f => f !== null)),
               e => { if (!e || e.name !== 'AbortError') input(); }); }"""
)

// The browser's answer: taken, or refused (no clipboard on a page without HTTPS, no permission)
private fun jsCopy(text: String, onDone: (Boolean) -> Unit): Unit = js(
  "{ try { navigator.clipboard.writeText(text).then(() => onDone(true), () => onDone(false)); } catch (e) { onDone(false); } }"
)

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

private fun jsTimeZone(): String? =
  js("{ try { return Intl.DateTimeFormat().resolvedOptions().timeZone || null; } catch (e) { return null; } }")

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

// A browser that refuses (no click behind it, an embedding page's policy) leaves the page as it is
private fun jsFullScreen(on: Boolean): Unit = js(
  """{ const page = document.documentElement;
       if (on) { if (!document.fullscreenElement && page.requestFullscreen) page.requestFullscreen().catch(() => {}); }
       else if (document.fullscreenElement && document.exitFullscreen) document.exitFullscreen().catch(() => {}); }"""
)

private fun jsOnFullScreenChange(onChange: (Boolean) -> Unit): JsAny = js(
  """{ const listener = () => onChange(!!document.fullscreenElement);
       document.addEventListener('fullscreenchange', listener);
       return listener; }"""
)

private fun jsOffFullScreenChange(listener: JsAny): Unit = js("document.removeEventListener('fullscreenchange', listener)")
