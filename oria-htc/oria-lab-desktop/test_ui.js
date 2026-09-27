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
  vm.runInContext("state.session={id:'test',frames:[{timeSeconds:0}]};state.index=0;state.sessionVersion=1;state.playing=false;showFrame=()=>new Promise(resolve=>globalThis.finishFrame=resolve);",context);
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
  element('integritySummary').textContent='OLD_INTEGRITY';element('frameIntegrity').textContent='OLD_ISSUE';
  element('decisionJson').textContent='OLD_DECISION';element('frameSubtitle').textContent='OLD_FRAME';
  element('position').textContent='4 / 4';
  element('archiveSession').onclick();await element('confirmArchive').onclick();
  assert.equal(vm.runInContext('mutations[0]',context),'/api/session/other/archive');
  assert.equal(element('contextVideo').paused,1);
  assert.equal(element('contextVideo').hidden,true);
  assert.equal(element('contextVideo').src,undefined);
  assert.equal(element('videoControls').hidden,true);
  for(const id of ['integritySummary','frameIntegrity','decisionJson','frameSubtitle'])assert.equal(element(id).textContent,'','Archiving must clear the previously selected capture');
  assert.equal(element('position').textContent,'0 / 0');
  assert.equal(element('canvas').hidden,true);
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
  vm.runInContext(`
    globalThis.imageRequests=[];
    globalThis.Image=class {set src(value){imageRequests.push(value);}};
    state.mode='recorded';state.job=null;state.jobBusy=false;state.session={id:'corrupt',frames:[
      {frameId:1,sourceLine:1,timeSeconds:0},{frameId:null,sourceLine:2,timeSeconds:null},
      {frameId:3,sourceLine:3,timeSeconds:2},{frameId:4,sourceLine:4,timeSeconds:3}],
      integrity:{frameEntryCount:4,invalidFrameCount:1,missingImageCount:2,invalidEventCount:0,
        recordedReplayAllowed:false,macReplayAllowed:false}};
    request=async url=>({detections:[{classId:0,confidence:.9,box:{left:0,top:0,right:1,bottom:1}}],
      events:[{type:'decision',reason:'MUST_NOT_ATTACH'}],audioEvents:[{type:'MUST_NOT_ATTACH'}],
      recordedInference:{inferenceMs:42,rawOutput:'MUST_NOT_ATTACH'},
      integrity:url.endsWith('/1')?{entryValid:false,imageAvailable:false,analysisStatus:'invalid',issues:['JSON invalide ligne2']}:
        {entryValid:true,imageAvailable:!url.endsWith('/2'),analysisStatus:'available',issues:url.endsWith('/2')?['PNG absente']:[]}});
  `,context);
  await actualShowFrame(1);
  assert.equal(element('position').textContent,'2 / 4','Invalid rows retain their original positions');
  assert.match(element('frameSubtitle').textContent,/Ligne source 2.*non identifiée/);
  assert.equal(vm.runInContext('imageRequests.length',context),0,'Unavailable PNG must not make a misleading image request');
  assert.equal(element('canvas').hidden,true);
  assert.equal(element('metrics').children[1].children[1].textContent,'Non exploitables','Invalid detections must not be represented by zero detections');
  assert.equal(element('decisionJson').textContent,'{}');
  assert.equal(element('audioJson').textContent,'[]');
  assert.doesNotMatch(element('rawDetails').textContent,/MUST_NOT_ATTACH/);
  vm.runInContext(`
    globalThis.recordedRequest=request;
    request=async url=>url.startsWith('/api/job/')?{detections:[],policy:{unsafe:'UNSAFE_CALCULATED_POLICY'},rawOutput:'UNSAFE_CALCULATED_RAW'}:recordedRequest(url);
    state.job='old';state.jobKind='recompute';state.mode='recomputed';
  `,context);
  await actualShowFrame(1);
  assert.equal(element('rawDetails').textContent,'{}','Calculated details must also be suppressed on an invalid recorded entry');
  assert.equal(element('decisionJson').textContent,'{}');
  vm.runInContext("state.job=null;state.mode='recorded';request=recordedRequest;refreshReplayControls();",context);
  assert.equal(element('play').disabled,true,'An invalid time cannot become a fabricated playback delay');
  assert.equal(element('compare').disabled,true);
  assert.equal(element('recompute').disabled,true);
  await actualShowFrame(2);
  assert.equal(element('position').textContent,'3 / 4');
  assert.match(element('empty').textContent,/position d’origine conservée/);
  assert.equal(element('metrics').children[1].children[1].textContent,'1','Valid recorded detections remain inspectable even if the PNG is missing');
  await actualShowFrame(3);
  assert.equal(vm.runInContext('imageRequests.at(-1)',context),'/api/session/corrupt/image/3','Navigation uses the original position after invalid entries');
  assert.equal(element('frameIntegrity').textContent,'','Prior diagnostics must not remain on the next valid entry');
  vm.runInContext("state.session.integrity.recordedReplayAllowed=true;state.session.frames=[{timeSeconds:0},{timeSeconds:1}];",context);
  element('compareSource').value='recorded';element('compareSource').onchange();
  assert.equal(element('compare').disabled,false);
  assert.equal(element('recompute').disabled,true);
  element('compareSource').value='mac';element('compareSource').onchange();
  assert.equal(element('compare').disabled,true,'Missing visual data blocks an ONNX comparison');
  vm.runInContext('state.jobBusy=true',context);element('compareSource').value='recorded';element('compareSource').onchange();
  assert.equal(element('compare').disabled,true,'Changing source must not re-enable controls during a job');
  vm.runInContext(`
    state.jobBusy=false;state.mode='comparison';state.job='comparison';state.jobKind='comparison';
    state.session={id:'unanalysed',originMs:0,frames:[{frameId:1,sourceLine:1,timeSeconds:0}]};
    request=async url=>url.startsWith('/api/job/')?{detections:[],decisionDifferent:false,
      variants:{A:{policy:{skipped:true}},B:{policy:{skipped:true}}}}:
      {detections:[],events:[],audioEvents:[],integrity:{entryValid:true,imageAvailable:true,analysisStatus:'not_recorded',issues:[]}};
  `,context);
  await actualShowFrame(0);
  assert.match(element('decision').textContent,/Position non comparée/,'Two skipped policies are not equal observed decisions');
  vm.runInContext("comparisonSummary({detectionSource:'recorded',skippedFrames:[0],summary:{comparedFrames:0,differentFrames:[],announcementDifferentFrames:[],identityDifferentFrames:[],announcements:{A:[],B:[]}}})",context);
  assert.match(element('comparisonSummary').textContent,/Aucune comparaison possible/);
  vm.runInContext(`
    globalThis.surfaceCalls=[];state.surfaceModel={installed:true};state.surfaceBusy=false;
    state.session={id:'surface-old',frames:[{timeSeconds:0}],integrity:{macReplayAllowed:true}};
    request=async(url,body)=>{surfaceCalls.push(url);if(url==='/api/surfaces/analyze')return new Promise(resolve=>globalThis.finishSurfaceStart=resolve);return {};};
  `,context);
  const surfaceStart=element('surfaceAnalyze').onclick();
  vm.runInContext("resetSurfaces();state.session={id:'surface-new',frames:[{timeSeconds:0}]};finishSurfaceStart({jobId:'late-surface'});",context);
  await surfaceStart;await Promise.resolve();
  assert.equal(vm.runInContext('surfaceCalls.at(-1)',context),'/api/surfaces/jobs/late-surface/cancel','A surfaces job arriving after selection changes cancels itself');
  assert.equal(vm.runInContext('state.surfaceJob',context),null);
  vm.runInContext(`
    state.session={id:'surface-old',frames:[{timeSeconds:0}]};state.surfaceJob='selected-surface';
    request=async()=>new Promise(resolve=>globalThis.finishSurfaceFrame=resolve);
  `,context);
  const surfaceFrame=vm.runInContext('showSurfaceFrame(0)',context);
  vm.runInContext("state.session={id:'surface-new',frames:[{timeSeconds:0}]};finishSurfaceFrame({sessionId:'surface-old',frameIndex:0,segmentation:{},policy:{proposal:{text:'STALE SURFACE'}}});",context);
  await surfaceFrame;
  assert.doesNotMatch(element('surfaceProposal').textContent,/STALE/,'Old capture surface evidence must not appear on the new capture');
  assert.equal(element('surfaceCanvas').hidden,true);
  vm.runInContext(`
    state.session={id:'surface-new',frames:[{timeSeconds:0}]};
    request=async()=>({sessionId:'wrong-identity',frameIndex:0,segmentation:{},policy:{}});
  `,context);
  await vm.runInContext('showSurfaceFrame(0)',context);
  assert.match(element('surfaceFrameStatus').textContent,/Identité/,'Server identity must match the selected PNG');
  assert.equal(element('surfaceCanvas').hidden,true);
  vm.runInContext("state.surfaceModel={installed:false};refreshSurfaceControls();",context);
  assert.equal(element('surfaceAnalyze').disabled,true,'Absent surfaces weights may not trigger an implicit download');
  element('surfaceView').value='depth';vm.runInContext('refreshSurfaceLegend()',context);
  assert.equal(element('surfaceSemanticLegend').hidden,true,'A relative heatmap must not retain the wall color legend');
  assert.equal(element('surfaceModeLegend').hidden,false);
  assert.match(element('surfaceModeLegend').textContent,/Bleu.*jaune.*aucune distance/);
  assert.match(element('surfaceCanvas').ariaLabel,/relief relatif/);
  element('surfaceView').value='candidate';vm.runInContext('refreshSurfaceLegend()',context);
  assert.match(element('surfaceModeLegend').textContent,/Rouge.*régions candidates/);
  assert.equal(element('surfaceSemanticLegend').hidden,true);
  element('surfaceView').value='segmentation';vm.runInContext('refreshSurfaceLegend()',context);
  assert.equal(element('surfaceSemanticLegend').hidden,false);
  assert.equal(element('surfaceModeLegend').hidden,true);
  vm.runInContext("request=async()=>({installed:true,message:'Segmentation prête.',relativeDepth:{installed:false}});",context);
  await vm.runInContext('refreshSurfaceModel()',context);
  assert.match(element('surfaceModelStatus').textContent,/Relief relatif : indisponible.*aucune proposition obstacle/);
  vm.runInContext("request=async()=>({installed:true,message:'Segmentation prête.',relativeDepth:{installed:true}});",context);
  await vm.runInContext('refreshSurfaceModel()',context);
  assert.match(element('surfaceModelStatus').textContent,/Relief relatif : modèle local installé/);
  vm.runInContext(`
    globalThis.surfaceNavigation=[];
    showFrame=async index=>{surfaceNavigation.push(index);state.index=index;refreshSurfaceNavigation();};
    state.session={id:'navigation',frames:[{timeSeconds:0},{timeSeconds:1},{timeSeconds:2}]};
    state.index=0;state.playing=true;refreshSurfaceNavigation();
  `,context);
  assert.equal(element('surfacePosition').textContent,'Image 1 / 3');
  assert.equal(element('surfacePrevious').disabled,true);
  assert.equal(element('surfaceNext').disabled,false);
  await element('surfacePrevious').onclick();
  assert.equal(vm.runInContext('surfaceNavigation.length',context),0,'At the first frame the local transport must not fetch an out-of-bounds frame');
  await element('surfaceNext').onclick();
  assert.equal(vm.runInContext('state.index',context),1,'The local transport must update the existing global selection');
  assert.equal(vm.runInContext('state.playing',context),false,'A local manual step stops playback');
  assert.equal(element('surfacePosition').textContent,'Image 2 / 3');
  await element('surfaceNext').onclick();await element('surfaceNext').onclick();
  assert.equal(vm.runInContext('JSON.stringify(surfaceNavigation)',context),'[1,2]');
  assert.equal(element('surfaceNext').disabled,true);
  await element('surfacePrevious').onclick();
  assert.equal(vm.runInContext('state.index',context),1);
  vm.runInContext('state.session=null;refreshSurfaceNavigation();',context);
  assert.equal(element('surfacePosition').textContent,'Image 0 / 0');
  assert.equal(element('surfacePrevious').disabled,true);assert.equal(element('surfaceNext').disabled,true);
  await element('surfaceNext').onclick();
  assert.equal(vm.runInContext('surfaceNavigation.length',context),3,'No session means no navigation request');
  vm.runInContext(`
    globalThis.surfaceZoneResult={zones:[{zone:'LEFT',wallFraction:.1,wallMeanConfidence:.9}]};
    globalThis.surfaceZoneEvidence={status:'ok',zones:[{zone:'LEFT',obstructionFraction:.8,unrecognizedFraction:.4,depthRelativeSupport:.75},{zone:'CENTER',obstructionFraction:0,unrecognizedFraction:0,depthRelativeSupport:0}]};
  `,context);
  element('surfaceView').value='candidate';vm.runInContext('renderSurfaceZones(surfaceZoneResult,surfaceZoneEvidence)',context);
  assert.match(element('surfaceZones').children[0].children[1].textContent,/Candidats hors boîtes YOLO : 40.0 %.*zone analysée/);
  assert.match(element('surfaceZones').children[0].children[1].textContent,/relief relatif : 75.0 % des pixels éligibles/);
  assert.doesNotMatch(element('surfaceZones').children[0].children[1].textContent,/Mur/);
  assert.match(element('surfaceZones').children[1].children[1].textContent,/non applicable.*aucune surface éligible/);
  assert.match(element('surfaceZones').children[2].children[1].textContent,/indisponibles/,'A missing zone must not be converted into a zero fraction');
  element('surfaceView').value='depth';vm.runInContext("renderSurfaceZones(surfaceZoneResult,{status:'unavailable',zones:null})",context);
  for(const card of element('surfaceZones').children){assert.match(card.children[1].textContent,/indisponibles/);assert.doesNotMatch(card.children[1].textContent,/0.*%/);}
  element('surfaceView').value='segmentation';vm.runInContext("renderSurfaceZones(surfaceZoneResult,{status:'unavailable',zones:null})",context);
  assert.match(element('surfaceZones').children[0].children[1].textContent,/Mur : 10.0 %/,'Semantic coverage remains inspectable when obstacle evidence is unavailable');
  for(const view of ['segmentation','depth','candidate']){
    element('surfaceView').value=view;
    vm.runInContext("renderSurfaceImageQuality({version:'rgb-quality-v1',status:'limited',reasons:['low_texture'],metrics:{}})",context);
    assert.equal(element('surfaceImageQuality').hidden,false);
    assert.match(element('surfaceImageQualityText').textContent,/Image peu structurée : analyse incertaine.*obstacle peut occuper le champ/);
    assert.match(element('surfaceImageQuality').className,/limited/);
  }
  vm.runInContext("renderSurfaceImageQuality({version:'rgb-quality-v1',status:'limited',reasons:['low_light'],metrics:{}})",context);
  assert.equal(element('surfaceImageQualityText').textContent,'Image très sombre : analyse incertaine.');
  vm.runInContext("renderSurfaceImageQuality({version:'rgb-quality-v1',status:'limited',reasons:['low_light','low_texture'],metrics:{}})",context);
  assert.match(element('surfaceImageQualityText').textContent,/Image peu structurée.*\nImage très sombre/);
  vm.runInContext("renderSurfaceImageQuality({version:'rgb-quality-v1',status:'usable',reasons:[],metrics:{}})",context);
  assert.equal(element('surfaceImageQualityText').textContent,'Aucun défaut de luminosité ou de texture signalé ; exactitude non garantie.');
  assert.doesNotMatch(element('surfaceImageQuality').className,/limited/);
  vm.runInContext('renderSurfaceImageQuality(undefined)',context);
  assert.equal(element('surfaceImageQualityText').textContent,'Qualité de l’image non évaluée dans ce rapport.','Older reports without quality diagnostics must not claim a usable image');
  vm.runInContext('clearSurfaceFrame()',context);
  assert.equal(element('surfaceImageQuality').hidden,true,'Changing image hides the old quality diagnostic while the next result is pending');
  assert.equal(element('surfaceImageQualityText').textContent,'');
  vm.runInContext("state.surfaceJob=null;renderSurfaceImageQuality({version:'rgb-quality-v1',status:'limited',reasons:['low_texture']});resetSurfaces();",context);
  assert.equal(element('surfaceImageQuality').hidden,true,'Resetting a capture must clear its quality diagnostic');
  assert.equal(element('surfaceImageQualityText').textContent,'');
  vm.runInContext(`
    globalThis.Image=class {constructor(){this.naturalWidth=8;this.naturalHeight=8;globalThis.qualityImage=this;}set src(value){}};
    state.session={id:'quality-view',frames:[{timeSeconds:0}]};state.surfaceJob='quality-job';
    request=async()=>({sessionId:'quality-view',frameIndex:0,segmentation:{width:8,height:8,maskWidth:2,maskHeight:2,mask:[[0,0],[0,0]],zones:[],imageQuality:{version:'rgb-quality-v1',status:'limited',reasons:['low_texture']}},obstacles:{status:'unavailable',reason:'relative_depth_unavailable'},policy:{status:'uncertain',reason:'image_quality_limited',proposal:null}});
  `,context);
  element('surfaceView').value='candidate';
  await vm.runInContext('showSurfaceFrame(0)',context);
  vm.runInContext('qualityImage.onload()',context);
  assert.match(element('surfaceFrameStatus').textContent,/Fusion obstacles indisponible/);
  assert.match(element('surfaceProposal').textContent,/Interprétation suspendue/,'An unavailable view must retain the suspended policy meaning');
  assert.equal(element('surfaceImageQuality').hidden,false,'Quality remains visible even when the selected experimental view is unavailable');
  assert.match(element('surfaceImageQualityText').textContent,/Image peu structurée/);
  vm.runInContext('request=async()=>new Promise(resolve=>globalThis.finishOldQuality=resolve);',context);
  const oldQuality=vm.runInContext('showSurfaceFrame(0)',context);
  assert.equal(element('surfaceImageQuality').hidden,true);
  vm.runInContext("state.session={id:'quality-new',frames:[{timeSeconds:0}]};finishOldQuality({sessionId:'quality-view',frameIndex:0,segmentation:{imageQuality:{version:'rgb-quality-v1',status:'limited',reasons:['low_texture']}},policy:{}});",context);
  await oldQuality;
  assert.equal(element('surfaceImageQuality').hidden,true,'A late quality response must not populate the new capture');
  assert.equal(element('surfaceImageQualityText').textContent,'');
  vm.runInContext("renderSurfaceImageQuality({version:'rgb-quality-v1',status:'limited',reasons:['low_texture']});renderSurfaceProposal({status:'uncertain',reason:'image_quality_limited',qualityReasons:['low_texture'],proposal:null});",context);
  assert.equal(element('surfaceProposal').textContent,'Interprétation suspendue : qualité d’image insuffisante. Aucun son émis.');
  assert.match(element('surfaceImageQualityText').textContent,/Image peu structurée/,'Policy suspension must not replace the precise photometric explanation');
  vm.runInContext("renderSurfaceProposal({proposal:null});",context);
  assert.match(element('surfaceProposal').textContent,/ne signifie pas que le passage est libre/,'Legacy reports retain their explicit non-clearance meaning');
  vm.runInContext(`
    state.surfaceModel={installed:false,modes:{depth_only:{installed:true,message:'Depth ready'},semantic_depth:{installed:false,message:'SegFormer absent'}}};
    state.session={id:'depth-selection',frames:[{timeSeconds:0}],integrity:{macReplayAllowed:true}};
    state.surfaceBusy=false;state.surfaceJob=null;state.surfaceJobMode=null;
  `,context);
  element('surfaceAnalysisMode').value='depth_only';element('surfaceView').value='segmentation';
  vm.runInContext('refreshSurfaceControls();refreshSurfaceModelText();refreshSurfaceLegend();',context);
  assert.equal(element('surfaceAnalyze').disabled,false,'Depth-only analysis must remain available without SegFormer');
  assert.equal(element('surfaceView').value,'depth');
  assert.equal(element('surfaceSemanticOption').disabled,true);
  assert.match(element('surfaceAnalysisDescription').textContent,/sans segmentation ni catégories YOLO/);
  element('surfaceView').value='candidate';vm.runInContext('refreshSurfaceLegend()',context);
  assert.match(element('surfaceModeLegend').textContent,/régions géométriques/);
  assert.doesNotMatch(element('surfaceModeLegend').textContent,/hors boîtes|non couvertes/);
  vm.runInContext("renderSurfaceZones({zones:[]},{status:'ok',zones:[{zone:'LEFT',candidateFraction:.3,relativeDepthMedian:.65}]})",context);
  assert.match(element('surfaceZones').children[0].children[1].textContent,/Régions géométriques candidates : 30.0 %/);
  assert.match(element('surfaceZones').children[0].children[1].textContent,/médian : 0.65.*sans unité/);
  assert.doesNotMatch(element('surfaceZones').children[0].children[1].textContent,/YOLO|Mur/);
  vm.runInContext(`
    globalThis.depthRequests=[];
    request=async(url,body)=>{depthRequests.push({url,body});if(url==='/api/surfaces/analyze')return new Promise(resolve=>globalThis.finishDepthStart=resolve);return {};};
  `,context);
  const depthStart=element('surfaceAnalyze').onclick();
  assert.equal(vm.runInContext('depthRequests[0].body.analysisMode',context),'depth_only');
  assert.equal(element('surfaceAnalysisMode').disabled,true);
  element('surfaceAnalysisMode').value='semantic_depth';element('surfaceAnalysisMode').onchange();
  assert.equal(element('surfaceAnalysisMode').value,'depth_only','An active job keeps its immutable analysis mode');
  vm.runInContext('resetSurfaces()',context);
  element('surfaceAnalysisMode').value='semantic_depth';element('surfaceAnalysisMode').onchange();
  vm.runInContext("finishDepthStart({jobId:'late-depth'});",context);await depthStart;await Promise.resolve();
  assert.equal(vm.runInContext('depthRequests.at(-1).url',context),'/api/surfaces/jobs/late-depth/cancel','Mode changes discard and cancel a late old analysis');
  assert.equal(vm.runInContext('state.surfaceJob',context),null);
  assert.equal(element('surfaceAnalyze').disabled,true,'The semantic mode cannot borrow availability from depth-only');
  vm.runInContext("request=async()=>({installed:true,message:'Old server',relativeDepth:{installed:true}});",context);
  element('surfaceAnalysisMode').value='depth_only';await vm.runInContext('refreshSurfaceModel()',context);
  assert.equal(element('surfaceAnalysisMode').value,'semantic_depth','Old servers without mode metadata keep their historical semantic route');
  vm.runInContext(`
    state.surfaceJob='old-report';state.surfaceJobMode='depth_only';
    request=async()=>({sessionId:'depth-selection',frameIndex:0,segmentation:{width:8,height:8,maskWidth:2,maskHeight:2,mask:[[0,0],[0,0]],zones:[],inferenceMs:10,totalInferenceMs:30,relativeDepth:{inferenceMs:20}},obstacles:{status:'unavailable'},policy:{proposal:null}});
  `,context);
  await vm.runInContext('showSurfaceFrame(0)',context);
  assert.equal(vm.runInContext('state.surfaceJobMode',context),'semantic_depth','An older result without analysisMode remains semantic, regardless of the planned next mode');
  assert.equal(element('surfaceSemanticOption').disabled,false);
  assert.match(element('surfaceFrameStatus').textContent,/combiné 30.0 ms.*surfaces 10.0 ms, relief 20.0 ms/);
  vm.runInContext(`
    request=async()=>({analysisMode:'depth_only',sessionId:'depth-selection',frameIndex:0,segmentation:{analysisMode:'depth_only',width:8,height:8,maskWidth:2,maskHeight:2,mask:null,classes:{},zones:[],inferenceMs:0,totalInferenceMs:123,relativeDepth:{available:true,width:2,height:2,values:[[0,1],[0,1]],inferenceMs:123}},obstacles:{status:'ok',zones:[{zone:'CENTER',candidateFraction:.2,relativeDepthMedian:.7}],candidateMask:[[0,1],[0,1]],categoryIndependent:true},policy:{proposal:null}});
  `,context);
  await vm.runInContext('showSurfaceFrame(0)',context);
  assert.equal(vm.runInContext('state.surfaceJobMode',context),'depth_only');
  assert.match(element('surfaceFrameStatus').textContent,/relief seul sur Mac 123.0 ms/);
  assert.doesNotMatch(element('surfaceFrameStatus').textContent,/surfaces 0|combiné/);
  assert.match(element('surfaceEvidenceStatus').textContent,/relief seul/);
  assert.doesNotMatch(element('surfaceEvidenceStatus').textContent,/hors boîtes|non couvertes/);
  element('surfaceAnalysisMode').value='depth_only';vm.runInContext('refreshSurfaceModelText()',context);
  assert.doesNotMatch(element('surfaceAnalysisDescription').textContent,/non commerciales|confiance sémantique/,'Semantic model limitations must not be assigned to the independent depth-only mode');
  element('surfaceAnalysisMode').value='semantic_depth';vm.runInContext('refreshSurfaceModelText()',context);
  assert.match(element('surfaceAnalysisDescription').textContent,/Recherche et évaluation non commerciales uniquement pour ce mode/);
  assert.match(element('surfaceAnalysisDescription').textContent,/confiance sémantique.*pas une probabilité étalonnée/);
  console.log('UI interaction checks passed: pans, stale play, archive confirmation, video cleanup, loading isolation, HTTP abort, seek debounce, native ZIP download, snapshot title, stale export cancellation, preserved invalid positions, integrity gates and explicitly skipped comparisons.');
})().catch(error=>{console.error(error);process.exitCode=1;});
