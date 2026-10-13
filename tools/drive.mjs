// Drives the FCon page inside the app over Chrome DevTools (debug builds only).
//   adb forward tcp:9222 localabstract:webview_devtools_remote_<pid of app.fcon>
//   node tools/drive.mjs <url> <format> <quality|resolution> [trimStart trimEnd]
const [url, format = 'mp3', q = '192', trimStart, trimEnd] = process.argv.slice(2);
const targets = await (await fetch('http://127.0.0.1:9222/json')).json();
const page = targets.find((t) => t.type === 'page');
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((r) => (ws.onopen = r));
let id = 0;
const pending = new Map();
ws.onmessage = (m) => {
  const d = JSON.parse(m.data);
  if (d.id && pending.has(d.id)) { pending.get(d.id)(d); pending.delete(d.id); }
};
const evaluate = (expression) =>
  new Promise((resolve) => {
    const myId = ++id;
    pending.set(myId, resolve);
    ws.send(JSON.stringify({ id: myId, method: 'Runtime.evaluate', params: { expression, awaitPromise: true, returnByValue: true } }));
  }).then((d) => d.result?.result?.value ?? d.result?.exceptionDetails?.text);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const isVideo = ['mp4', 'mkv', 'webm'].includes(format);
console.log('set up:', await evaluate(`(() => {
  const set = (id, v) => { const e = document.getElementById(id); e.value = v; e.dispatchEvent(new Event('input', {bubbles:true})); e.dispatchEvent(new Event('change', {bubbles:true})); };
  set('urlInput', ${JSON.stringify(url)});
  set('formatSelect', ${JSON.stringify(format)});
  ${isVideo ? `set('resolutionSelect', ${JSON.stringify(q)});` : `set('qualitySelect', ${JSON.stringify(q)});`}
  return 'ok';
})()`));
await sleep(14000); // let the page ask /api/info (duration, max resolution)
console.log('resolution options after /info:', await evaluate(`Array.from(document.querySelectorAll('#resolutionSelect option')).filter(o=>!o.disabled&&!o.hidden).map(o=>o.value).join(',')`));
if (trimStart && trimEnd) {
  console.log('trim:', await evaluate(`(() => {
    const m = document.getElementById('modeSelect'); m.value = 'trim'; m.dispatchEvent(new Event('change', {bubbles:true}));
    for (const [id, v] of [['startSlider', ${Number(trimStart)}], ['endSlider', ${Number(trimEnd)}]]) {
      const s = document.getElementById(id); s.value = v; s.dispatchEvent(new Event('input', {bubbles:true})); s.dispatchEvent(new Event('change', {bubbles:true}));
    }
    return 'mode=trim ' + formatTime(document.getElementById('startSlider').value) + ' -> ' + formatTime(document.getElementById('endSlider').value);
  })()`));
}
console.log('click convert:', await evaluate(`(() => { document.querySelector('.convert-btn').click(); return 'clicked'; })()`));
const t0 = Date.now();
let state = '';
while (Date.now() - t0 < 240000) {
  await sleep(2000);
  state = await evaluate(`(() => { const r=document.getElementById('resultSection'); const t=document.getElementById('toastMsg')?.textContent; return (r&&r.classList.contains('active') ? 'RESULT: '+document.getElementById('resultTitle').textContent+' | '+document.getElementById('resultInfo').textContent : 'working') + ' | toast: ' + t; })()`);
  if (state.startsWith('RESULT') || /Failed/i.test(state)) break;
}
console.log(((Date.now() - t0) / 1000).toFixed(0) + 's ->', state);
if (state.startsWith('RESULT')) {
  console.log('click download:', await evaluate(`(() => { document.getElementById('downloadBtn').click(); return document.getElementById('toastMsg').textContent; })()`));
  await sleep(1500);
  console.log('toast after save:', await evaluate(`document.getElementById('toastTitle').textContent + ' ' + document.getElementById('toastMsg').textContent`));
}
ws.close();
