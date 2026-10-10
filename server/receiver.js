// EchoTrace external receiver: a synthetic client that pairs with a real phone and verifies the photo it sends.
// Usage: node receiver.js <phone personal code> [minutes=10]
// Uses only the public API. Prints its own pairing code first.
const crypto = require('crypto');
const BASE = (process.env.ECHOTRACE_URL || 'https://echotrace-server.gamal-hametzaits.workers.dev').replace(/\/$/, '');
const AL = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const newId = () => Array.from({ length: 6 }, () => AL[crypto.randomInt(AL.length)]).join('');
const phone = (process.argv[2] || '').toUpperCase();
const minutes = Math.min(10, Number(process.argv[3] || 10) || 10);
if (!/^[A-Z2-9]{6}$/.test(phone)) { console.error('usage: node receiver.js <6-char phone code> [minutes]'); process.exit(2); }
const j = async (method, path, body) => { const r = await fetch(BASE + path, { method, headers: { 'content-type': 'application/json' }, body: body ? JSON.stringify(body) : undefined }); return [r.status, await r.json().catch(() => ({}))]; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
function jpegInfo(b) {
  if (b.length < 4 || b[0] !== 0xff || b[1] !== 0xd8) return { valid: false };
  let i = 2;
  while (i + 9 < b.length) {
    if (b[i] !== 0xff) { i++; continue; }
    const m = b[i + 1];
    if (m === 0xd8 || (m >= 0xd0 && m <= 0xd7) || m === 0x01) { i += 2; continue; }
    const len = b.readUInt16BE(i + 2);
    if (m >= 0xc0 && m <= 0xcf && m !== 0xc4 && m !== 0xc8 && m !== 0xcc) return { valid: true, height: b.readUInt16BE(i + 5), width: b.readUInt16BE(i + 7), endsWithEOI: b[b.length - 2] === 0xff && b[b.length - 1] === 0xd9 };
    i += 2 + len;
  }
  return { valid: true };
}
(async () => {
  let me, s, b;
  for (let t = 0; t < 5; t++) { me = newId(); [s, b] = await j('GET', '/partner-status/' + me); if (b.paired === false) break; }
  [s, b] = await j('POST', '/register', { deviceId: me });
  if (s !== 200) { console.log('FAILED register', s, JSON.stringify(b)); process.exit(1); }
  [s, b] = await j('POST', '/connect', { myDeviceId: me, partnerCode: phone, role: 'both' });
  console.log(`RECEIVER_CODE=${me}`);
  console.log(`connect to phone ${phone}: ${s} ${JSON.stringify(b)}`);
  console.log(`LIVE: waiting up to ${minutes} min for a photo from the phone. Enter ${me} as the partner code in the app, then send a photo.`);
  const end = Date.now() + minutes * 60000; let lastState = '';
  while (Date.now() < end) {
    [s, b] = await j('GET', '/poll/' + me);
    const st = JSON.stringify({ paired: b.paired, partner: b.partner, connected: b.partnerConnected });
    if (st !== lastState) { console.log(new Date().toISOString(), 'state', st); lastState = st; }
    if (b.image) {
      const img = b.image;
      const r = await fetch(`${BASE}/image/${img.imageId}?deviceId=${me}`);
      const buf = Buffer.from(await r.arrayBuffer());
      const info = jpegInfo(buf);
      const sha = crypto.createHash('sha256').update(buf).digest('hex');
      console.log(`RECEIVED photo: imageId=${img.imageId} caption=${JSON.stringify(img.caption)} sentAt=${new Date(img.sentAt).toISOString()} from partner=${b.partner}`);
      console.log(`download: HTTP ${r.status} content-type=${r.headers.get('content-type')} bytes=${buf.length} sha256=${sha}`);
      console.log(`jpeg: ${JSON.stringify(info)}`);
      const ok = r.status === 200 && info.valid && info.width > 0 && info.height > 0 && buf.length > 1000;
      const [cs, cb] = await j('POST', '/confirm-download', { deviceId: me, imageId: img.imageId });
      console.log(`confirm-download: ${cs} ${JSON.stringify(cb)}`);
      [s, b] = await j('GET', '/poll/' + me);
      console.log(`after confirm, image slot: ${JSON.stringify(b.image)}`);
      require('fs').writeFileSync('/tmp/received.jpg', buf);
      console.log(ok ? 'RESULT: PASS - valid JPEG received from the phone' : 'RESULT: FAIL - photo received but failed validation');
      process.exit(ok ? 0 : 1);
    }
    await sleep(3000);
  }
  console.log('RESULT: TIMEOUT - no photo arrived');
  process.exit(3);
})().catch((e) => { console.log('RESULT: ERROR', String(e)); process.exit(1); });
