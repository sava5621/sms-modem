// Real device check: reads LED GPIO brightness and opens the SMS page in a fresh browser.
// It never sends/deletes SMS, moves the SIM, changes radio settings or saves message content.
const assert=require('node:assert/strict');
const fs=require('node:fs');
const {execFile}=require('node:child_process');
const {promisify}=require('node:util');
const {chromium}=require('playwright');
const run=promisify(execFile),delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const base='http://192.168.100.1:8088',samples=[];
const adb=process.env.ADB||'adb',serial=process.env.MODEM_SERIAL;
if(!serial||!/^[A-Za-z0-9_-]+$/.test(serial))throw new Error('Set MODEM_SERIAL to the USB serial');
async function state(){const response=await fetch(base+'/api/status');assert.equal(response.status,200);return response.json();}
async function sample(label,color){
  const {stdout}=await run(adb,['-s',serial,'shell','cat /sys/class/leds/4G_S/brightness; cat /sys/class/leds/4G_L2/brightness; cat /sys/class/leds/WIFI_S/brightness'],{timeout:5000});
  const rgb=stdout.trim().split(/\s+/).map(Number),status=await state();
  const wanted=color==='GREEN'?[0,255,0]:color==='BLUE'?[0,0,255]:[0,0,0];
  assert.deepEqual(rgb,wanted,label+' hardware channels');
  assert.equal(status.indicator.color,color,label+' policy');
  assert.equal(status.indicator.hardwareAvailable,true,label+' write access');
  samples.push({label,color,red:rgb[0],green:rgb[1],blue:rgb[2],remainingMs:status.indicator.visitRemainingMs});
}
async function at(start,ms){await delay(Math.max(0,ms-(performance.now()-start)));}
(async()=>{
  const initial=await state();
  assert.equal(initial.network.simReady,true,'This device test requires a ready SIM');
  assert.equal(initial.network.registered,true,'This device test requires network registration');
  await delay(initial.indicator.visitRemainingMs+300);
  await sample('healthy idle','OFF');
  for(const path of ['/','/index.html'])assert.equal((await fetch(base+path,{method:'HEAD'})).status,200);
  assert.equal((await fetch(base+'/not-a-page')).status,404);
  await sample('HEAD, status API and 404 do not pulse','OFF');
  const browser=await chromium.launch({channel:process.env.PLAYWRIGHT_CHANNEL||'msedge',headless:true});
  const errors=[],frames={};let closes=0;
  try {
    const page=await browser.newPage();
    page.on('pageerror',error=>errors.push(error.message));
    page.on('websocket',socket=>{
      socket.on('framereceived',frame=>{const message=JSON.parse(frame.payload);frames[message.type]=(frames[message.type]||0)+1;});
      socket.on('close',()=>closes++);
    });
    let start=performance.now();
    await page.goto(base+'/');
    await page.waitForFunction(()=>document.getElementById('status-text').textContent.includes('Живое обновление включено'));
    await at(start,300);await sample('page opened','GREEN');
    await at(start,4400);await sample('before five seconds','GREEN');
    await at(start,5300);await sample('after five seconds with live socket','OFF');
    await page.locator('#refresh').click();await delay(300);
    await sample('manual SMS refresh','OFF');
    await at(start,21500);
    assert.ok(frames.heartbeat>=1,'Live heartbeat still works');
    assert.equal(closes,0,'No unexpected socket closes');
    await sample('background heartbeat and network events','OFF');
    const count=await page.locator('#count').textContent();
    await page.goto(base+'/index.html');
    start=performance.now();
    await delay(300);await sample('index.html opened','GREEN');
    await at(start,3000);await page.reload();
    const repeated=performance.now();
    await at(repeated,2300);await sample('reopening extends beyond first deadline','GREEN');
    await at(repeated,5300);await sample('five seconds after last opening','OFF');
    assert.deepEqual(errors,[],'No browser script errors');
    const result={passed:true,messageCount:Number(count),frames,pageErrors:errors,samples,physicalSimRemoval:false,radioSettingsChanged:false,savedSmsContent:false};
    if(process.env.INDICATOR_REPORT)fs.writeFileSync(process.env.INDICATOR_REPORT,JSON.stringify(result,null,2)+'\n');
    console.log(JSON.stringify(result));
  }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
