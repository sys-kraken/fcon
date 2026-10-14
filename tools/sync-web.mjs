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

// 8. Smooth drawer: the page's blurred background blobs and backdrop-filter layers are re-blurred every frame while the
//    drawer slides over them (the WebView logs "tile memory limits exceeded" and the panel flickers). Use cheap static
//    gradients instead and give the drawer its own GPU layer.
patch(
  '</head>',
  `<style>
    * { backdrop-filter: none !important; -webkit-backdrop-filter: none !important; }
    .blob { display: none !important; }
    body {
      background:
        radial-gradient(circle at 50% 40%, rgba(232,176,75,0.11), transparent 55%),
        radial-gradient(520px 520px at 100% 0%, rgba(232,176,75,0.07), transparent 70%),
        radial-gradient(420px 420px at 0% 100%, rgba(126,184,247,0.05), transparent 70%),
        radial-gradient(rgba(255,255,255,0.028) 1px, transparent 1px) !important;
      background-size: auto, auto, auto, 30px 30px !important;
    }
    @media (max-width: 768px) {
      .sidebar { will-change: transform; transform: translate3d(-100%, 0, 0); box-shadow: none;
        transition: transform .25s ease, visibility 0s linear .25s; visibility: hidden; }
      .sidebar.open { transform: translate3d(0, 0, 0); visibility: visible; transition: transform .25s ease; }
      .side-overlay { display: block !important; visibility: hidden; opacity: 0; transition: opacity .25s ease, visibility 0s linear .25s; }
      .side-overlay.show { visibility: visible; opacity: 1; transition: opacity .25s ease; }
    }
    .toast-open { display: none; margin-top: 10px; padding: 9px 14px; background: var(--gold); color: #0a0a0b; border: 0; border-radius: 8px;
      font: inherit; font-weight: 600; letter-spacing: 1px; text-transform: uppercase; cursor: pointer; }
    .recent-item .recent-dl { flex-shrink: 0; margin-left: 6px; }
  </style>
</head>`,
);

// 9. Paste button: WebView has no clipboard-read permission, so read the clipboard through the app.
patch(
  'const text = await navigator.clipboard.readText();',
  `const text = window.FConApp ? FConApp.paste() : await navigator.clipboard.readText();
        if (!text) throw new Error('empty');`,
);
patch(
  "showToast('Paste manually', 'Press Ctrl+V / Cmd+V in the field.');",
  "window.FConApp ? showToast('Nothing to paste', 'Copy a video link first, then tap Paste.') : showToast('Paste manually', 'Press Ctrl+V / Cmd+V in the field.');",
);

// 10. "Open folder" in the saved popup and in the Recent list.
patch('function showToast(title, msg) {\n', "function showToast(title, msg) {\n      const ob = document.getElementById('toastOpen'); if (ob) ob.style.display = 'none';\n");
patch(
  "showToast('Saved!', saved);\n",
  "showToast('Saved!', saved);\n          window.fconOfferOpenFolder && fconOfferOpenFolder();\n",
);
patch(
  "showToast(r.indexOf('ERR:') === 0 ? 'Error' : 'Saved!', r.replace(/^ERR:/, ''));\n",
  "showToast(r.indexOf('ERR:') === 0 ? 'Error' : 'Saved!', r.replace(/^ERR:/, ''));\n        if (r.indexOf('ERR:') !== 0 && window.fconOfferOpenFolder) fconOfferOpenFolder();\n",
);
patch(
  '<button class="recent-dl" onclick="redownload(${i})"',
  `\${window.FConApp ? \`<button class="recent-dl" onclick="FConApp.openFolder()" title="Open folder" aria-label="Open folder"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg></button>\` : ''}
          <button class="recent-dl" onclick="redownload(\${i})"`,
);
patch(
  '(function () {\n      if (!window.FConApp) { var s = document.getElementById(\'appUpdate\'); if (s) s.remove(); return; }',
  `// Shows an "Open folder" button inside the toast for a few seconds (called after a file was saved).
    window.fconOfferOpenFolder = function () {
      var t = document.getElementById('toast');
      if (!t || !window.FConApp) return;
      var b = document.getElementById('toastOpen');
      if (!b) {
        b = document.createElement('button'); b.id = 'toastOpen'; b.className = 'toast-open'; b.textContent = 'Open folder';
        b.onclick = function () { FConApp.openFolder(); };
        t.appendChild(b);
      }
      b.style.display = 'inline-block';
      clearTimeout(_toastTimer); _toastTimer = setTimeout(function () { t.classList.remove('show'); }, 9000);
    };
    (function () {
      if (!window.FConApp) { var s = document.getElementById('appUpdate'); if (s) s.remove(); return; }`,
);

