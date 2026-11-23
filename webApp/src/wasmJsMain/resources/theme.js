// Loaded in index.html's head, before the first paint. A file of its own (not inline): the page's content policy
// only runs scripts from here.
// The user's theme for this page (the web app keeps its colours here, ap-theme-css), before the first paint; without
// one, page.css follows the device's light or dark setting
window.randogeddonTheme = (function () {
  var theme = null;
  var media = window.matchMedia("(prefers-color-scheme: dark)");
  function paint() {
    if (!theme) return;
    var colours = theme[theme.mode === "light" || (theme.mode !== "dark" && !media.matches) ? "light" : "dark"];
    Object.keys(colours).forEach(function (name) {
      document.documentElement.style.setProperty("--" + name, colours[name]);
    });
    document.querySelectorAll('meta[name="theme-color"]').forEach(function (m) { m.content = colours.bg; });
  }
  media.addEventListener("change", paint);
  try { theme = JSON.parse(localStorage.getItem("ap-theme-css")); } catch (e) {}
  paint();
  return function (json) {
    try { localStorage.setItem("ap-theme-css", json); } catch (e) {}
    theme = JSON.parse(json);
    paint();
  };
})();
