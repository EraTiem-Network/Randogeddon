"use strict";

// The page's words, in English and German (as the account's language, else the browser's)
const TEXTS = {
  en: {
    bridge: "Bridge", logout: "Log out", quit: "Quit", download: "Download",
    update: (v) => `Randogeddon Bridge ${v} is available.`,
    updateCheck: "To check the download, compare its signing key's fingerprint with the one the dashboard shows " +
      "under Your connection.",
    loginTitle: "Log in with a key",
    loginHint: "On the dashboard, under Your connection, choose Create key and paste it here. The key stays on this " +
      "computer until you log out.",
    key: "Key", dashboard: "Dashboard", login: "Log in",
    session: "Session", slot: "Slot", address: "Address", state: "State", close: "Close", open: "Open",
    copy: "Copy", copied: "Copied", hosted: "hosted",
    copyAsShown: "As shown", copyHost: "Host", copyPort: "Port", copyWs: "ws:// address",
    none: "No connection open yet: pick a session and a slot below.",
    noSessions: "No running session you play in or manage.",
    noSlots: "No slot to open in this session (locked, or hosted without one for you).",
    howTo: "In the game, connect to the address (some games call it server or host). Any slot name and no password: " +
      "the bridge fills in yours.",
    quitRule: "Closing this page quits the bridge, unless a game is still connected through it: then it quits once " +
      "the last one has left.",
    openFromBridge: "Open this page from Randogeddon Bridge: start it again, it opens the page (a link works once).",
    states: { waiting: "Waiting for the game", connecting: "Connecting…", connected: "Connected", error: "Error" },
    games: (n) => n > 1 ? ` (${n} clients)` : "",
    minecraft: "Minecraft 1.19.4: Multiplayer, Direct Connection, this address. The server starts when you join " +
      "(about a minute; Logging in… meanwhile).",
    quitted: "Randogeddon Bridge has quit. You can close this tab.",
    unreachable: "The bridge doesn't answer: is it still running?",
  },
  de: {
    bridge: "Bridge", logout: "Abmelden", quit: "Beenden", download: "Herunterladen",
    update: (v) => `Randogeddon Bridge ${v} ist verfügbar.`,
    updateCheck: "Zum Prüfen des Downloads: Vergleiche den Fingerabdruck seines Signaturschlüssels mit dem, den das " +
      "Dashboard unter Deine Verbindung zeigt.",
    loginTitle: "Mit einem Schlüssel anmelden",
    loginHint: "Wähle auf dem Dashboard unter Deine Verbindung Schlüssel erstellen und füge ihn hier ein. Der Schlüssel " +
      "bleibt auf diesem Computer, bis du dich abmeldest.",
    key: "Schlüssel", dashboard: "Dashboard", login: "Anmelden",
    session: "Session", slot: "Slot", address: "Adresse", state: "Status", close: "Schließen", open: "Öffnen",
    copy: "Kopieren", copied: "Kopiert", hosted: "gehostet",
    copyAsShown: "Wie angezeigt", copyHost: "Host", copyPort: "Port", copyWs: "ws://-Adresse",
    none: "Noch keine Verbindung offen: Wähle unten eine Session und einen Slot.",
    noSessions: "Keine laufende Session, in der du spielst oder die du verwaltest.",
    noSlots: "Kein Slot zum Öffnen in dieser Session (gesperrt, oder gehostet ohne einen für dich).",
    howTo: "Verbinde dich im Spiel mit der Adresse (manche Spiele nennen sie Server oder Host). Beliebiger Slot-Name " +
      "und kein Passwort: Die Bridge trägt deine ein.",
    quitRule: "Wird diese Seite geschlossen, beendet sich die Bridge, außer ein Spiel ist noch über sie verbunden: " +
      "dann, sobald das letzte getrennt ist.",
    openFromBridge: "Öffne diese Seite aus Randogeddon Bridge: Starte sie erneut, sie öffnet die Seite (ein Link " +
      "funktioniert nur einmal).",
    states: { waiting: "Wartet auf das Spiel", connecting: "Verbindet…", connected: "Verbunden", error: "Fehler" },
    games: (n) => n > 1 ? ` (${n} Clients)` : "",
    minecraft: "Minecraft 1.19.4: Mehrspieler, Direkt verbinden, diese Adresse. Der Server startet, wenn du beitrittst " +
      "(etwa eine Minute; solange Logging in…).",
    quitted: "Randogeddon Bridge ist beendet. Du kannst diesen Tab schließen.",
    unreachable: "Die Bridge antwortet nicht: Läuft sie noch?",
  },
};

