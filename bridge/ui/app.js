"use strict";

// The page's words, in English and German (as the account's language, else the browser's)
const TEXTS = {
  en: {
    bridge: "Bridge", logout: "Log out", quit: "Quit", download: "Download",
    update: (v) => `Randogeddon Bridge ${v} is out.`,
    loginTitle: "Log in with a key",
    loginHint: "On the dashboard, under Your connection, choose Create key and paste it here. The key stays on this " +
      "computer until you log out.",
    key: "Key", dashboard: "Dashboard", login: "Log in",
    session: "Session", slot: "Slot", address: "Address", state: "State", close: "Close", open: "Open",
    copy: "Copy", copied: "Copied", hosted: "hosted",
    none: "No connection open yet: pick a session and a slot below.",
    noSessions: "No running session with a slot of yours (or a hosted one you manage).",
    howTo: "In the game, connect to the address (some games call it server or host). Any slot name and no password: " +
      "the bridge fills in yours.",
    states: { waiting: "Waiting for the game", connecting: "Connecting…", connected: "Connected", error: "Error" },
    games: (n) => n > 1 ? ` (${n} clients)` : "",
    quitted: "Randogeddon Bridge has quit. You can close this tab.",
    unreachable: "The bridge doesn't answer: is it still running?",
  },
  de: {
    bridge: "Bridge", logout: "Abmelden", quit: "Beenden", download: "Herunterladen",
    update: (v) => `Randogeddon Bridge ${v} ist da.`,
    loginTitle: "Mit einem Schlüssel anmelden",
    loginHint: "Wähle auf dem Dashboard unter Deine Verbindung Schlüssel erstellen und füge ihn hier ein. Der Schlüssel " +
      "bleibt auf diesem Computer, bis du dich abmeldest.",
    key: "Schlüssel", dashboard: "Dashboard", login: "Anmelden",
    session: "Session", slot: "Slot", address: "Adresse", state: "Status", close: "Schließen", open: "Öffnen",
    copy: "Kopieren", copied: "Kopiert", hosted: "gehostet",
    none: "Noch keine Verbindung offen: Wähle unten eine Session und einen Slot.",
    noSessions: "Keine laufende Session mit einem Slot von dir (oder einem gehosteten, den du verwaltest).",
    howTo: "Verbinde dich im Spiel mit der Adresse (manche Spiele nennen sie Server oder Host). Beliebiger Slot-Name " +
      "und kein Passwort: Die Bridge trägt deine ein.",
    states: { waiting: "Wartet auf das Spiel", connecting: "Verbindet…", connected: "Verbunden", error: "Fehler" },
    games: (n) => n > 1 ? ` (${n} Clients)` : "",
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

async function api(method, path, body) {
  const response = await fetch(path, {
    method,
    headers: Object.assign({ "X-Bridge": "1" }, body ? { "Content-Type": "application/json" } : {}),
    body: body ? JSON.stringify(body) : undefined,
  });
  if (response.status === 204) return null;
  const data = await response.json().catch(() => null);
  if (!response.ok) throw new Error((data && data.message) || `${response.status}`);
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

function renderRows() {
  const rows = $("rows");
  rows.replaceChildren();
  if (!state.relays.length) {
    rows.append(el("tr", {}, el("td", { class: "empty", colspan: "5" }, t.none)));
    return;
  }
  for (const r of state.relays) {
    const address = `localhost:${r.port}`;
    const copy = el("button", { class: "icon", type: "button" }, t.copy);
    copy.addEventListener("click", async () => {
      try { await navigator.clipboard.writeText(address); copy.textContent = t.copied; } catch (e) { /* not allowed */ }
      setTimeout(() => { copy.textContent = t.copy; }, 1500);
    });
    const closeButton = el("button", { class: "icon", type: "button", title: t.close, "aria-label": t.close }, "✕");
    closeButton.addEventListener("click", () => act(() => api("DELETE", `/api/relays/${r.id}`)));
    rows.append(el("tr", {},
      el("td", {}, r.title),
      el("td", {}, r.slot, el("span", { class: "game" }, r.game)),
      el("td", {}, el("span", { class: "address" }, address), copy),
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
    $("update-link").href = state.updateUrl;
  }
  showProblem(state.problem);
}

async function refresh() {
  try {
    state = await api("GET", "/api/state");
    render();
  } catch (e) {
    if (!quitted) showProblem(t.unreachable);
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
    select.replaceChildren(...sessions.map((s) => el("option", { value: s.id }, s.title)));
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
    select.replaceChildren(...slots.map((s) => el("option", { value: s.name }, `${s.name} · ${s.game}${s.hosted ? ` · ${t.hosted}` : ""}`)));
    select.disabled = !slots.length;
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
  const session = $("session");
  const title = session.selectedOptions[0] ? session.selectedOptions[0].textContent : session.value;
  act(() => api("POST", "/api/relays", { session: session.value, title, slot: $("slot").value }));
});

$("logout").addEventListener("click", () => act(() => api("POST", "/api/logout")));

$("quit").addEventListener("click", async () => {
  try { await api("POST", "/api/quit"); } catch (e) { /* it may be gone already */ }
  quitted = true;
  for (const id of ["login", "relays", "user", "update", "problem", "quit"]) $(id).hidden = true;
  $("quitted").hidden = false;
});

applyTexts();
refresh();
setInterval(refresh, 1500);
// Back on this page: the account's theme, mode and language as they are now (changed on the dashboard meanwhile)
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible") api("POST", "/api/refresh").then(() => refresh()).catch(() => {});
});
