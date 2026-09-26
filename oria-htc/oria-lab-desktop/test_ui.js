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
const context=vm.createContext({console,setTimeout,clearTimeout,URL,URLSearchParams,Blob,
  document:{getElementById:element,createElement:()=>new Element(),addEventListener:()=>{},activeElement:{tagName:'BODY'}},
  fetch:()=>new Promise(()=>{}),location:{search:''}});
vm.runInContext(fs.readFileSync(path.join(__dirname,'static/app.js'),'utf8'),context);
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
  console.log('UI interaction checks passed: 3 pans, stale play, archive confirmation and video cleanup.');
})().catch(error=>{console.error(error);process.exitCode=1;});
