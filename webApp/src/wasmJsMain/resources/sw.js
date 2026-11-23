// Makes the dashboard installable (Android "Install app") and lets it open while offline with the last loaded app.
// Network first: a new build is always used when the server is reachable. Only the app itself is kept (its page,
// scripts, styles, .wasm, fonts, icons, manifest), never the API, the live stream or a download, and never an answer
// that says `no-store`; an older build's .wasm files go once newer ones are kept.
// v2: v1 kept every GET it saw (old builds, downloads); activating this drops it.
const CACHE = "randogeddon-v2";

// The app's files, by their path below the worker's scope (the page's folder: the app may be served under a path)
const SHELL = /^(?:|index\.html|manifest\.webmanifest|[^/]+\.(?:js|mjs|wasm|css)|(?:fonts|icons|composeResources)\/.+)$/;
// A build's .wasm files, named after their content: each build brings new ones
const HASHED_WASM = /^[0-9a-f]{16,}\.wasm$/;
// How many of those are kept: the current build's and the one before it
const KEEP_WASM = 4;

self.addEventListener("install", () => self.skipWaiting());
// Also drops caches of older versions (v1, and "ap-tracker-v1" from before the rename)
self.addEventListener("activate", (event) => event.waitUntil(
  caches.keys()
    .then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
    .then(() => self.clients.claim())
));

// [url]'s path below the worker's scope; null: outside it (another origin, or above the app)
function below(url) {
  const scope = new URL(self.registration.scope);
  if (url.origin !== scope.origin || !url.pathname.startsWith(scope.pathname)) return null;
  return url.pathname.slice(scope.pathname.length);
}

// The older builds' .wasm files out: a cache lists its entries in the order they were put, and every load puts the
// current ones again, so the first are the oldest. (Were a browser to list them otherwise, a current one may go: it is
// then fetched again, only offline it is missing until the next load.)
function trim(cache) {
  return cache.keys().then((requests) => {
    const wasm = requests.filter((request) => HASHED_WASM.test(below(new URL(request.url)) || ""));
    return Promise.all(wasm.slice(0, Math.max(0, wasm.length - KEEP_WASM)).map((request) => cache.delete(request)));
  });
}

self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET") return;
  const url = new URL(request.url);
  const path = below(url);
  // The API (relative to the page, as the app calls it), the live stream, downloads, the health check: not touched
  if (path === null || url.search || path.startsWith("api/") || !SHELL.test(path)) return;
  event.respondWith(
    fetch(request)
      .then((response) => {
        const store = response.status === 200 && response.type === "basic" &&
          !/no-store/i.test(response.headers.get("Cache-Control") || "");
        if (store) {
          const copy = response.clone();
          caches.open(CACHE)
            .then((cache) => cache.put(request, copy).then(() => (HASHED_WASM.test(path) ? trim(cache) : null)))
            .catch(() => {});
        }
        return response;
      })
      .catch(() => caches.match(request).then((cached) => cached || Response.error()))
  );
});
