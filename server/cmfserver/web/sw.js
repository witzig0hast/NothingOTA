/* CMF Health service worker: app shell offline, data always fresh from the network. */
const CACHE = "cmf-health-v1";
const SHELL = ["/", "/static/app.css", "/static/app.js", "/static/charts.js", "/static/icon.svg",
  "/static/vendor/maplibre/maplibre-gl.js", "/static/vendor/maplibre/maplibre-gl.css"];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});
self.addEventListener("activate", (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET" || url.origin !== location.origin || url.pathname.startsWith("/api/")) return;
  // network first (updates show up immediately), cache as fallback when offline
  e.respondWith(fetch(e.request).then((r) => {
    if (r.ok && (url.pathname === "/" || url.pathname.startsWith("/static/"))) {
      const copy = r.clone();
      caches.open(CACHE).then((c) => c.put(url.pathname === "/" ? "/" : e.request, copy));
    }
    return r;
  }).catch(() => caches.match(url.pathname.startsWith("/static/") ? e.request : "/")));
});
