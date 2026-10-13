// Builds app/src/main/assets/www from web/ (a copy of the FCon website's public/ folder).
// Run from the project root:  node tools/sync-web.mjs
import fs from 'node:fs';
import path from 'node:path';

const src = 'web';
const out = 'app/src/main/assets/www';

fs.rmSync(out, { recursive: true, force: true });
fs.cpSync(src, out, { recursive: true });
for (const f of ['sw.js', 'robots.txt', 'sitemap.xml', 'manifest.json']) {
  fs.rmSync(path.join(out, f), { force: true });
}

const indexPath = path.join(out, 'index.html');
let html = fs.readFileSync(indexPath, 'utf8');

function patch(find, replace) {
  if (!html.includes(find)) throw new Error('patch target missing: ' + find.slice(0, 70));
  html = html.replace(find, () => replace);
}

// 1. "Download" saves into Downloads/FCon through the app instead of building a blob in the browser.
patch(
  'if (!currentDownloadUrl || isDownloading) return;\n',
  `if (!currentDownloadUrl || isDownloading) return;
      if (window.FConApp) {
        const saved = FConApp.save(currentDownloadUrl.split('/').pop());
        if (saved.indexOf('ERR:') === 0) {
          showToast('Error', saved.slice(4));
        } else {
          showToast('Saved!', saved);
          document.getElementById('downloadBtn').innerHTML = '⬇ &nbsp; SAVE AGAIN';
        }
        return;
      }
`,
);

// 2. Same for "download again" in the Recent list.
patch(
  'if (!it || !it.url) return;\n',
  `if (!it || !it.url) return;
      if (window.FConApp) {
        const r = FConApp.save(it.url.split('/').pop());
        showToast(r.indexOf('ERR:') === 0 ? 'Error' : 'Saved!', r.replace(/^ERR:/, ''));
        return;
      }
`,
);

// 3. Show the real failure reason instead of a generic message.
patch(
  `evtSource.close();
              reject(new Error('Conversion failed. Please try again.'));
              return;`,
  `evtSource.close();
              try {
                const er = await (await fetch(API_BASE + '/result/' + jobId)).json();
                reject(new Error(er.error || 'Conversion failed. Please try again.'));
              } catch (_) {
                reject(new Error('Conversion failed. Please try again.'));
              }
              return;`,
);

// 4. Inside the app the page is not "an in-app browser": skip that warning (and the website's support banner).
patch(
  '(function detectInAppBrowser() {\n',
  '(function detectInAppBrowser() {\n      if (window.FConApp) return;\n',
);

// 5. Sidebar logo subtitle.
patch('<span class="side-logo-sub">CONVERTER</span>', '<span class="side-logo-sub">MEDIA CONVERTER</span>');

// 6. The server-wide "Activity" counters mean nothing in the app: remove that block and stop polling /stats.
{
  const a = html.indexOf('    <div class="side-section">\n      <div class="side-label">Activity</div>');
  const b = html.indexOf('    <div class="side-section side-recent">');
  if (a < 0 || b < a) throw new Error('patch target missing: Activity block');
  html = html.slice(0, a) + html.slice(b);
}
patch('async function updateServerStats() {\n', 'async function updateServerStats() {\n      if (window.FConApp) return;\n');

// 7. "App" section with the update button (talks to Updater.kt through the FConApp bridge).
patch(
  '  </aside>\n  <div class="side-overlay"',
  `    <div class="side-section" id="appUpdate">
      <div class="side-label">App</div>
      <div class="upd-version" id="updVersion"></div>
      <button class="upd-btn" id="updBtn" onclick="fconUpdateClick()">Check for updates</button>
      <div class="upd-msg" id="updMsg"></div>
    </div>
  </aside>
  <div class="side-overlay"`,
);
patch(
  '</head>',
  `<style>
    .upd-version { font-family: 'JetBrains Mono', monospace; font-size: 10.5px; color: var(--muted); margin-bottom: 10px; line-height: 1.5; word-break: break-word; }
    .upd-btn { width: 100%; padding: 11px 12px; background: var(--bg3); border: 1px solid var(--border); border-radius: 10px; color: var(--text);
      font-family: 'JetBrains Mono', monospace; font-size: 11px; letter-spacing: 1px; text-transform: uppercase; cursor: pointer; }
    .upd-btn:hover:not(:disabled) { border-color: var(--gold); color: var(--gold); }
    .upd-btn.go, .upd-btn.go:hover:not(:disabled) { background: var(--gold); border-color: var(--gold); color: #0a0a0b; font-weight: 600; }
    .upd-btn:disabled { opacity: .55; cursor: default; }
    .upd-msg { font-size: 12px; color: var(--muted); margin: 8px 2px 14px; line-height: 1.45; min-height: 1em; }
  </style>
</head>`,
);
patch(
  '</body>',
  `<script>
    // ─── APP UPDATE (Android app only) ────────────────────────────────────────────
    (function () {
      if (!window.FConApp) { var s = document.getElementById('appUpdate'); if (s) s.remove(); return; }
      var btn = document.getElementById('updBtn'), msg = document.getElementById('updMsg');
      var mode = 'check';
      // The downloader needs a few seconds to start, so refresh the version line a couple of times.
      var showVersion = function () { document.getElementById('updVersion').textContent = FConApp.version(); };
      showVersion(); setTimeout(showVersion, 6000); setTimeout(showVersion, 20000);
      // The app calls this with a JSON string describing the update state.
      window.__fconUpdate = function (json) {
        showVersion();
        var r = JSON.parse(json);
        btn.disabled = false; btn.classList.remove('go');
        switch (r.status) {
          case 'uptodate':
            mode = 'check'; btn.textContent = 'Check for updates';
            msg.textContent = 'You have the latest version (' + r.current + ').'; break;
          case 'available':
            mode = 'install'; btn.textContent = 'Install ' + r.latest; btn.classList.add('go');
            msg.textContent = 'Version ' + r.latest + ' is available' + (r.size ? ' (' + r.size + ' MB)' : '') + '. You have ' + r.current + '.'; break;
          case 'downloading':
            btn.disabled = true; btn.textContent = 'Downloading…';
            msg.textContent = 'Downloading ' + r.latest + ': ' + r.percent + '%'; break;
          case 'installing':
            mode = 'install'; btn.textContent = 'Install ' + r.latest; btn.classList.add('go');
            msg.textContent = 'The installer should be open. If nothing appeared, tap the button again.'; break;
          case 'needs_permission':
            mode = 'install'; btn.textContent = 'Install ' + r.latest; btn.classList.add('go');
            msg.textContent = 'Allow FCon to install apps in the settings screen that just opened, go back, and tap the button again.'; break;
          default:
            btn.textContent = mode === 'install' ? 'Try again' : 'Check for updates';
            msg.textContent = r.message || 'Something went wrong.';
        }
      };
      window.fconUpdateClick = function () {
        btn.disabled = true;
        if (mode === 'install') { msg.textContent = 'Starting…'; FConApp.installUpdate(); }
        else { msg.textContent = 'Checking…'; FConApp.checkUpdate(); }
      };
    })();
  </script>
</body>`,
);

fs.writeFileSync(indexPath, html);
fs.copyFileSync('tools/app-config.js', path.join(out, 'config.js'));
console.log('synced ->', out, fs.readdirSync(out).join(', '));
