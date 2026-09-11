// EchoTrace server - Cloudflare Worker + KV
// Pairing by 6-char device codes, one photo at a time, delete-on-download-confirm.
const json = (o, s=200) => new Response(JSON.stringify(o), {status:s, headers:{'content-type':'application/json; charset=utf-8'}});
const CODE = /^[A-Z2-9]{6}$/;

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const p = url.pathname;
    const kv = env.ECHOTRACE;
    try {
      if (req.method === 'POST' && p === '/register') {
        const b = await req.json();
        if (!CODE.test(b.deviceId||'')) return json({error:'bad deviceId'},400);
        const now = Date.now();
        const dev = await kv.get('dev:'+b.deviceId, 'json');
        await kv.put('dev:'+b.deviceId, JSON.stringify({createdAt: dev?.createdAt || now, lastSeen: now}));
        return json({ok:true});
      }
      if (req.method === 'POST' && p === '/connect') {
        const b = await req.json();
        const me = (b.myDeviceId||'').toUpperCase(), partner = (b.partnerCode||'').toUpperCase();
        const role = ['both','sender','viewer'].includes(b.role) ? b.role : 'both';
        if (!CODE.test(me) || !CODE.test(partner)) return json({error:'bad code'},400);
        if (me === partner) return json({error:'cannot pair with yourself'},400);
        if (!await kv.get('dev:'+me)) return json({error:'register first'},400);
        if (!await kv.get('dev:'+partner)) return json({error:'partner code not found'},404);
        // one person only, for the life of the install
        const myConn = await kv.get('conn:'+me, 'json');
        if (myConn) {
          if (myConn.partner === partner) return json({ok:true, already:true});
          return json({error:'already paired'},409);
        }
        const partnerConn = await kv.get('conn:'+partner, 'json');
        if (partnerConn && partnerConn.partner !== me) return json({error:'partner already paired'},409);
        await kv.put('conn:'+me, JSON.stringify({partner, role, createdAt: Date.now()}));
        return json({ok:true});
      }
      if (req.method === 'POST' && p === '/upload') {
        const me = (req.headers.get('x-device-id')||'').toUpperCase();
        let caption=''; try { caption = decodeURIComponent(req.headers.get('x-caption')||'').slice(0,50); } catch(e) {}
        if (!CODE.test(me)) return json({error:'bad deviceId'},400);
        const conn = await kv.get('conn:'+me, 'json');
        if (!conn) return json({error:'not paired'},400);
        if (conn.role === 'viewer') return json({error:'viewer role cannot send'},403);
        const bytes = await req.arrayBuffer();
        if (!bytes.byteLength) return json({error:'empty image'},400);
        if (bytes.byteLength > 8*1024*1024) return json({error:'image too large'},413);
        const imageId = crypto.randomUUID();
        const sentAt = Date.now();
        // one photo at a time: overwrite anything waiting for the partner
        await kv.put('img:'+conn.partner, bytes);
        await kv.put('meta:'+conn.partner, JSON.stringify({imageId, caption, sentAt, from: me}));
        await kv.put('dev:'+me, JSON.stringify({...(await kv.get('dev:'+me,'json')), lastSeen: Date.now()}));
        return json({ok:true, imageId, sentAt});
      }
      if (req.method === 'GET' && p.startsWith('/poll/')) {
        const me = p.split('/')[2].toUpperCase();
        if (!CODE.test(me)) return json({error:'bad deviceId'},400);
        const dev = await kv.get('dev:'+me, 'json');
        if (dev) await kv.put('dev:'+me, JSON.stringify({...dev, lastSeen: Date.now()}));
        const conn = await kv.get('conn:'+me, 'json');
        if (!conn) return json({paired:false});
        let partnerConnected = true;
        const pdev = await kv.get('dev:'+conn.partner, 'json');
        if (!pdev || Date.now() - pdev.lastSeen > 5*24*3600*1000) partnerConnected = false;
        const meta = await kv.get('meta:'+me, 'json');
        return json({paired:true, role:conn.role, partnerConnected, image: meta ? {imageId:meta.imageId, caption:meta.caption, sentAt:meta.sentAt} : null});
      }
      if (req.method === 'GET' && p.startsWith('/image/')) {
        const me = (url.searchParams.get('deviceId')||'').toUpperCase();
        if (!CODE.test(me)) return json({error:'bad deviceId'},400);
        const meta = await kv.get('meta:'+me, 'json');
        if (!meta || meta.imageId !== p.split('/')[2]) return json({error:'no such image'},404);
        const bytes = await kv.get('img:'+me, 'arrayBuffer');
        if (!bytes) return json({error:'gone'},404);
        return new Response(bytes, {headers:{'content-type':'image/jpeg','cache-control':'no-store'}});
      }
      if (req.method === 'POST' && p === '/confirm-download') {
        const b = await req.json();
        const me = (b.deviceId||'').toUpperCase();
        const meta = await kv.get('meta:'+me, 'json');
        if (meta && meta.imageId === b.imageId) {
          await kv.delete('img:'+me);
          await kv.delete('meta:'+me);
        }
        return json({ok:true});
      }
      if (req.method === 'GET' && p.startsWith('/partner-status/')) {
        const me = p.split('/')[2].toUpperCase();
        const conn = await kv.get('conn:'+me, 'json');
        if (!conn) return json({paired:false});
        const pdev = await kv.get('dev:'+conn.partner, 'json');
        const connected = !!(pdev && Date.now() - pdev.lastSeen <= 5*24*3600*1000);
        return json({paired:true, partnerConnected: connected});
      }
      return json({error:'not found'},404);
    } catch (e) {
      return json({error:'server error', detail:String(e).slice(0,200)},500);
    }
  }
};