const $ = (id) => document.getElementById(id);
let lang = (navigator.language || "en").toLowerCase().startsWith("de") ? "de" : "en";
let t = TEXTS[lang];
let state = null;
let sessionsLoaded = false;
let quitted = false;
// The running sessions' titles, by ID
let sessionTitles = {};

// The page's token: its link carries a code that works once, exchanged here for it; kept for this tab (a reload
// keeps working) and sent with every call. No cookie: a browser would send that to every server on this computer.
let token = "";
try { token = sessionStorage.getItem("randogeddon-bridge") || ""; } catch (e) { /* no storage: this page's alone */ }

async function takeToken() {
  const params = new URLSearchParams(location.search);
  const code = params.get("c");
  const given = params.get("t");
  if (code || given) history.replaceState(null, "", "/");
  if (given) token = given;
  if (code) {
    const response = await fetch("/api/exchange", {
      method: "POST",
      headers: { "X-Bridge": "1", "Content-Type": "application/json" },
      body: JSON.stringify({ code }),
    }).catch(() => null);
    const data = response && response.ok ? await response.json().catch(() => null) : null;
    if (data && data.token) token = data.token;
  }
  try { sessionStorage.setItem("randogeddon-bridge", token); } catch (e) { /* kept in this page only */ }
}

function applyTexts() {
  document.documentElement.lang = lang;
  document.querySelectorAll("[data-t]").forEach((el) => {
    const text = t[el.dataset.t];
    if (typeof text === "string") el.textContent = text;
  });
}

// The account's colours: its theme's light or dark palette, by its mode (automatic: the device's)
const dark = window.matchMedia("(prefers-color-scheme: dark)");
function applyColours() {
  const info = state && state.info;
  const root = document.documentElement;
  if (!info || !info.light || !info.dark) {
    root.removeAttribute("data-mode");
    return;
  }
  const isDark = info.mode === "dark" || (info.mode !== "light" && dark.matches);
  root.dataset.mode = isDark ? "dark" : "light";
  for (const [name, value] of Object.entries(isDark ? info.dark : info.light)) root.style.setProperty(`--${name}`, value);
}
dark.addEventListener("change", applyColours);

// The account's font (the bridge fetches its files from the dashboard): for all text, or for the headings only.
// Without one, or until it is loaded, the system's font shows.
let fontShown = "";
function applyFont() {
  const font = state && state.info && state.info.font;
  const key = font ? JSON.stringify(font) : "";
  if (key === fontShown) return;
  fontShown = key;
  const root = document.documentElement;
  for (const name of ["--font-text", "--font-heading", "--text-scale", "--heading-scale"]) root.style.removeProperty(name);
  if (!font) return;
  // A name of its own per font: a changed choice doesn't show the one loaded before
  const family = "account-" + font.regular.replace(/[^a-z0-9]/g, "-");
  // Fetched with the page's token (a font's url() couldn't send it)
  const load = (weight, file) => fetch(`/font/${weight}?f=${encodeURIComponent(file)}`, { headers: { "X-Bridge": token } })
    .then((response) => {
      if (!response.ok) throw new Error(`${response.status}`);
      return response.arrayBuffer();
    })
    .then((data) => new FontFace(family, data, { weight: weight === "bold" ? "700" : "400" }).load());
  const faces = [load("regular", font.regular)];
  if (font.bold) faces.push(load("bold", font.bold));
  Promise.all(faces).then((loaded) => {
    if (key !== fontShown) return;
    loaded.forEach((face) => document.fonts.add(face));
    const scale = String(font.scale || 1);
    root.style.setProperty("--font-heading", `"${family}"`);
    root.style.setProperty("--heading-scale", scale);
    if (!font.headingsOnly) {
      root.style.setProperty("--font-text", `"${family}"`);
      root.style.setProperty("--text-scale", scale);
    }
  }, () => { /* the system's font stays */ });
}

