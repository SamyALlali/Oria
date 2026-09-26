// Small interaction regressions, using only Node's standard library and a fake DOM.
'use strict';
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
class Element {
  constructor(){this.children=[];this.value='';this.hidden=false;this.paused=0;this.width=100;}
  append(...children){this.children.push(...children);}
  replaceChildren(...children){this.children=children;}
  pause(){this.paused++;}
  focus(){this.focused=true;}
  removeAttribute(name){delete this[name];}
}
const elements=new Map();
const element=id=>{if(!elements.has(id))elements.set(id,new Element());return elements.get(id);};
const context=vm.createContext({console,setTimeout,clearTimeout,URL,URLSearchParams,Blob,AbortController,
  document:{getElementById:element,createElement:()=>new Element(),addEventListener:()=>{},activeElement:{tagName:'BODY'}},
  fetch:()=>new Promise(()=>{}),location:{search:''}});
vm.runInContext(fs.readFileSync(path.join(__dirname,'static/app.js'),'utf8'),context);
const actualShowFrame=vm.runInContext('showFrame',context);
(async()=>{
  vm.runInContext("showFrame=async()=>{};state.session={id:'test',originMs:0};",context);
  for(const zone of ['LEFT','CENTER','RIGHT']){
    context.sampleZone=zone;
    vm.runInContext("comparisonSummary({detectionSource:'recorded',skippedFrames:[],summary:{comparedFrames:1,differentFrames:[],announcementDifferentFrames:[],identityDifferentFrames:[],announcements:{A:[{zone:sampleZone,text:'Test',frameIndex:0,submittedAtMs:0}],B:[]}}})",context);
    const button=element('announcementsA').children[0].children[0];
    button.onclick();assert.equal(element('previewPan').value,zone,'Inspecter must retain the Kotlin zone');
  }
  vm.runInContext("state.session={id:'test',frames:[{}]};state.index=0;state.sessionVersion=1;state.playing=false;showFrame=()=>new Promise(resolve=>globalThis.finishFrame=resolve);",context);
  const play=element('play').onclick();
  vm.runInContext('state.sessionVersion=2;finishFrame();',context);await play;
  assert.equal(vm.runInContext('state.playing',context),false,'A stale rewind must not start a new session');
  vm.runInContext("globalThis.mutations=[];request=async url=>{mutations.push(url);return {};};refreshLibrary=async()=>{};state.session={id:'test'};",context);
  element('contextVideo').src='old-video';element('contextVideo').hidden=false;
  element('archiveSession').onclick();
  assert.equal(element('archiveConfirmation').hidden,false);
  element('cancelArchive').onclick();await element('confirmArchive').onclick();
  assert.equal(vm.runInContext('mutations.length',context),0,'Cancelled confirmation must not mutate storage');
  assert.equal(element('archiveConfirmation').hidden,true);
  assert.equal(vm.runInContext('state.session.id',context),'test','Cancelled confirmation must keep the session');
  element('archiveSession').onclick();
  vm.runInContext("state.sessionVersion++;state.session={id:'other'};",context);
  await element('confirmArchive').onclick();
  assert.equal(vm.runInContext('mutations.length',context),0,'Stale session confirmation must not archive either capture');
  assert.equal(element('archiveConfirmation').hidden,true);
  element('archiveSession').onclick();await element('confirmArchive').onclick();
  assert.equal(vm.runInContext('mutations[0]',context),'/api/session/other/archive');
  assert.equal(element('contextVideo').paused,1);
  assert.equal(element('contextVideo').hidden,true);
  assert.equal(element('contextVideo').src,undefined);
  assert.equal(element('videoControls').hidden,true);
  vm.runInContext("globalThis.frameSignals=[];request=(url,body,binary,signal)=>{frameSignals.push(signal);return new Promise((resolve,reject)=>signal.addEventListener('abort',()=>reject({name:'AbortError'})));};state.session={id:'test',frames:[{frameId:1,timeSeconds:0},{frameId:2,timeSeconds:1}]};state.mode='recorded';state.job=null;",context);
  const older=actualShowFrame(0);
  element('canvas').hidden=false;element('decisionJson').textContent='previous frame decision';
  element('rawDetails').textContent='previous frame detections';element('metrics').replaceChildren(new Element());
  const newer=actualShowFrame(1);
  assert.equal(element('canvas').hidden,true,'The previous image must be hidden while a new caption is displayed');
  assert.match(element('empty').textContent,/Chargement/);
  assert.match(element('decision').textContent,/Chargement/);
  assert.equal(element('decisionJson').textContent,'');
  assert.equal(element('rawDetails').textContent,'');
  assert.equal(element('metrics').children[1].children[1].textContent,'—','Old metrics must not be attributed to the pending image');
  assert.equal(vm.runInContext('frameSignals[0].aborted',context),true,'A new seek must cancel the older HTTP read');
  vm.runInContext('state.frameAbort.abort()',context);await Promise.all([older,newer]);
  vm.runInContext('globalThis.seeks=[];showFrame=async index=>seeks.push(index);',context);
  element('timeline').value=0;element('timeline').oninput();
  element('timeline').value=1;element('timeline').oninput();
  await new Promise(resolve=>setTimeout(resolve,70));
  assert.equal(vm.runInContext('JSON.stringify(seeks)',context),'[1]','Fast scrubbing should fetch only the latest image');
  vm.runInContext(`
    globalThis.exportCalls=[];globalThis.exportTimers=new Map();globalThis.timerIndex=0;
    setTimeout=(callback,delay)=>{exportTimers.set(++timerIndex,{callback,delay});return timerIndex;};
    clearTimeout=id=>exportTimers.delete(id);
    state.session={id:'original',displayName:'Old browser title',manifest:{sessionId:'capture'}};
    state.exportId=null;
    request=async(url,body,binary)=>{
      exportCalls.push({url,binary});
      if(binary)throw Error('ZIP export must never request a browser blob');
      if(url==='/api/session/original/export'){
        state.session={id:'another',displayName:'Another selection'};
        return {id:'opaque',displayNameSnapshot:'Snapshot title',sessionId:'original',state:'preparing'};
      }
      if(url==='/api/export/opaque')return {id:'opaque',displayNameSnapshot:'Snapshot title',sessionId:'original',state:'ready',archiveBytes:123456,downloadUrl:'/api/export/opaque/download',downloadName:'OriaLab-capture.zip'};
      return {state:'cancelled'};
    };
  `,context);
  await element('exportSession').onclick();
  await Promise.resolve();
  assert.equal(element('downloadExport').hidden,false);
  assert.equal(element('downloadExport').href,'/api/export/opaque/download');
  assert.equal(element('downloadExport').download,'OriaLab-capture.zip');
  assert.equal(element('exportTitle').textContent,'ZIP · Snapshot title','Server snapshot title must win over browser selection or a racing rename');
  assert.equal(vm.runInContext('state.session.id',context),'another');
  assert.equal(vm.runInContext('exportCalls.some(c=>c.url.endsWith("/download"))',context),false,'Only the native anchor may download the archive');
  assert.equal(vm.runInContext('[...exportTimers.values()][0].delay',context),10000);
  await element('cancelExport').onclick();
  assert.equal(element('downloadExport').hidden,true);
  assert.equal(vm.runInContext('exportTimers.size',context),0);
  assert.match(element('exportStatus').textContent,/reste géré par le navigateur/,'Cancel must not claim to stop an already launched native download');
  assert.equal(vm.runInContext('exportCalls.at(-1).url',context),'/api/export/opaque/cancel');

  vm.runInContext(`
    exportCalls=[];state.session={id:'pending',manifest:{sessionId:'pending'}};
    request=async(url,body)=>{
      exportCalls.push({url});
      if(url==='/api/session/pending/export')return new Promise(resolve=>globalThis.finishExport=resolve);
      return {};
    };
  `,context);
  const pendingExport=element('exportSession').onclick();
  await element('cancelExport').onclick();
  vm.runInContext("finishExport({id:'late',state:'preparing',displayNameSnapshot:'Late snapshot'});",context);
  await pendingExport;await Promise.resolve();
  assert.equal(vm.runInContext('exportCalls.at(-1).url',context),'/api/export/late/cancel','A prepare result arriving after cancellation must retire its own snapshot');
  assert.equal(element('downloadExport').hidden,true);
  assert.equal(vm.runInContext('state.exportId',context),null);
  assert.equal(vm.runInContext('exportTimers.size',context),0);
  console.log('UI interaction checks passed: pans, stale play, archive confirmation, video cleanup, loading isolation, HTTP abort, seek debounce, native ZIP download, snapshot title and stale export cancellation.');
})().catch(error=>{console.error(error);process.exitCode=1;});