// 11. Trim: never show a stale length from the previous video while the new one is being looked up.
patch(
  "document.getElementById('resultInfo').textContent = 'Checking video quality…';\n",
  `document.getElementById('resultInfo').textContent = 'Checking video quality…';
      videoDuration = 0;
      document.getElementById('trimValues').textContent = 'Loading video length…';
      document.getElementById('trimMaxLabel').textContent = '…';
`,
);
patch(
  "console.error('[FCon info]', err);\n",
  "if (myInfoReq !== window.__infoReq) return;\n        console.error('[FCon info]', err);\n        document.getElementById('trimValues').textContent = 'Length unavailable';\n        document.getElementById('trimMaxLabel').textContent = '?';\n",
);
// Replies can arrive out of order (a slow first lookup must not overwrite the answer for the URL that is in the box now).
patch(
  'async function fetchVideoInfo(url) {\n',
  'async function fetchVideoInfo(url) {\n      const myInfoReq = window.__infoReq = (window.__infoReq || 0) + 1;\n',
);
patch(
  'const data = await res.json();\n\n        if (data.max_resolution) {',
  'const data = await res.json();\n        if (myInfoReq !== window.__infoReq) return;\n\n        if (data.max_resolution) {',
);

// 12. The notification is a card under the Download button (inside the converter card), not a popup that covers it.
{
  const a = html.indexOf('  <!-- TOAST -->');
  const end = '  </div>\n';
  const b = html.indexOf(end, html.indexOf('id="toastMsg"', a)) + end.length;
  if (a < 0 || b < end.length) throw new Error('patch target missing: toast block');
  const toastHtml = html.slice(a, b).replace('  <!-- TOAST -->\n', '');
  html = html.slice(0, a) + html.slice(b);
  patch(
    '            ⬇ &nbsp; DOWNLOAD FILE\n          </button>\n        </div>\n',
    '            ⬇ &nbsp; DOWNLOAD FILE\n          </button>\n        </div>\n\n        <!-- Status card: messages appear here, below the button -->\n' +
      toastHtml.replace(/^  /gm, '        '),
  );
}
patch(
  '</head>',
  `<style>
    .toast { position: static !important; transform: none !important; transition: none !important; width: auto !important;
      max-width: none !important; margin: 16px 0 0 !important; display: none; right: auto !important; bottom: auto !important; }
    .toast.show { display: block; animation: fadeUp 0.3s ease both; }
  </style>
</head>`,
);
patch(
  "t.classList.add('show');\n      clearTimeout(_toastTimer);",
  "t.classList.add('show');\n      if (window.FConApp) t.scrollIntoView({ behavior: 'smooth', block: 'nearest' });\n      clearTimeout(_toastTimer);",
);

