// EchoTrace external end-to-end test client. Acts like a phone using only the public API.
// Uses synthetic device ids and a tiny generated JPEG. No personal data. Cleans up after itself.
const BASE = (process.env.ECHOTRACE_URL || 'https://echotrace-server.gamal-hametzaits.workers.dev').replace(/\/$/, '');
const BOT = process.env.ECHOTRACE_TEST_CODE || 'TEST42';
const AL = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const newId = () => Array.from({ length: 6 }, () => AL[Math.floor(Math.random() * AL.length)]).join('');
const dev = newId();
const JPEG = Buffer.from('/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAAMCAgICAgMCAgIDAwMDBAYEBAQEBAgGBgUGCQgKCgkICQkKDA8MCgsOCwkJDRENDg8QEBEQCgwSExIQEw8QEBD/yQALCAABAAEBAREA/8wABgAQEAX/2gAIAQEAAD8A0s8g/9k=', 'base64');
let pass = 0, fail = 0;
const results = [];
function check(name, ok, detail) {
  (ok ? pass++ : fail++);
  results.push(`${ok ? 'PASS' : 'FAIL'}  ${name}${ok ? '' : '  -> ' + detail}`);
  console.log(results[results.length - 1]);
}
const post = (path, body, headers = {}) => fetch(BASE + path, { method: 'POST', headers: { 'content-type': 'application/json', ...headers }, body: typeof body === 'string' || Buffer.isBuffer(body) ? body : JSON.stringify(body) });
const jget = async (path) => { const r = await fetch(BASE + path); return [r.status, await r.json().catch(() => ({}))]; };
const jpost = async (path, body, headers) => { const r = await post(path, body, headers); return [r.status, await r.json().catch(() => ({}))]; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function pollImage(want) {
  for (let i = 0; i < 10; i++) {
    const [, p] = await jget('/poll/' + dev);
    if (p.image && (!want || p.image.caption === want)) return p;
    await sleep(500);
  }
  return null;
}
async function run() {
  console.log(`EchoTrace e2e against ${BASE}, device ${dev}`);
  let s, b;
  [s, b] = await jpost('/register', { deviceId: 'bad' }); check('register rejects malformed id', s === 400, s);
  [s, b] = await jpost('/register', { deviceId: dev }); check('register new device', s === 200 && b.ok, s);
  [s, b] = await jget('/poll/' + dev); check('unpaired poll says paired:false', s === 200 && b.paired === false, JSON.stringify(b));
  [s, b] = await jpost('/connect', { myDeviceId: dev, partnerCode: dev }); check('cannot pair with yourself', s === 400, s);
  [s, b] = await jpost('/test-photo', { deviceId: dev }); check('test-photo refused before pairing the bot', s === 403, s);
  [s, b] = await jpost('/upload', JPEG, { 'x-device-id': dev, 'content-type': 'image/jpeg' }); check('upload refused while unpaired', s === 400, s);
  [s, b] = await jpost('/connect', { myDeviceId: dev, partnerCode: BOT, role: 'both' }); check('pair with test bot code', s === 200 && b.ok, `${s} ${JSON.stringify(b)}`);
  [s, b] = await jget('/poll/' + dev); check('poll shows paired with bot', s === 200 && b.paired === true && b.partner === BOT, JSON.stringify(b));
  const first = await pollImage();
  check('bot queued a welcome test photo', !!first && !!first.image.imageId, JSON.stringify(first));
  if (first && first.image) {
    const r = await fetch(`${BASE}/image/${first.image.imageId}?deviceId=${dev}`);
    const buf = Buffer.from(await r.arrayBuffer());
    check('download test photo is a JPEG', r.status === 200 && /image\/jpeg/.test(r.headers.get('content-type') || '') && buf[0] === 0xff && buf[1] === 0xd8, `${r.status} ${buf.length}B`);
    [s, b] = await jpost('/confirm-download', { deviceId: dev, imageId: first.image.imageId }); check('confirm-download ok', s === 200 && b.ok, s);
    [s, b] = await jget('/poll/' + dev); check('photo cleared after confirm', b.image === null, JSON.stringify(b.image));
  }
  const cap = 'e2e ' + Date.now();
  [s, b] = await jpost('/upload', JPEG, { 'x-device-id': dev, 'content-type': 'image/jpeg', 'x-caption': encodeURIComponent(cap) });
  check('send photo to bot', s === 200 && b.ok && b.testBot === true, `${s} ${JSON.stringify(b)}`);
  const echo = await pollImage('Echo: ' + cap);
  check('bot echoes the caption back', !!echo, 'no echo within 5s');
  if (echo && echo.image) await jpost('/confirm-download', { deviceId: dev, imageId: echo.image.imageId });
  [s, b] = await jpost('/upload', Buffer.alloc(0), { 'x-device-id': dev }); check('empty upload rejected', s === 400, s);
  [s, b] = await jpost('/upload', Buffer.alloc(8388609), { 'x-device-id': dev }); check('oversized upload (>8MB) rejected', s === 400, s);
  [s, b] = await jpost('/test-photo', { deviceId: dev }); check('on-demand test photo queued', s === 200 && b.queued, `${s} ${JSON.stringify(b)}`);
  [s, b] = await jget('/partner-status/' + dev); check('partner-status reports paired', s === 200 && b.paired === true, JSON.stringify(b));
}
(async () => {
  try { await run(); }
  catch (e) { check('run completed without exception', false, String(e)); }
  finally {
    try { const [s, b] = await jpost('/cleanup-test', { deviceId: dev }); check('cleanup removed test device', s === 200 && b.deleted, `${s} ${JSON.stringify(b)}`);
      const [, p] = await jget('/poll/' + dev); check('device gone after cleanup', p.paired === false, JSON.stringify(p)); }
    catch (e) { check('cleanup', false, String(e)); }
    console.log(`\n${pass} passed, ${fail} failed`);
    process.exit(fail ? 1 : 0);
  }
})();
