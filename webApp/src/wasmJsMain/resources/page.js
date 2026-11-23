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
      show: "Show password", hide: "Hide password",
      passwordRules: "At least 12 characters; not your name, a repeated pattern or a common password. A few " +
        "unrelated words or a password manager's work best.",
      forgot: "Forgot your password?", forgotTitle: "Forgot your password?",
      forgotHint: "Your account's name or e-mail address. A link to set a new password goes to the account's address.",
      next: "Next", backToLogin: "Back to the login", verifyTitle: "Your e-mail address", email: "E-mail address",
      verifyHint: "The account's address is {0}. Type it in full: if it is the account's, a link to set a new " +
        "password goes there.",
      sendLink: "Send the link", sentTitle: "Check your mail",
      sentHint: "If that is the account's address, a mail with a link is on its way. The link works for 15 minutes, " +
        "once. Nothing there after a few minutes? Look in the spam folder, or ask again.",
      resetFor: "For {0}: choose your new password.",
      allLoginsEnd: "Every login of your account ends; a two-factor login stays on.", setPassword: "Set the password",
      goneTitle: "This link no longer works",
      goneHint: "A link works for 15 minutes and once, and a newer mail replaces it. Ask for a new one.",
      askAgain: "Ask for a new link", passwordSet: "Your password is set",
      passwordSetHint: "Every login of your account has ended. Log in with your new password.", toLogin: "To the login",
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
      show: "Passwort zeigen", hide: "Passwort verbergen",
      passwordRules: "Mindestens 12 Zeichen; nicht dein Name, kein wiederholtes Muster und kein gängiges Passwort. Ein " +
        "paar Wörter ohne Zusammenhang oder eines vom Passwort-Manager eignen sich am besten.",
      forgot: "Passwort vergessen?", forgotTitle: "Passwort vergessen?",
      forgotHint: "Der Name oder die E-Mail-Adresse deines Kontos. Ein Link, mit dem du ein neues Passwort setzt, geht " +
        "an die Adresse des Kontos.",
      next: "Weiter", backToLogin: "Zurück zur Anmeldung", verifyTitle: "Deine E-Mail-Adresse", email: "E-Mail-Adresse",
      verifyHint: "Die Adresse des Kontos ist {0}. Gib sie vollständig ein: Ist es die des Kontos, geht ein Link für " +
        "ein neues Passwort dorthin.",
      sendLink: "Link senden", sentTitle: "Schau in dein Postfach",
      sentHint: "Ist das die Adresse des Kontos, ist eine Mail mit einem Link unterwegs. Der Link funktioniert 15 " +
        "Minuten lang, einmal. Nach ein paar Minuten nichts da? Schau im Spam-Ordner nach oder frag erneut.",
      resetFor: "Für {0}: Wähle dein neues Passwort.",
      allLoginsEnd: "Alle Anmeldungen deines Kontos enden; eine Zwei-Faktor-Anmeldung bleibt eingeschaltet.",
      setPassword: "Passwort setzen", goneTitle: "Dieser Link funktioniert nicht mehr",
      goneHint: "Ein Link funktioniert 15 Minuten lang und einmal, und eine neuere Mail ersetzt ihn. Frag nach einem " +
        "neuen.",
      askAgain: "Neuen Link anfordern", passwordSet: "Dein Passwort ist gesetzt",
      passwordSetHint: "Alle Anmeldungen deines Kontos sind beendet. Melde dich mit deinem neuen Passwort an.",
      toLogin: "Zur Anmeldung",
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
    codes: document.getElementById("codes-form"),
    forgot: document.getElementById("forgot-form"),
    verify: document.getElementById("verify-form"),
    sent: document.getElementById("sent-form"),
    reset: document.getElementById("reset-form"),
    gone: document.getElementById("gone-form"),
    resetDone: document.getElementById("reset-done-form")
  };
  // A login whose password was right, waiting for its code (or the setup)
  var pending = null;
  var done = null;
  var token = null;

  // Every password field's eye shows what was typed (as the connection panel's does): a real button, reached by Tab and
  // named for screen readers; hidden by default, and again whenever a form is sent or another one opens
  var EYE = '<svg viewBox="0 0 100 100" aria-hidden="true" focusable="false"><path d="M6 50Q50 8 94 50Q50 92 6 50Z"/>' +
    '<circle cx="50" cy="50" r="13"/><line x1="14" y1="86" x2="86" y2="14"/></svg>';
  var eyes = [];
  function eyeLabel(eye) {
    var shown = eye.input.type === "text";
    eye.button.classList.toggle("shown", shown);
    eye.button.setAttribute("aria-label", t(shown ? "hide" : "show"));
    eye.button.title = t(shown ? "hide" : "show");
  }
  function hidePasswords() {
    eyes.forEach(function (eye) {
      eye.input.type = "password";
      eyeLabel(eye);
    });
  }
  root.querySelectorAll("input[type=password]").forEach(function (input, index) {
    // The field keeps its label's words as its name (not the button's beside it, which sits in the label too)
    var name = input.parentNode.querySelector("span");
    if (!name.id) name.id = "login-field-" + index;
    input.setAttribute("aria-labelledby", name.id);
    var box = document.createElement("span");
    box.className = "reveal";
    input.parentNode.insertBefore(box, input);
    box.appendChild(input);
    var button = document.createElement("button");
    button.type = "button";
    button.className = "eye";
    button.innerHTML = EYE;
    box.appendChild(button);
    var eye = { input: input, button: button };
    eyes.push(eye);
    eyeLabel(eye);
    button.addEventListener("click", function () {
      input.type = input.type === "password" ? "text" : "password";
      eyeLabel(eye);
    });
  });
  Object.keys(forms).forEach(function (key) { forms[key].addEventListener("submit", hidePasswords); });

  function open(name, text) {
    hidePasswords();
    Object.keys(forms).forEach(function (key) { forms[key].hidden = key !== name; });
    forms[name].querySelector(".message").textContent = text || "";
    root.hidden = false;
  }
  function close(ok) {
    loggedOut = false;
    root.hidden = true;
    var callback = done;
    done = null;
    if (callback) callback(ok);
  }
  // Sends the form's JSON; on success calls onOk, otherwise shows why (unless onRefused, given the status, takes it)
  function send(form, method, path, body, onOk, onRefused) {
    var button = form.querySelector("button[type=submit]");
    var message = form.querySelector(".message");
    button.disabled = true;
    message.textContent = "";
    fetch(path, { method: method, headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) })
      .then(function (r) {
        if (r.ok) { return (r.status === 204 ? Promise.resolve(null) : r.json()).then(onOk); }
        if (onRefused && onRefused(r.status)) return;
        if (r.status === 429) { message.textContent = t("tooMany"); return; }
        // A new password the server refuses (too short, too simple, the name in it): back to its field, which keeps
        // what was typed so it can be corrected
        return r.json().then(function (e) {
          message.textContent = e.message;
          if (/^PASSWORD_/.test(e.code || "") && form.password && form.password.autocomplete === "new-password") {
            form.password.focus();
          }
        },
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

  function backToLogin(name) {
    var form = forms.login;
    if (name) form.username.value = name;
    open("login");
    (form.username.value ? form.password : form.username).focus();
  }
  [forms.forgot, forms.verify, forms.gone].forEach(function (form) {
    form.querySelector("button.back").addEventListener("click", function () { backToLogin(); });
  });
  // A forgotten password (offered when the server can send mails): the account by its name or address, then its
  // address, partly hidden, typed in full. Every input gets a hidden address and every address the same answer, so
  // nothing here tells whether an account exists
  var resetOffered = false;
  var forgotAccount = "";
  var hiddenAddress = null;
  function verifyHint() {
    if (hiddenAddress !== null) forms.verify.querySelector(".hint").textContent = t("verifyHint", hiddenAddress);
  }
  function showForgot() {
    var form = forms.forgot;
    if (!form.account.value) form.account.value = forms.login.username.value.trim();
    open("forgot");
    form.account.focus();
  }
  forms.login.querySelector("a.forgot").addEventListener("click", function (event) {
    event.preventDefault();
    showForgot();
  });
  forms.forgot.addEventListener("submit", function (event) {
    event.preventDefault();
    var account = forms.forgot.account.value.trim();
    send(forms.forgot, "POST", "api/login/forgot", { account: account }, function (hint) {
      forgotAccount = account;
      hiddenAddress = hint.address;
      verifyHint();
      forms.verify.email.value = "";
      open("verify");
      forms.verify.email.focus();
    });
  });
  forms.verify.addEventListener("submit", function (event) {
    event.preventDefault();
    var form = forms.verify;
    send(form, "POST", "api/login/forgot/send", { account: forgotAccount, email: form.email.value.trim() }, function () {
      form.email.value = "";
      open("sent");
      forms.sent.querySelector("button").focus();
    });
  });
  forms.sent.addEventListener("submit", function (event) {
    event.preventDefault();
    backToLogin();
  });

  // The mail's link (#/reset/<token>): the account's name greets them, then the new password, twice. It doesn't log
  // in: the login follows (with its code, for two-factor login)
  var resetToken = null;
  var resetName = null;
  function resetHint() {
    if (resetName !== null) forms.reset.querySelector(".hint").textContent = t("resetFor", resetName);
  }
  function showGone() {
    resetToken = null;
    // Asking again needs the server's mails
    forms.gone.querySelector("button[type=submit]").hidden = !resetOffered;
    open("gone");
    forms.gone.querySelector(resetOffered ? "button[type=submit]" : "button.back").focus();
  }
  function showReset(linkToken) {
    // Out of the address bar and the history at once; the form keeps the token
    leaveInvitation();
    fetch("api/login/reset/" + encodeURIComponent(linkToken)).then(function (r) {
      if (r.status === 404) { showGone(); return; }
      if (!r.ok) {
        return r.json().then(function (e) { open("login", e.message); },
          function () { open("login", r.status === 429 ? t("tooMany") : t("failed", r.status)); });
      }
      return r.json().then(function (view) {
        var form = forms.reset;
        resetToken = linkToken;
        resetName = view.name;
        resetHint();
        form.username.value = view.name;
        form.password.value = form.repeat.value = "";
        open("reset");
        form.password.focus();
      }, function () { open("login", t("didntWork")); });
    }, function () { open("login", t("unreachable")); });
  }
  forms.reset.addEventListener("submit", function (event) {
    event.preventDefault();
    var form = forms.reset;
    if (!sameTwice(form)) return;
    send(form, "POST", "api/login/reset", { token: resetToken, password: form.password.value }, function () {
      form.password.value = form.repeat.value = "";
      resetToken = null;
      open("resetDone");
      forms.resetDone.querySelector("button").focus();
    }, function (status) {
      if (status !== 404) return false;
      form.password.value = form.repeat.value = "";
      showGone();
      return true;
    });
  });
  forms.gone.addEventListener("submit", function (event) {
    event.preventDefault();
    showForgot();
  });
  forms.resetDone.addEventListener("submit", function (event) {
    event.preventDefault();
    var name = resetName;
    resetName = null;
    backToLogin(name);
  });

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
    // Without a mail server, no way to a forgotten password
    if (options.passwordReset) {
      resetOffered = true;
      forms.login.querySelector("a.forgot").hidden = false;
      forms.gone.querySelector("button[type=submit]").hidden = false;
    }
    if (!options.singleSignOn) return;
    provider = options.singleSignOn;
    ssoLink();
  }, function () {});
  randogeddonTexts.onChange(function () {
    ssoLink();
    signupHint();
    verifyHint();
    resetHint();
    eyes.forEach(eyeLabel);
  });
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
  // A token that isn't one (a broken % escape) goes nowhere
  function decoded(part) { try { return decodeURIComponent(part); } catch (e) { return null; } }
  // The link in the address bar, if it is one of an invitation (#/invite/<token>), the CLI's login (#/login/<token>) or
  // a forgotten password (#/reset/<token>): its form, and true. A broken one of them is taken out of the address bar;
  // any other hash is left alone, and with it the form that is open
  function followLink() {
    var hash = location.hash;
    var invitation = /^#\/invite\/([^/]+)/.exec(hash);
    var invited = invitation && decoded(invitation[1]);
    if (invited) { showSignup(invited); return true; }
    var link = /^#\/login\/([^/]+)/.exec(hash);
    var linked = link && decoded(link[1]);
    if (linked) { useLink(linked); return true; }
    var reset = /^#\/reset\/([^/]+)/.exec(hash);
    var resetting = reset && decoded(reset[1]);
    if (resetting) { showReset(resetting); return true; }
    if (invitation || link || reset) leaveInvitation();
    return false;
  }
  // Logged out, the overlay showing (from show() until a login or hide()): a link opened in this tab while it is open
  // (pasted, or a mail client reusing the tab) takes effect at once. Logged in, the web app follows the hash instead.
  // The overlay's own changes don't loop: they only ever set "#/" (leaveInvitation, which also announces it), and
  // "#/" is none of the three links, so followLink leaves it, and the open form, as they are; an invitation's form
  // keeps its link in the address bar, which fires nothing until the hash changes again
  var loggedOut = false;
  window.addEventListener("hashchange", function () {
    if (loggedOut && !root.hidden) followLink();
  });
  return {
    show: function (text, onLoggedIn) {
      done = function (ok) { if (ok) onLoggedIn(); };
      loggedOut = true;
      if (followLink()) return;
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
    hide: function () {
      loggedOut = false;
      root.hidden = true;
    },
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