// 13. "Report an issue" panel above Recent (answered by Firebase.kt through /api/report).
patch(
  '    <div class="side-section side-recent">',
  `    <div class="side-section" id="reportBox">
      <div class="side-label">Report an issue</div>
      <div class="rep-chips" id="repChips">
        <button type="button" class="rep-chip" data-c="Conversion failed">Conversion failed</button>
        <button type="button" class="rep-chip" data-c="Facebook conversion failed">Facebook conversion failed</button>
        <button type="button" class="rep-chip" data-c="Instagram conversion failed">Instagram conversion failed</button>
        <button type="button" class="rep-chip" data-c="Audio or video quality issue">Audio or video quality issue</button>
        <button type="button" class="rep-chip" data-c="Other issue">Other issue</button>
      </div>
      <textarea class="rep-text" id="repText" maxlength="1000" rows="3" placeholder="Tell us more (optional): which link, which format…"></textarea>
      <button class="upd-btn" id="repSend" type="button" disabled>Send</button>
      <div class="upd-msg" id="repMsg"></div>
    </div>
    <div class="side-section side-recent">`,
);

// 14. Announcement card at the top of the converter card (shown only while the admin has an announcement active).
patch(
  '      <div class="converter-card">\n',
  `      <div class="ann-card" id="annCard" hidden>
        <div class="ann-body">
          <div class="ann-title" id="annTitle">What's new</div>
          <div class="ann-msg" id="annMsg"></div>
          <div class="ann-status" id="annStatus"></div>
        </div>
        <button type="button" class="upd-btn go ann-btn" id="annBtn" hidden>Update</button>
        <button type="button" class="ann-x" id="annX" aria-label="Dismiss">&times;</button>
      </div>
      <div class="converter-card">
`,
);
patch(
  '</head>',
  `<style>
    .rep-chips { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 10px; }
    .rep-chip { padding: 7px 11px; background: var(--bg3); border: 1px solid var(--border); border-radius: 999px; color: var(--muted);
      font-family: inherit; font-size: 12px; line-height: 1.2; cursor: pointer; text-align: left; }
    .rep-chip.on { border-color: var(--gold); color: var(--gold); background: rgba(232,176,75,0.1); }
    .rep-text { width: 100%; box-sizing: border-box; margin-bottom: 8px; padding: 10px 12px; background: var(--bg3); border: 1px solid var(--border);
      border-radius: 10px; color: var(--text); font-family: inherit; font-size: 13px; resize: vertical; min-height: 64px; }
    .rep-text:focus { outline: none; border-color: var(--gold); }
    #reportBox .upd-msg { margin-bottom: 8px; }
    .ann-card { width: 100%; box-sizing: border-box; display: flex; align-items: center; gap: 10px; margin: 0 0 12px; padding: 8px 6px 8px 12px; border: 1px solid rgba(232,176,75,0.4);
      border-radius: 10px; background: rgba(232,176,75,0.08); }
    .ann-card[hidden] { display: none; }
    .ann-body { flex: 1; min-width: 0; }
    .ann-title { font-family: 'JetBrains Mono', monospace; font-size: 9.5px; letter-spacing: 1.5px; text-transform: uppercase; color: var(--gold); margin-bottom: 2px; }
    .ann-msg { font-size: 12.5px; line-height: 1.35; color: var(--text); white-space: pre-wrap; word-break: break-word; }
    .ann-btn { width: auto; flex-shrink: 0; margin: 0; padding: 7px 14px; font-size: 10.5px; }
    .ann-btn[hidden] { display: none; }
    .ann-status { font-size: 11px; color: var(--muted); margin-top: 3px; }
    .ann-status:empty { display: none; }
    .ann-x { flex-shrink: 0; align-self: flex-start; width: 26px; height: 26px; border: 0; background: transparent; color: var(--muted); font-size: 20px; line-height: 1; cursor: pointer; }
  </style>
</head>`,
);
patch(
  '</body>',
  `<script>
    // ─── REPORT AN ISSUE + ANNOUNCEMENT (Android app only) ───────────────────────
    (function () {
      if (!window.FConApp) { var rb = document.getElementById('reportBox'); if (rb) rb.remove(); var ac = document.getElementById('annCard'); if (ac) ac.remove(); return; }
      var chosen = '', send = document.getElementById('repSend'), msg = document.getElementById('repMsg'), text = document.getElementById('repText');
      document.getElementById('repChips').addEventListener('click', function (e) {
        var b = e.target.closest('.rep-chip'); if (!b) return;
        chosen = b.dataset.c;
        document.querySelectorAll('.rep-chip').forEach(function (x) { x.classList.toggle('on', x === b); });
        send.disabled = false; msg.textContent = '';
      });
      send.addEventListener('click', function () {
        if (!chosen) return;
        send.disabled = true; msg.textContent = 'Sending…';
        fetch('/api/report?body=' + encodeURIComponent(JSON.stringify({ category: chosen, details: text.value })))
          .then(function (r) { return r.json().then(function (j) { return { ok: r.ok, j: j }; }); })
          .then(function (r) {
            if (!r.ok) throw new Error(r.j.error || 'Could not send');
            msg.textContent = 'Thanks! Your report was sent.';
            text.value = ''; chosen = '';
            document.querySelectorAll('.rep-chip').forEach(function (x) { x.classList.remove('on'); });
          })
          .catch(function (e) { msg.textContent = e.message || 'Could not send. Check your connection.'; send.disabled = false; });
      });

      var card = document.getElementById('annCard'), btn = document.getElementById('annBtn'), st = document.getElementById('annStatus');
      var DISMISS = 'fcon-ann-dismissed', current = null, auto = false;
      function dismissed(id) { try { return localStorage.getItem(DISMISS) === id; } catch (e) { return false; } }
      document.getElementById('annX').addEventListener('click', function () {
        card.hidden = true;
        try { if (current) localStorage.setItem(DISMISS, current); } catch (e) {}
      });
      function poll() {
        fetch('/api/announcement').then(function (r) { return r.json(); }).then(function (a) {
          if (!a.active || !a.message || dismissed(a.id)) { card.hidden = true; current = null; return; }
          if (current !== a.id) st.textContent = '';
          current = a.id;
          document.getElementById('annTitle').textContent = a.title || "What's new";
          document.getElementById('annMsg').textContent = a.message;
          btn.hidden = !a.showUpdate;
          card.hidden = false;
        }).catch(function () {});
      }
      poll(); setInterval(poll, 5 * 60 * 1000);
      document.addEventListener('visibilitychange', function () { if (!document.hidden) poll(); });

      // The Update button reuses the app's update flow: check GitHub, then start the install straight away.
      var prev = window.__fconUpdate;
      window.__fconUpdate = function (json) {
        if (prev) prev(json);
        if (!auto) return;
        var r = JSON.parse(json);
        if (r.status === 'available') { st.textContent = 'Downloading ' + r.latest + '…'; FConApp.installUpdate(); return; }
        if (r.status === 'downloading') { st.textContent = 'Downloading ' + r.latest + ': ' + r.percent + '%'; return; }
        if (r.status === 'installing') { st.textContent = 'The installer should be open. Tap Install.'; btn.disabled = false; auto = false; return; }
        if (r.status === 'needs_permission') { st.textContent = 'Allow FCon to install apps in the settings screen that opened, go back, then tap Update again.'; btn.disabled = false; auto = false; return; }
        if (r.status === 'uptodate') st.textContent = 'You already have the latest version.';
        else st.textContent = r.message || 'Something went wrong. Please try again.';
        btn.disabled = false; auto = false;
      };
      btn.addEventListener('click', function () { auto = true; btn.disabled = true; st.textContent = 'Checking…'; FConApp.checkUpdate(); });
    })();
  </script>
</body>`,
);

fs.writeFileSync(indexPath, html);
fs.copyFileSync('tools/app-config.js', path.join(out, 'config.js'));
console.log('synced ->', out, fs.readdirSync(out).join(', '));
