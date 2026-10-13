// Android app shim. The app serves this file in place of the website's config.js.
// It adapts the web page to the in-app API (see Api.kt); outside the app it does nothing.
(function () {
  if (!window.FConApp) return;

  var BASE = location.origin;
  window.FCON_API_BASE = BASE + '/api';
  var realFetch = window.fetch.bind(window);
  var sleep = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };

  // WebView interception cannot read POST bodies, so POSTs to the API become GETs with ?body=<json>.
  window.fetch = function (input, init) {
    var url = typeof input === 'string' ? input : input.url;
    if (url.indexOf(BASE + '/api/') === 0 && init && init.method === 'POST') {
      return realFetch(url + '?body=' + encodeURIComponent(init.body || '{}'), { cache: 'no-store' });
    }
    return realFetch(input, init);
  };

  // The page watches an EventSource; there is no server in the app, so poll the same values instead.
  function PollSource(url) {
    var self = this;
    self.closed = false;
    self.onmessage = null;
    self.onerror = null;
    var pollUrl = url.replace('/stream/', '/progress/');
    (async function () {
      var last = null;
      var misses = 0;
      while (!self.closed) {
        try {
          var r = await realFetch(pollUrl, { cache: 'no-store' });
          if (r.ok) {
            misses = 0;
            var t = (await r.text()).trim();
            if (t !== last) {
              last = t;
              if (self.onmessage) await self.onmessage({ data: t });
            }
            if (t === '100' || t === 'ERROR') { self.closed = true; return; }
          } else if (++misses > 20) {
            throw new Error('Lost the conversion job.');
          }
        } catch (e) {
          if (!self.closed && self.onerror) self.onerror(e);
          self.closed = true;
          return;
        }
        await sleep(500);
      }
    })();
  }
  PollSource.prototype.close = function () { this.closed = true; };
  window.EventSource = PollSource;
})();
