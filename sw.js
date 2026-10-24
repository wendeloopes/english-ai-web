const C='english-ai-v4',SHELL=['./','index.html','manifest.json','icon-192.png','icon-512.png','icon-maskable-512.png','vendor/litert-lm/0.18.0/assets.json'];
self.addEventListener('install',e=>{e.waitUntil((async()=>{
  const c=await caches.open(C);
  await c.addAll(SHELL);
  const manifestUrl=new URL('vendor/litert-lm/0.18.0/assets.json',self.registration.scope);
  const assets=await (await c.match(manifestUrl)).json();
  await c.addAll(assets);
  await self.skipWaiting();
})())});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(k=>Promise.all(k.filter(x=>x!==C).map(x=>caches.delete(x)))).then(()=>self.clients.claim()))});
// Cache first: avoid a background network request on every tap or asset read.
self.addEventListener('fetch',e=>{
  const r=e.request;if(r.method!=='GET'||r.headers.has('range'))return;
  e.respondWith(caches.match(r,{ignoreSearch:true}).then(hit=>{
    if(hit)return hit;
    return fetch(r).then(res=>{
      if(res&&(res.ok||res.type==='opaque'))caches.open(C).then(c=>c.put(r,res.clone())).catch(()=>{});
      return res;
    }).catch(()=>caches.match('index.html'));
  }));
});
