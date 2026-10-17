/* Run with Node and Playwright. Uses synthetic SMS and a private browser context. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const { chromium } = require('playwright');
const ready = {simState:'READY',simPresent:true,simReady:true,registered:true,serviceState:'IN_SERVICE',operator:'Test Mobile',technology:'LTE',signalLevel:3,signalDbm:-101,signalMetric:'RSRP'};
const state = {network:ready,messages:[{id:1,sender:'TEST',body:'Synthetic first message',sentAt:1700000000000,receivedAt:1700000000000}],delay:0,onRequest:null};
const html = fs.readFileSync(path.join(__dirname,'../app/assets/index.html'));
const server = http.createServer((req,res)=>{
  const url=new URL(req.url,'http://localhost');
  if(url.pathname==='/'){res.setHeader('Content-Type','text/html; charset=utf-8');res.end(html);return;}
  res.setHeader('Content-Type','application/json');
  if(url.pathname==='/api/status'){res.end(JSON.stringify({network:state.network}));return;}
  if(url.pathname==='/api/sms/delete'){
    let body='';req.on('data',chunk=>body+=chunk);req.on('end',()=>{const ids=JSON.parse(body).ids,before=state.messages.length;state.messages=state.messages.filter(m=>!ids.includes(m.id));res.end(JSON.stringify({deleted:before-state.messages.length}));});return;
  }
  if(!['/api/sms','/api/senders'].includes(url.pathname)){res.statusCode=404;res.end('{}');return;}
  const sorted=[...state.messages].sort((a,b)=>b.id-a.id);
  let records=sorted;
  if(url.pathname==='/api/senders'){
    const groups=new Map();for(const message of sorted){const group=groups.get(message.sender);if(group)group.count++;else groups.set(message.sender,{sender:message.sender,count:1,preview:message.body,receivedAt:message.receivedAt});}records=[...groups.values()];
  }else if(url.searchParams.has('sender'))records=records.filter(m=>m.sender===url.searchParams.get('sender'));
  const offset=Number(url.searchParams.get('offset')||0),limit=Number(url.searchParams.get('limit')||100);
  const output=JSON.stringify({[url.pathname==='/api/senders'?'senders':'messages']:records.slice(offset,offset+limit),total:records.length,globalTotal:sorted.length,hasMore:offset+limit<records.length,providerAvailable:true});
  const delay=state.delay;state.delay=0;if(state.onRequest){const callback=state.onRequest;state.onRequest=null;callback();}
  setTimeout(()=>res.end(output),delay);
});
function add(id){state.messages.push({id,sender:'TEST',body:'Synthetic message '+id,sentAt:1700000000000+id,receivedAt:1700000000000+id});}
(async()=>{
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const browser=await chromium.launch({headless:true,...(process.env.PLAYWRIGHT_CHANNEL?{channel:process.env.PLAYWRIGHT_CHANNEL}:{})});
  try{
    const page=await browser.newPage();
    const errors=[];page.on('pageerror',error=>errors.push(error.message));
    await page.addInitScript(initial=>{
      window.__sockets=[];window.__network=initial;window.__blockSockets=false;
      window.WebSocket=class {
        constructor(url){this.url=url;this.readyState=0;window.__sockets.push(this);setTimeout(()=>{if(window.__blockSockets){this.close();return;}this.readyState=1;this.onopen?.();this.emit({type:'ready',revision:0,network:window.__network});},20);}
        emit(message){if(message.network)window.__network=message.network;this.onmessage?.({data:JSON.stringify(message)});}
        close(){if(this.readyState===3)return;this.readyState=3;this.onclose?.();}
      };
    },ready);
    const emit=message=>page.evaluate(data=>window.__sockets.at(-1).emit(data),message);
    const count=number=>page.waitForFunction(n=>document.getElementById('count').textContent===String(n),number,{timeout:2500});
    await page.goto('http://127.0.0.1:'+server.address().port);
    await count(1);await page.getByText('SIM установлена',{exact:true}).waitFor();
    assert.match(await page.locator('#network-detail').textContent(),/-101 дБм RSRP/);
    await page.getByRole('tab',{name:'Все СМС',exact:true}).click();
    await page.locator('.message').waitFor();
    add(2);await emit({type:'messages',revision:1});await count(2);
    await page.getByRole('button',{name:'Выбрать',exact:true}).click();
    await page.locator('.pick input').first().check();
    add(3);await emit({type:'messages',revision:2});
    await page.locator('#live-notice').waitFor();
    assert.equal(await page.locator('#count').textContent(),'2');
    assert.equal(await page.locator('.pick input:checked').count(),1);
    await page.getByRole('button',{name:'Отменить выбор',exact:true}).click();await count(3);
    state.delay=350;const requestStarted=new Promise(resolve=>state.onRequest=resolve);
    await page.getByRole('button',{name:'Обновить',exact:true}).click();await requestStarted;
    add(4);await emit({type:'messages',revision:3});await count(4);
    const absent={...ready,simState:'ABSENT',simPresent:false,simReady:false,registered:false,signalLevel:null,signalDbm:null};
    state.network=absent;await emit({type:'network',network:absent});
    assert.equal(await page.locator('#sim-title').textContent(),'Нет SIM-карты');
    assert.equal(await page.locator('.signal-bars i.active').count(),0);
    assert.doesNotMatch(await page.locator('#network-detail').textContent(),/дБм/);
    const pin={...absent,simState:'PIN_REQUIRED',simPresent:true};await emit({type:'network',network:pin});
    assert.match(await page.locator('#sim-detail').textContent(),/PIN/);
    const unknown={...ready,signalLevel:null,signalDbm:null};await emit({type:'network',network:unknown});
    assert.match(await page.locator('#network-detail').textContent(),/недоступен/);
    assert.equal(await page.locator('.signal-bars i.active').count(),0);
    state.network=ready;await emit({type:'network',network:ready});
    const previousSockets=await page.evaluate(()=>window.__sockets.length);
    await page.evaluate(()=>window.__sockets.at(-1).close());
    await page.waitForFunction(n=>window.__sockets.length>n&&window.__sockets.at(-1).readyState===1,previousSockets,{timeout:3000});
    assert.match(await page.locator('#status-text').textContent(),/Живое обновление включено/);
    await page.evaluate(()=>{window.__blockSockets=true;window.__sockets.at(-1).close();});
    add(5);await page.getByRole('button',{name:'Обновить',exact:true}).click();await count(5);
    await page.waitForTimeout(400);add(6);await page.waitForFunction(()=>document.getElementById('count').textContent==='6',null,{timeout:12000});
    await page.getByRole('button',{name:'Выбрать',exact:true}).click();
    await page.locator('.pick input').first().check();await page.locator('#delete-selected').click();
    await page.locator('#delete-code').fill('3412');await page.locator('#delete-confirm').click();await count(5);
    await page.setViewportSize({width:390,height:844});
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
    assert.deepEqual(errors,[]);
    console.log('PASS: live insert, selection preservation, update while HTTP busy, SIM/PIN/unknown signal, reconnect, manual refresh, polling fallback, deletion and mobile layout');
  }finally{await browser.close();server.close();}
})().catch(error=>{console.error(error);server.close();process.exitCode=1;});
