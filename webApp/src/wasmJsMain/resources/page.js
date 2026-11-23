// index.html's own script: its texts, the login forms and the start of the web app. A file of its own (not inline):
// the page's content policy only runs scripts from here.
// The texts of this page in English and German (the web app has its own, in :shared); `data-t` names an element's
window.randogeddonTexts = (function () {
  var TEXTS = {
    en: {
      loading: "loading…", nameOrEmail: "Name or e-mail", password: "Password", logIn: "Log in",
      yourCode: "Your code", code: "Code",
      codeHint: "The 6 digits your authenticator app shows for Randogeddon, or one of your recovery codes.",
      twoFactor: "Two-factor login", turnItOn: "Turn it on",
      setupHint: "Administrators log in with a second factor. Scan this with an authenticator app (any TOTP app), or type " +
        "the key in, then enter the code it shows.",
      recoveryCodes: "Recovery codes", copy: "Copy", savedThem: "I have saved them",
      recoveryHint: "Each works once, in place of a code, if you lose your authenticator. Keep them somewhere " +
        "safe: they are shown only now.",
      welcome: "Welcome to Randogeddon", name: "Name", passwordAgain: "Password again",
      deleteByItself: "Delete my account by itself", weeks2: "2 weeks after my last session",
      weeks4: "4 weeks after my last session", months3: "3 months after my last session",
      months6: "6 months after my last session", year1: "1 year after my last session", never: "Never",
      signUp: "Sign up", newPassword: "New password", currentPassword: "Current password",
      newPasswordAgain: "New password again", otherLoginsEnd: "Your other logins end; this one stays.",
      changePassword: "Change password", cancel: "Cancel",
      tooMany: "Too many attempts. Please wait a minute.", failed: "That didn't work ({0}).",
      didntWork: "That didn't work.", unreachable: "The server can't be reached.",
      differ: "The two passwords differ.", copied: "Copied.",
      invited: "You're invited as {0}. Choose the name the others see, and your password.",
      sso: "Administrators: log in with {0}",
      tooOld: "<b>Your browser is too old for this page.</b><br>Please update it (iOS 18.2+, Chrome 119+, Firefox 120+)."
    },
    de: {
      loading: "lädt…", nameOrEmail: "Name oder E-Mail", password: "Passwort", logIn: "Anmelden",
      yourCode: "Dein Code", code: "Code",
      codeHint: "Die 6 Ziffern, die deine Authenticator-App für Randogeddon zeigt, oder einer deiner " +
        "Wiederherstellungscodes.",
      twoFactor: "Zwei-Faktor-Anmeldung", turnItOn: "Einschalten",
      setupHint: "Administratoren melden sich mit einem zweiten Faktor an. Scanne das mit einer Authenticator-App (jede " +
        "TOTP-App) oder tippe den Schlüssel ab, und gib dann den angezeigten Code ein.",
      recoveryCodes: "Wiederherstellungscodes", copy: "Kopieren", savedThem: "Ich habe sie gespeichert",
      recoveryHint: "Jeder funktioniert einmal anstelle eines Codes, falls du deinen Authenticator verlierst. " +
        "Bewahre sie sicher auf: Sie werden nur jetzt angezeigt.",
      welcome: "Willkommen bei Randogeddon", name: "Name", passwordAgain: "Passwort wiederholen",
      deleteByItself: "Mein Konto von selbst löschen", weeks2: "2 Wochen nach meiner letzten Session",
      weeks4: "4 Wochen nach meiner letzten Session", months3: "3 Monate nach meiner letzten Session",
      months6: "6 Monate nach meiner letzten Session", year1: "1 Jahr nach meiner letzten Session", never: "Nie",
      signUp: "Registrieren", newPassword: "Neues Passwort", currentPassword: "Aktuelles Passwort",
      newPasswordAgain: "Neues Passwort wiederholen",
      otherLoginsEnd: "Deine anderen Anmeldungen enden; diese bleibt.",
      changePassword: "Passwort ändern", cancel: "Abbrechen",
      tooMany: "Zu viele Versuche. Bitte warte eine Minute.", failed: "Das hat nicht geklappt ({0}).",
      didntWork: "Das hat nicht geklappt.", unreachable: "Der Server ist nicht erreichbar.",
      differ: "Die beiden Passwörter sind verschieden.", copied: "Kopiert.",
      invited: "Du bist als {0} eingeladen. Wähle den Namen, den die anderen sehen, und dein Passwort.",
      sso: "Administratoren: mit {0} anmelden",
      tooOld: "<b>Dein Browser ist zu alt für diese Seite.</b><br>Bitte aktualisiere ihn (iOS 18.2+, Chrome 119+, " +
        "Firefox 120+)."
    }
  };
  var KEY = "ap-language";
  function supported(code) {
    var main = (code || "").toLowerCase().split(/[-_]/)[0];
    return TEXTS[main] ? main : null;
  }
  function stored() { try { return supported(localStorage.getItem(KEY)); } catch (e) { return null; } }
  var browser = supported((navigator.languages && navigator.languages[0]) || navigator.language);
  // The one last used here, else the browser's, else English (as the web app picks it before a login)
  var current = stored() || browser || "en";
  // The texts with {0} filled in
  function t(key, value) { return (TEXTS[current][key] || TEXTS.en[key]).replace("{0}", value); }
  var listeners = [];
  function apply(code) {
    current = supported(code) || current;
    document.documentElement.lang = current;
    // The server words its answers in it too (the forms' messages, the login's way back from Authentik)
    document.cookie = "ap-language=" + current + "; path=/; max-age=31536000; SameSite=Lax" +
      (location.protocol === "https:" ? "; Secure" : "");
    document.querySelectorAll("[data-t]").forEach(function (element) { element.textContent = t(element.dataset.t); });
    document.querySelectorAll("#login .languages a").forEach(function (link) {
      if (link.dataset.language === current) link.setAttribute("aria-current", "true");
      else link.removeAttribute("aria-current");
    });
    listeners.forEach(function (listener) { listener(); });
  }
  return {
    t: t,
    apply: apply,
    // Chosen on the login page: remembered for the next visit
    choose: function (code) {
      try { localStorage.setItem(KEY, code); } catch (e) {}
      apply(code);
    },
    onChange: function (listener) { listeners.push(listener); }
  };
})();
randogeddonTexts.apply();
// A browser zoom or another screen's scaling needs nothing here: Compose (1.13) draws at the new pixel density by
// itself, without a reload that would lose what is going on. But Ctrl and the mouse wheel (a trackpad's pinch sends
// the same) would reach Compose, which scrolls with it and keeps the browser from zooming: it stops here, before
// the app's canvas sees it, and the browser zooms.
window.addEventListener("wheel", function (event) {
  if (event.ctrlKey) event.stopImmediatePropagation();
}, { capture: true, passive: true });
// Kotlin/Wasm needs WasmGC: Chrome/Edge 119+, Firefox 120+, Safari 18.2+ (iOS 18.2+)
(function () {
  var ok = typeof WebAssembly === "object" && WebAssembly.validate(new Uint8Array([
    0, 97, 115, 109, 1, 0, 0, 0, 1, 5, 1, 95, 1, 120, 0]));
  if (!ok) {
    document.querySelector("#splash div:last-of-type").innerHTML = randogeddonTexts.t("tooOld");
    document.querySelector("#splash .ring").remove();
    return;
  }
  var s = document.createElement("script");
  s.src = "webApp.js";
  document.body.appendChild(s);
})();
// Shown by the web app (Platform.showLogin) when the server answers 401: the login, or with an invitation's link
// (#/invite/<token>) the sign-up. Platform.showPasswordChange opens the password form from the settings.
window.randogeddonLogin = (function () {
  var t = randogeddonTexts.t;
  var root = document.getElementById("login");
  var forms = {
    login: document.getElementById("login-form"),
    signup: document.getElementById("signup-form"),
    password: document.getElementById("password-form"),
    code: document.getElementById("code-form"),
    setup: document.getElementById("setup-form"),
    codes: document.getElementById("codes-form")
  };
  // A login whose password was right, waiting for its code (or the setup)
  var pending = null;
  var done = null;
  var token = null;

  function open(name, text) {
    Object.keys(forms).forEach(function (key) { forms[key].hidden = key !== name; });
    forms[name].querySelector(".message").textContent = text || "";
    root.hidden = false;
  }
  function close(ok) {
    root.hidden = true;
    var callback = done;
    done = null;
    if (callback) callback(ok);
  }
  // Sends the form's JSON; on success calls onOk, otherwise shows why
  function send(form, method, path, body, onOk) {
    var button = form.querySelector("button[type=submit]");
    var message = form.querySelector(".message");
    button.disabled = true;
    message.textContent = "";
    fetch(path, { method: method, headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) })
      .then(function (r) {
        if (r.ok) { return (r.status === 204 ? Promise.resolve(null) : r.json()).then(onOk); }
        if (r.status === 429) { message.textContent = t("tooMany"); return; }
        return r.json().then(function (e) { message.textContent = e.message; },
          function () { message.textContent = t("failed", r.status); });
      }, function () { message.textContent = t("unreachable"); })
      .finally(function () { button.disabled = false; });
  }
  function sameTwice(form) {
    if (form.password.value === form.repeat.value) return true;
    form.querySelector(".message").textContent = t("differ");
    form.repeat.focus();
    return false;
  }
  // The hash goes back to the sessions (the token isn't needed any more, and shouldn't stay in the history)
  function leaveInvitation() {
    history.replaceState(null, "", location.pathname + location.search + "#/");
    window.dispatchEvent(new HashChangeEvent("hashchange"));
  }

  forms.login.addEventListener("submit", function (event) {
    event.preventDefault();
    var form = forms.login;
    send(form, "POST", "api/login", { username: form.username.value, password: form.password.value }, function (step) {
      form.password.value = "";
      if (!step) { close(true); return; }
      pending = step.pending;
      if (step.next === "code") {
        forms.code.code.value = "";
        open("code");
        forms.code.code.focus();
      } else {
        startSetup();
      }
    });
  });
  forms.code.addEventListener("submit", function (event) {
    event.preventDefault();
    send(forms.code, "POST", "api/login/code", { pending: pending, code: forms.code.code.value }, function () {
      forms.code.code.value = "";
      pending = null;
      close(true);
    });
  });
  function startSetup() {
    var form = forms.setup;
    fetch("api/login/setup", { method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ pending: pending }) }).then(function (r) {
      // An answer without the server's JSON (a proxy's error page): its status at least
      if (!r.ok) {
        return r.json().then(function (e) { open("login", e.message); },
          function () { open("login", r.status === 429 ? t("tooMany") : t("failed", r.status)); });
      }
      return r.json().then(function (setup) {
        // The server's SVG of the QR code (nothing else in it)
        form.querySelector(".qr").innerHTML = setup.svg;
        form.querySelector(".secret").textContent = setup.secret.replace(/(.{4})/g, "$1 ").trim();
        form.code.value = "";
        open("setup");
        form.code.focus();
      }, function () { open("login", t("didntWork")); });
    }, function () { open("login", t("unreachable")); });
  }
  forms.setup.addEventListener("submit", function (event) {
    event.preventDefault();
    send(forms.setup, "POST", "api/login/setup/confirm", { pending: pending, code: forms.setup.code.value }, function (answer) {
      pending = null;
      var list = forms.codes.querySelector(".codes");
      list.innerHTML = "";
      answer.codes.forEach(function (code) {
        var item = document.createElement("li");
        item.innerHTML = "<code></code>";
        item.firstChild.textContent = code;
        list.appendChild(item);
      });
      forms.codes.dataset.codes = answer.codes.join("\n");
      open("codes");
    });
  });
  // Without HTTPS there is no clipboard (navigator.clipboard is missing): the codes stay to be copied by hand
  forms.codes.querySelector("button.copy").addEventListener("click", function () {
    try {
      navigator.clipboard.writeText(forms.codes.dataset.codes).then(function () {
        forms.codes.querySelector(".message").textContent = t("copied");
      }, function () {});
    } catch (e) { /* nothing to copy to */ }
  });
  forms.codes.addEventListener("submit", function (event) {
    event.preventDefault();
    forms.codes.dataset.codes = "";
    forms.codes.querySelector(".codes").innerHTML = "";
    close(true);
  });
  forms.signup.addEventListener("submit", function (event) {
    event.preventDefault();
    var form = forms.signup;
    if (!sameTwice(form)) return;
    send(form, "POST", "api/signup", {
      token: token, name: form.username.value.trim(), password: form.password.value, autoDelete: form.autoDelete.value
    }, function () {
      form.password.value = form.repeat.value = "";
      token = null;
      leaveInvitation();
      close(true);
    });
  });
  forms.password.addEventListener("submit", function (event) {
    event.preventDefault();
    var form = forms.password;
    if (!sameTwice(form)) return;
    send(form, "PUT", "api/account/password", { current: form.current.value, password: form.password.value }, function () {
      form.current.value = form.password.value = form.repeat.value = "";
      close(true);
    });
  });
  forms.password.querySelector("button.secondary").addEventListener("click", function () { close(false); });

  // The sign-up's hint names the invited address; it is worded again when the language changes
  var invitedAs = null;
  function signupHint() {
    if (invitedAs !== null) forms.signup.querySelector(".hint").textContent = t("invited", invitedAs);
  }
  function showSignup(invitation) {
    var form = forms.signup;
    token = invitation;
    // An answer without the server's JSON (too many attempts, a proxy's error page) says so, not "unreachable"
    fetch("api/invitations/" + encodeURIComponent(invitation)).then(function (r) {
      return r.json().catch(function () {
        return { valid: false, problem: r.status === 429 ? t("tooMany") : t("failed", r.status) };
      });
    }).then(function (info) {
      if (!info.valid) {
        token = null;
        leaveInvitation();
        open("login", info.problem);
        forms.login.username.focus();
        return;
      }
      form.email.value = info.email || "";
      invitedAs = info.email;
      signupHint();
      if (!form.username.value) form.username.value = info.suggestedName || "";
      open("signup");
      (form.username.value ? form.password : form.username).focus();
    }, function () {
      open("login", t("unreachable"));
    });
  }
  // Administrators log in through the provider, if the server names one
  var provider = null;
  function ssoLink() {
    if (provider === null) return;
    var link = forms.login.querySelector("a.sso");
    link.textContent = t("sso", provider);
    link.hidden = false;
  }
  fetch("api/login/options").then(function (r) { return r.ok ? r.json() : {}; }).then(function (options) {
    if (!options.singleSignOn) return;
    provider = options.singleSignOn;
    ssoLink();
  }, function () {});
  randogeddonTexts.onChange(function () { ssoLink(); signupHint(); });
  forms.login.querySelectorAll(".languages a").forEach(function (link) {
    link.addEventListener("click", function (event) {
      event.preventDefault();
      randogeddonTexts.choose(link.dataset.language);
    });
  });
  // A one-time login link of the host's CLI (#/login/<token>)
  function useLink(linkToken) {
    leaveInvitation();
    fetch("api/login/link", { method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ token: linkToken }) }).then(function (r) {
      if (r.ok) { close(true); return; }
      return r.json().then(function (e) { open("login", e.message); }, function () { open("login", t("didntWork")); });
    }, function () { open("login", t("unreachable")); });
  }
  return {
    show: function (text, onLoggedIn) {
      done = function (ok) { if (ok) onLoggedIn(); };
      // A token that isn't one (a broken % escape) goes nowhere: the plain login instead
      function decoded(part) { try { return decodeURIComponent(part); } catch (e) { return null; } }
      var invitation = /^#\/invite\/([^/]+)/.exec(location.hash);
      var invited = invitation && decoded(invitation[1]);
      if (invited) { showSignup(invited); return; }
      var link = /^#\/login\/([^/]+)/.exec(location.hash);
      var linked = link && decoded(link[1]);
      if (linked) { useLink(linked); return; }
      if (invitation || link) leaveInvitation();
      // Back from the provider without a login: why
      // Its text comes in a cookie the server set, never from the link (which could carry anyone's words). Over HTTPS
      // its name has the __Host- prefix (no other host can set it); the plain name is the local demo's
      if (/^#\/login-problem/.test(location.hash)) {
        leaveInvitation();
        var problem = /(?:^|; )(?:__Host-)?randogeddon_login_problem=([^;]*)/.exec(document.cookie);
        document.cookie = "randogeddon_login_problem=; Max-Age=0; path=/";
        if (location.protocol === "https:") document.cookie = "__Host-randogeddon_login_problem=; Max-Age=0; path=/; secure";
        if (problem) { try { text = decodeURIComponent(problem[1].replace(/\+/g, " ")); } catch (e) { /* not ours */ } }
      }
      open("login", text);
      var form = forms.login;
      (form.username.value ? form.password : form.username).focus();
    },
    hide: function () { root.hidden = true; },
    // The web app's language (Platform.setPageLanguage)
    language: function (code) { randogeddonTexts.apply(code); },
    password: function (name, onDone) {
      done = onDone;
      forms.password.username.value = name || "";
      open("password");
      forms.password.current.focus();
    }
  };
})();
if ("serviceWorker" in navigator) {
  window.addEventListener("load", function () { navigator.serviceWorker.register("sw.js").catch(function () {}); });
}