async function api(method, path, body) {
  const response = await fetch(path, {
    method,
    headers: Object.assign({ "X-Bridge": token }, body ? { "Content-Type": "application/json" } : {}),
    body: body ? JSON.stringify(body) : undefined,
  });
  if (response.status === 204) return null;
  const data = await response.json().catch(() => null);
  if (!response.ok) {
    // Without the page's token: opened otherwise than from the bridge
    const error = new Error(response.status === 403 ? t.openFromBridge : (data && data.message) || `${response.status}`);
    error.status = response.status;
    throw error;
  }
  return data;
}

function showProblem(message) {
  $("problem").textContent = message || "";
  $("problem").hidden = !message;
}

function el(tag, attrs, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (k === "class") node.className = v;
    else if (k.startsWith("on")) node.addEventListener(k.slice(2), v);
    else node.setAttribute(k, v);
  }
  for (const child of children) if (child != null) node.append(child);
  return node;
}

// The relay whose copy menu is open: the rows are drawn again with every refresh, the menu stays open over it
let openMenu = null;
// The relay whose address was just copied: its button says so for a moment
let copiedRelay = null;
let copiedTimer = null;
document.addEventListener("click", (e) => {
  if (openMenu !== null && !e.target.closest(".copy-menu")) { openMenu = null; renderRows(); }
});

/**
 * A relay's address to copy as shown, its host, its port, or as a ws:// address (a game asks for one or the other;
 * the relay speaks plain WebSocket). Not as an address for a Minecraft tunnel: Minecraft takes host and port.
 */
function copyMenu(id, address, port, tunnel) {
  const button = el("button", { class: "icon", type: "button", "aria-haspopup": "menu", "aria-expanded": String(openMenu === id) },
    copiedRelay === id ? t.copied : `${t.copy} ▾`);
  button.addEventListener("click", () => { openMenu = openMenu === id ? null : id; renderRows(); });
  const choices = [[t.copyAsShown, address], [t.copyHost, "localhost"], [t.copyPort, String(port)]];
  if (!tunnel) choices.push([t.copyWs, `ws://${address}`]);
  const menu = el("div", { class: "menu", role: "menu" });
  menu.hidden = openMenu !== id;
  for (const [label, text] of choices) {
    const item = el("button", { type: "button", role: "menuitem" }, el("span", {}, label), el("code", {}, text));
    item.addEventListener("click", async () => {
      openMenu = null;
      renderRows();
      try { await navigator.clipboard.writeText(text); } catch (e) { return; /* not allowed: nothing was copied */ }
      copiedRelay = id;
      renderRows();
      clearTimeout(copiedTimer);
      copiedTimer = setTimeout(() => { copiedRelay = null; renderRows(); }, 1500);
    });
    menu.append(item);
  }
  return el("span", { class: "copy-menu" }, button, menu);
}

function renderRows() {
  const rows = $("rows");
  rows.replaceChildren();
  if (!state.relays.length) {
    rows.append(el("tr", {}, el("td", { class: "empty", colspan: "5" }, t.none)));
    return;
  }
  for (const r of state.relays) {
    const address = `localhost:${r.port}`;
    const copy = copyMenu(r.id, address, r.port, r.tunnel);
    const closeButton = el("button", { class: "icon", type: "button", title: t.close, "aria-label": t.close }, "✕");
    closeButton.addEventListener("click", () => act(() => api("DELETE", `/api/relays/${r.id}`)));
    rows.append(el("tr", {},
      el("td", {}, r.title, r.title !== r.session ? el("span", { class: "game" }, r.session) : null),
      el("td", {}, r.slot, el("span", { class: "game" }, r.game)),
      el("td", {}, el("span", { class: "address" }, address), copy,
        r.tunnel ? el("span", { class: "game" }, t.minecraft) : null),
      el("td", {},
        el("span", { class: `state ${r.state}` }, el("span", { class: "dot" }), (t.states[r.state] || r.state) + t.games(r.games)),
        r.detail ? el("span", { class: "detail" }, r.detail) : null),
      el("td", {}, closeButton),
    ));
  }
}

