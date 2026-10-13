const CACHE_NAME = 'fcon-v5';
const ASSETS_TO_CACHE = [
  '/',
  '/index.html',
  '/style.css?v=11',
  '/assets/soundrip.png'
];

// Install event: Cache the UI files so the app shell loads instantly
self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE_NAME).then(cache => cache.addAll(ASSETS_TO_CACHE))
  );
  // Activate the new worker right away instead of waiting for every tab to close
  self.skipWaiting();
});

// Activate event: delete caches from older versions so visitors don't keep stale HTML/CSS
self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(k => k !== CACHE_NAME).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

// Fetch event: serve cached UI files; API calls (other origin, or non-GET) and config.js always hit the network
self.addEventListener('fetch', event => {
  if (event.request.method !== 'GET') return;
  const url = new URL(event.request.url);
  if (url.origin !== self.location.origin) return;
  if (url.pathname === '/config.js') return;

  event.respondWith(
    caches.match(event.request).then(cachedResponse => cachedResponse || fetch(event.request))
  );
});