function render() {
  if (quitted) return;
  const user = state.user;
  const info = state.info;
  const wanted = info && info.language ? info.language : lang;
  if (wanted !== lang && TEXTS[wanted]) { lang = wanted; t = TEXTS[lang]; applyTexts(); }
  applyColours();
  applyFont();
  $("brand").href = state.server;
  $("version").textContent = state.version;
  $("user").hidden = !user;
  $("login").hidden = !!user;
  $("relays").hidden = !user;
  if (user) {
    $("name").textContent = user.name;
    $("avatar").textContent = (user.name || "?").slice(0, 1).toUpperCase();
    renderRows();
    if (!sessionsLoaded) loadSessions();
  } else {
    sessionsLoaded = false;
    if (!$("server").value) $("server").value = state.server;
  }
  $("update").hidden = !state.update;
  if (state.update) {
    $("update-text").textContent = t.update(state.update);
    $("update-link").hidden = !state.updateUrl;
    if (state.updateUrl) $("update-link").href = state.updateUrl;
  }
  showProblem(state.problem);
}

async function refresh() {
  try {
    state = await api("GET", "/api/state");
    render();
  } catch (e) {
    if (!quitted) showProblem(e.status === 403 ? e.message : t.unreachable);
  }
}

async function act(fn) {
  try {
    await fn();
    showProblem("");
  } catch (e) {
    showProblem(e.message);
  }
  await refresh();
}

async function loadSessions() {
  sessionsLoaded = true;
  const select = $("session");
  try {
    const sessions = await api("GET", "/api/sessions");
    sessionTitles = {};
    for (const s of sessions) sessionTitles[s.id] = s.title;
    // Its title, its ID (as the game addresses name it) beside it
    select.replaceChildren(...sessions.map((s) => el("option", { value: s.id }, s.title === s.id ? s.id : `${s.title} · ${s.id}`)));
    select.disabled = !sessions.length;
    $("open-form").querySelector("button").disabled = !sessions.length;
    if (!sessions.length) {
      $("slot").replaceChildren(el("option", { value: "" }, t.noSessions));
      $("slot").disabled = true;
      return;
    }
    await loadSlots();
  } catch (e) {
    sessionsLoaded = false;
    showProblem(e.message);
  }
}

async function loadSlots() {
  const select = $("slot");
  const session = $("session").value;
  if (!session) return;
  try {
    const slots = await api("GET", `/api/slots?session=${encodeURIComponent(session)}`);
    if (slots.length) {
      select.replaceChildren(...slots.map((s) => el("option", { value: s.name }, `${s.name} · ${s.game}${s.hosted ? ` · ${t.hosted}` : ""}`)));
    } else {
      select.replaceChildren(el("option", { value: "" }, t.noSlots));
    }
    select.disabled = !slots.length;
    $("open-form").querySelector("button").disabled = !slots.length;
  } catch (e) {
    showProblem(e.message);
  }
}

$("session").addEventListener("change", loadSlots);
$("session").addEventListener("focus", () => { if (!$("session").options.length) loadSessions(); });

$("login-form").addEventListener("submit", (e) => {
  e.preventDefault();
  act(async () => {
    await api("POST", "/api/login", { key: $("key").value, server: $("server").value });
    $("key").value = "";
  });
});

$("open-form").addEventListener("submit", (e) => {
  e.preventDefault();
  const session = $("session").value;
  act(() => api("POST", "/api/relays", { session, title: sessionTitles[session] || session, slot: $("slot").value }));
});

$("logout").addEventListener("click", () => act(() => api("POST", "/api/logout")));

$("quit").addEventListener("click", async () => {
  try { await api("POST", "/api/quit"); } catch (e) { /* it may be gone already */ }
  quitted = true;
  for (const id of ["login", "relays", "user", "update", "problem", "quit"]) $(id).hidden = true;
  $("quitted").hidden = false;
});

// While this page is open the bridge runs: it quits once the page is closed (or the browser is), unless it comes back
// within a few seconds (a reload) or a game is connected through it. Dropped otherwise (the bridge restarting): tried
// again. The token goes as its second subprotocol (a WebSocket can't carry a header).
function stayAlive() {
  if (quitted || !token) return;
  const socket = new WebSocket(`ws://${location.host}/api/alive`, ["randogeddon-bridge", token]);
  socket.addEventListener("close", () => { if (!quitted) setTimeout(stayAlive, 2000); });
}

applyTexts();
takeToken().catch(() => {}).then(() => {
  stayAlive();
  refresh();
  setInterval(refresh, 1500);
});
// Back on this page: the account's theme, mode and language as they are now (changed on the dashboard meanwhile)
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible") api("POST", "/api/refresh").then(() => refresh()).catch(() => {});
});
