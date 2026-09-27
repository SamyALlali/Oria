'use strict';
const $ = id => document.getElementById(id);
const state = {token:null,session:null,index:0,playing:false,timer:null,job:null,jobKind:null,
  drawVersion:0,sessionVersion:0,loadVersion:0,libraryVersion:0,jobVersion:0,audioVersion:0,mode:'recorded',comparison:null,audioUrl:null,pendingArchive:null,frameAbort:null,seekTimer:null,exportId:null,exportVersion:0,exportTimer:null,jobBusy:false,surfaceModel:null,surfaceJob:null,surfaceJobMode:null,surfaceBusy:false,surfaceVersion:0,surfaceDrawVersion:0,surfaceTimer:null,surfaceAbort:null};
const fmt=(v,d=1)=>Number.isFinite(v)?v.toFixed(d):'—';
async function response(url,body,binary=false,signal){
  const options=body===undefined?{}:{method:'POST',headers:{'X-Oria-Lab-Token':state.token,
    'Content-Type':binary?'application/zip':'application/json'},body:binary?body:JSON.stringify(body)};
  if(signal)options.signal=signal;
  const r=await fetch(url,options);
  if(!r.ok){let data;try{data=await r.json();}catch{data={};}throw Error(data.error||'Action impossible');}
  return r;
}
async function request(url,body,binary=false,signal){return (await response(url,body,binary,signal)).json();}
function error(e){$('importStatus').textContent=e.message;$('importStatus').className='warning';}
function stop(){state.playing=false;clearTimeout(state.timer);clearTimeout(state.seekTimer);$('play').textContent='Lire';}
function textJson(id,value){$(id).textContent=JSON.stringify(value,null,2);}
function download(blob,name){const url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),10000);}
function stopAudio(){++state.audioVersion;$('previewPlayer').pause();$('previewPlayer').removeAttribute('src');$('previewPlayer').hidden=true;if(state.audioUrl)URL.revokeObjectURL(state.audioUrl);state.audioUrl=null;}
async function refreshLibrary(){
  const version=++state.libraryVersion;
  try{
    const data=await request('/api/library');if(version!==state.libraryVersion)return;
    $('library').replaceChildren();const trash=$('showTrash').checked;
    for(const item of data.sessions.filter(s=>s.archived===trash)){
      const row=document.createElement('div');row.className='sessionItem';
      const button=document.createElement('button');button.className='secondary';
      button.textContent=(trash?'Restaurer · ':'')+(item.displayName||item.captureId||item.id);
      button.disabled=Boolean(item.error);button.onclick=()=>trash?restoreSession(item.id):openSession(item.id);
      const small=document.createElement('small');small.textContent=item.error||`${item.status||'État inconnu'} · ${item.captureId||item.id}`;
      row.append(button,small);$('library').append(row);
    }
    if(!$('library').children.length)$('library').textContent=trash?'La corbeille est vide.':'Aucune capture importée.';
  }catch(e){if(version===state.libraryVersion)error(e);}
}
async function openSession(id){const version=++state.loadVersion;try{await loadSession(await request(`/api/session/${id}`),version);}catch(e){if(version===state.loadVersion)error(e);}}
async function restoreSession(id){const version=++state.loadVersion;try{const restored=await request(`/api/session/${id}/restore`,{});if(version!==state.loadVersion)return;$('showTrash').checked=false;await loadSession(restored,version);}catch(e){if(version===state.loadVersion)error(e);}}
function sessionTitle(){if(state.session){$('sessionTitle').textContent=state.session.displayName||'Images capturées';$('displayName').value=state.session.displayName||'';}}
function replayAllowed(source){
  const session=state.session;
  return Boolean(session?.frames.length)&&session.integrity?.[source==='mac'?'macReplayAllowed':'recordedReplayAllowed']!==false;
}
function playbackAllowed(){
  const frames=state.session?.frames||[];
  return frames.length>0&&frames.every((frame,index)=>Number.isFinite(frame.timeSeconds)&&(index===0||frame.timeSeconds>=frames[index-1].timeSeconds));
}
function refreshReplayControls(){
  $('recompute').disabled=state.jobBusy||!replayAllowed('mac');
  $('compare').disabled=state.jobBusy||!replayAllowed($('compareSource').value);
  $('play').disabled=!playbackAllowed();
  refreshSurfaceControls();
}
function integritySummary(session){
  const integrity=session.integrity;
  if(!integrity)return '';
  const messages=[`${integrity.frameEntryCount} entrées source · ${integrity.invalidFrameCount} entrées invalides · ${integrity.missingImageCount} PNG indisponibles · ${integrity.invalidEventCount} événements invalides.`];
  if(!integrity.recordedReplayAllowed)messages.push('Recalculs refusés : données ou associations ambiguës. Inspection image par image disponible.');
  else if(!integrity.macReplayAllowed)messages.push('Couverture visuelle incomplète : comparaison des détections enregistrées possible, recalcul ONNX indisponible.');
  if(!playbackAllowed())messages.push('Lecture automatique indisponible : horloges absentes ou non chronologiques. Utilisez les flèches.');
  return messages.join('\n');
}
async function loadSession(session,version){
  if(version!==state.loadVersion)return;
  resetSurfaces();
  const oldJob=state.job;if(oldJob)request(`/api/job/${oldJob}/cancel`,{}).catch(()=>{});
  stop();stopAudio();resetArchiveConfirmation();state.frameAbort?.abort();clearFrameView();++state.sessionVersion;++state.drawVersion;++state.jobVersion;
  state.session=session;state.index=0;state.job=null;state.jobKind=null;state.comparison=null;state.mode='recorded';state.jobBusy=false;
  const config=session.manifest.metadata?.policyConfig||{};
  for(const id of ['confirmationSamples','samplesA','samplesB'])$(id).value=config.confirmationSamples??2;
  for(const id of ['repeatInterval','repeatA','repeatB'])$(id).value=config.repeatIntervalMs??6500;
  $('trackingA').value=config.trackingMode??'LEGACY_IOU';$('trackingB').value='STABLE_RGB_V2';
  $('mode').value='recorded';$('contextVideo').pause();$('contextVideo').hidden=true;$('contextVideo').removeAttribute('src');
  $('videoControls').hidden=true;$('videoStatus').textContent='';$('jobStatus').textContent='Aucun recalcul.';
  $('comparisonPanel').hidden=true;$('exportReport').disabled=true;$('nextDifference').disabled=true;$('cancelJob').disabled=true;
  $('sessionActions').hidden=false;sessionTitle();$('warnings').textContent=session.warnings.join('\n');$('sessionInfo').replaceChildren();
  $('integritySummary').textContent=integritySummary(session);
  for(const [a,b]of [['Capture',session.manifest.sessionId],['État déclaré',session.manifest.status],['Entrées source',session.frames.length],
    ['Paquets H264',session.packetCount],['Modèle',session.modelSha256?session.modelSha256.slice(0,16)+'…':'SHA non renseigné']]){
    const dt=document.createElement('dt'),dd=document.createElement('dd');dt.textContent=a;dd.textContent=b??'—';$('sessionInfo').append(dt,dd);
  }
  const has=session.frames.length>0;for(const id of ['previous','next','timeline'])$(id).disabled=!has;refreshReplayControls();
  $('videoButton').disabled=!session.videoAvailable||!session.ffmpegAvailable;
  $('videoButton').textContent=session.ffmpegAvailable?'Préparer la vidéo avec FFmpeg local':'FFmpeg non disponible';
  $('timeline').max=Math.max(0,session.frames.length-1);$('timeStart').textContent=fmt(session.frames[0]?.timeSeconds)+' s';
  $('timeEnd').textContent=fmt(session.frames.at(-1)?.timeSeconds)+' s';
  $('importStatus').textContent=`Session ouverte : ${session.frames.length} entrées source. Traitement local sur ce Mac.`;$('importStatus').className='muted';
  await refreshLibrary();if(version!==state.loadVersion)return;
  if(has)await showFrame(0);else{$('empty').hidden=false;$('empty').textContent='Cette capture ne contient aucune entrée d’image.';$('decision').textContent='Aucune image indexée.';$('audio').textContent='Aucun événement associé à une image.';}
}
function draw(image,detections){
  const canvas=$('canvas'),ctx=canvas.getContext('2d');canvas.width=image.naturalWidth;canvas.height=image.naturalHeight;ctx.drawImage(image,0,0);
  const scale=Math.max(1,canvas.width/600);ctx.lineWidth=2*scale;ctx.font=`${13*scale}px -apple-system,sans-serif`;
  for(const d of detections){const b=d.box;if(!b)continue;
    const x=b.left*canvas.width,y=b.top*canvas.height,w=(b.right-b.left)*canvas.width,h=(b.bottom-b.top)*canvas.height;
    ctx.strokeStyle=state.mode==='recorded'?'#59e2ac':'#8aa8ff';ctx.strokeRect(x,y,w,h);
    const label=`${d.className??d.classId} ${(100*d.confidence).toFixed(0)}%`,size=ctx.measureText(label).width;
    ctx.fillStyle=ctx.strokeStyle;ctx.fillRect(x,Math.max(0,y-20*scale),size+8*scale,20*scale);ctx.fillStyle='#0e1724';ctx.fillText(label,x+4*scale,Math.max(15*scale,y-5*scale));
  }
}
function clearFrameView(){
  refreshSurfaceNavigation();
  $('canvas').hidden=true;$('empty').hidden=false;$('empty').textContent='Chargement de l’image…';
  $('decision').textContent='Chargement de la décision…';$('audio').textContent='Chargement des événements…';
  for(const id of ['decisionJson','audioJson','differenceJson','rawDetails','frameIntegrity'])$(id).textContent='';
  $('metrics').replaceChildren();
  for(const label of ['Image','Détections','Inférence','Âge enregistré']){
    const div=document.createElement('div'),span=document.createElement('span'),strong=document.createElement('strong');
    span.textContent=label;strong.textContent='—';div.append(span,strong);$('metrics').append(div);
  }
}
async function showFrame(index){
  if(!state.session||!state.session.frames.length)return;
  index=Math.max(0,Math.min(index,state.session.frames.length-1));state.index=index;
  showSurfaceFrame(index);
  state.frameAbort?.abort();state.frameAbort=new AbortController();const signal=state.frameAbort.signal;
  const version=++state.drawVersion,session=state.session,mode=state.mode,jobId=state.job,item=session.frames[index];
  clearFrameView();
  $('timeline').value=index;$('position').textContent=`${index+1} / ${session.frames.length}`;
  $('frameSubtitle').textContent=`${item.sourceLine==null?'':`Ligne source ${item.sourceLine} · `}Image ${item.frameId??'non identifiée'} · ${fmt(item.timeSeconds,3)} s`;
  $('modeBadge').textContent=mode==='recorded'?'TÉLÉPHONE · ENREGISTRÉ':mode==='comparison'?'MAC · COMPARAISON A/B':'MAC · RECALCUL';
  const usableJob=jobId&&((mode==='comparison')===(state.jobKind==='comparison'));
  try{
    const [recorded,calculated]=await Promise.all([request(`/api/session/${session.id}/frame/${index}`,undefined,false,signal),
      mode!=='recorded'&&usableJob?request(`/api/job/${jobId}?frame=${index}`,undefined,false,signal):Promise.resolve(null)]);
    if(version!==state.drawVersion)return;
    const integrity=recorded.integrity||{},unsafe=integrity.entryValid===false||['invalid','ambiguous'].includes(integrity.analysisStatus);
    const ready=calculated&&!calculated.pending,skipped=ready&&(calculated.policy?.skipped||Object.values(calculated.variants||{}).some(value=>value.policy?.skipped)),boxes=unsafe?[]:mode==='recorded'?recorded.detections:ready?calculated.detections:[];
    $('frameIntegrity').textContent=(integrity.issues||[]).join('\n');
    if(unsafe)$('frameIntegrity').textContent+='\nDonnées non exploitables pour une décision : aucune détection ne leur est attribuée.';
    if(integrity.imageAvailable===false){$('empty').textContent='PNG indisponible · position d’origine conservée.';}
    else{
      const image=new Image();image.onload=()=>{if(version===state.drawVersion){draw(image,boxes);$('canvas').hidden=false;$('empty').hidden=true;}};
      image.onerror=()=>{if(version===state.drawVersion){$('empty').hidden=false;$('empty').textContent='PNG absente ou illisible · position d’origine conservée.';}};
      image.src=`/api/session/${session.id}/image/${index}`;
    }
    const phone=unsafe?{}:recorded.recordedInference||{},time=unsafe?null:ready?calculated.macTimingsMs?.inference:mode==='recorded'?phone.inferenceMs:null;
    const labels=[['Image',item.frameId==null?'—':String(item.frameId)],['Détections',unsafe?'Non exploitables':integrity.analysisStatus==='not_recorded'&&mode==='recorded'?'Non enregistrées':mode!=='recorded'&&!ready?'En attente':String(boxes.length)],
      [ready&&calculated.macTimingsMs?'Inférence Mac':'Inférence téléphone',fmt(time??(mode==='comparison'?phone.inferenceMs:null))+' ms'],
      ['Âge téléphone enregistré',fmt(phone.resultAgeMs??phone.ageMs,0)+' ms']];
    $('metrics').replaceChildren();for(const[a,b]of labels){const div=document.createElement('div'),span=document.createElement('span'),strong=document.createElement('strong');span.textContent=a;strong.textContent=b;div.append(span,strong);$('metrics').append(div);}
    const decisions=unsafe?[]:recorded.events.filter(e=>e.type==='decision');
    $('decision').textContent=unsafe?'Association refusée : consulter le diagnostic de cette entrée.':mode==='recorded'?(decisions.length?'Décision enregistrée sur le téléphone.':'Aucune décision enregistrée pour cette image.'):
      ready?(skipped?'Position non comparée : inférence ou horloge enregistrée absente.':mode==='comparison'?(calculated.decisionDifferent?'Les décisions A et B diffèrent.':'Même décision A et B (identifiants et diagnostics exclus).'):'Moteur Kotlin rejoué dans l’ordre.'):'Lancez le traitement complet pour inspecter cette image.';
    textJson('decisionJson',unsafe?{}:mode==='recorded'?decisions:ready?(calculated.variants||calculated.policy):{});
    let audio=[];
    if(unsafe){$('audio').textContent='Aucun événement attribué à cette entrée ambiguë.';}
    else if(mode==='recorded'){audio=recorded.audioEvents;$('audio').textContent=`${audio.length} événement(s) enregistré(s). Aucune piste sonore.`;}
    else if(ready){
      audio=mode==='comparison'?calculated.simulatedAudioByVariant:calculated.simulatedAudioEvents;
      $('audio').textContent='Événements simulés. Aucun son automatique, aucune confirmation physique.';
    }else $('audio').textContent='Simulation en attente de traitement.';
    textJson('audioJson',audio);
    textJson('differenceJson',!unsafe&&ready&&mode==='comparison'?{champsModifiés:calculated.changedPaths,identifiantsDifférents:calculated.identityDifferent,entréeCommune:calculated.sharedInput}:{});
    textJson('rawDetails',unsafe?{}:{detections:boxes,...(ready?{macTimingsMs:calculated.macTimingsMs,rawParity:calculated.rawParity,rawModelOutput:calculated.rawOutput,transform:calculated.transform,scope:calculated.scope}:{confidenceFloor:recorded.recordedDetectionConfidenceFloor,rawModelOutputIncluded:recorded.rawModelOutputIncluded,rawModelOutput:phone.rawModelOutput??null,modelDetections:phone.modelDetections??null})});
  }catch(e){if(e.name==='AbortError')return;if(version===state.drawVersion){$('empty').textContent='Image indisponible.';$('decision').textContent='Décision indisponible pour cette image.';$('audio').textContent='Résultat indisponible.';textJson('audioJson',[]);error(e);}}
}
async function tick(){
  if(!state.playing||!state.session)return;if(!playbackAllowed()){stop();return;}const current=state.session.frames[state.index],next=state.session.frames[state.index+1];
  if(!next){stop();return;}const version=state.sessionVersion,delay=Math.max(20,1000*(next.timeSeconds-current.timeSeconds)/Number($('speed').value));
  state.timer=setTimeout(async()=>{if(version!==state.sessionVersion||!state.playing)return;await showFrame(state.index+1);if(version===state.sessionVersion)tick();},delay);
}
async function importCapture(url,body,binary){const version=++state.loadVersion;try{$('importStatus').textContent='Import et vérification…';await loadSession(await request(url,body,binary),version);}catch(e){if(version===state.loadVersion)error(e);}}
$('zip').onchange=e=>{const file=e.target.files[0];if(file)importCapture('/api/import/zip',file,true);};
$('openFolder').onclick=()=>importCapture('/api/import/folder',{path:$('folder').value});
$('refreshLibrary').onclick=refreshLibrary;$('showTrash').onchange=refreshLibrary;
$('renameSession').onclick=async()=>{const session=state.session;try{const renamed=await request(`/api/session/${session.id}/rename`,{displayName:$('displayName').value});if(state.session?.id===session.id){state.session.displayName=renamed.displayName;sessionTitle();}await refreshLibrary();}catch(e){error(e);}};
function exportProgressText(job){
  const mib=value=>fmt(value/1048576,1)+' Mio';
  if(job.state==='ready')return `ZIP prêt (${mib(job.archiveBytes)}). Télécharger avec le navigateur ; jusqu’à 30 min sans utilisation, dans la limite de quatre ZIP en cache.`;
  if(job.state==='preparing')return `Préparation sur disque : ${mib(job.bytesCopied)} / ${mib(job.totalBytes)}. La capture reste conservée.`;
  if(job.state==='cancelled')return 'Lien retiré. Un téléchargement déjà lancé reste géré par le navigateur. La capture est conservée.';
  if(job.state==='expired')return 'Lien expiré ou sorti du cache. Préparez un nouveau ZIP ; la capture est conservée.';
  return 'Export impossible : '+(job.error||'erreur locale');
}
async function pollExport(id,version){
  try{
    const job=await request(`/api/export/${id}`);
    if(version!==state.exportVersion||id!==state.exportId)return;
    $('exportTitle').textContent='ZIP · '+(job.displayNameSnapshot||job.captureId||job.sessionId);
    $('exportStatus').textContent=exportProgressText(job);
    $('downloadExport').hidden=job.state!=='ready';
    if(job.state==='ready'){$('downloadExport').href=job.downloadUrl;$('downloadExport').download=job.downloadName;}
    $('exportSession').disabled=job.state==='preparing';$('cancelExport').disabled=!['preparing','ready'].includes(job.state);$('cancelExport').textContent=job.state==='ready'?'Retirer le lien':'Annuler la préparation';
    if(['preparing','ready'].includes(job.state)){clearTimeout(state.exportTimer);state.exportTimer=setTimeout(()=>pollExport(id,version),job.state==='preparing'?600:10000);}
  }catch(e){if(version===state.exportVersion){$('exportStatus').textContent='État export indisponible : '+e.message;$('downloadExport').hidden=true;$('exportSession').disabled=false;}}
}
$('exportSession').onclick=async()=>{
  if(!state.session)return;const session=state.session,version=++state.exportVersion,previous=state.exportId;
  state.exportId=null;clearTimeout(state.exportTimer);$('exportControls').hidden=false;$('downloadExport').hidden=true;
  $('exportTitle').textContent='ZIP · '+(session.displayName||session.manifest.sessionId||session.id);
  $('exportStatus').textContent='Préparation du ZIP sur disque…';$('exportSession').disabled=true;$('cancelExport').disabled=false;$('cancelExport').textContent='Annuler la préparation';
  try{
    if(previous)await request(`/api/export/${previous}/cancel`,{}).catch(()=>{});
    if(version!==state.exportVersion)return;
    const job=await request(`/api/session/${session.id}/export`,{});
    if(version!==state.exportVersion){request(`/api/export/${job.id}/cancel`,{}).catch(()=>{});return;}
    state.exportId=job.id;$('exportTitle').textContent='ZIP · '+(job.displayNameSnapshot||job.captureId||job.sessionId);pollExport(job.id,version);
  }catch(e){if(version===state.exportVersion){$('exportStatus').textContent='Export impossible : '+e.message;$('exportSession').disabled=false;$('cancelExport').disabled=true;}}
};
$('cancelExport').onclick=async()=>{
  const id=state.exportId,version=++state.exportVersion;state.exportId=null;clearTimeout(state.exportTimer);
  $('downloadExport').hidden=true;$('cancelExport').disabled=true;$('exportSession').disabled=false;
  $('exportStatus').textContent='Annulation de l’export…';
  try{if(id)await request(`/api/export/${id}/cancel`,{});if(version===state.exportVersion)$('exportStatus').textContent='Lien retiré ou préparation annulée. Un téléchargement déjà lancé reste géré par le navigateur. La capture est conservée.';}
  catch(e){if(version===state.exportVersion)$('exportStatus').textContent='Annulation non confirmée : '+e.message;}
};
function resetArchiveConfirmation(){state.pendingArchive=null;$('archiveConfirmation').hidden=true;}
$('archiveSession').onclick=()=>{
  if(!state.session)return;
  state.pendingArchive={id:state.session.id,version:state.sessionVersion};
  $('archiveConfirmationText').textContent=state.session.displayName||state.session.manifest?.sessionId||'Cette capture';
  $('archiveConfirmation').hidden=false;$('cancelArchive').focus();
};
$('cancelArchive').onclick=()=>{resetArchiveConfirmation();$('archiveSession').focus();};
$('confirmArchive').onclick=async()=>{
  const pending=state.pendingArchive;
  resetArchiveConfirmation();
  if(!pending||pending.version!==state.sessionVersion||pending.id!==state.session?.id)return;
  const session=state.session;
  try{
    await request(`/api/session/${session.id}/archive`,{});
    if(pending.version===state.sessionVersion&&state.session?.id===session.id){
      stop();stopAudio();state.frameAbort?.abort();++state.loadVersion;++state.sessionVersion;++state.drawVersion;++state.jobVersion;
      resetSurfaces();state.session=null;state.job=null;state.jobKind=null;state.comparison=null;state.jobBusy=false;
      clearFrameView();
      for(const id of ['integritySummary','warnings','frameSubtitle'])$(id).textContent='';
      $('position').textContent='0 / 0';$('timeline').value=0;$('timeline').max=0;
      $('timeStart').textContent='—';$('timeEnd').textContent='—';
      $('decision').textContent='Aucune capture ouverte.';$('audio').textContent='Aucun événement sélectionné.';
      $('contextVideo').pause();$('contextVideo').removeAttribute('src');$('contextVideo').hidden=true;
      $('videoControls').hidden=true;$('sessionInfo').replaceChildren();$('canvas').width=$('canvas').width;
      $('sessionActions').hidden=true;$('sessionTitle').textContent='Capture dans la corbeille';
      $('empty').hidden=false;$('empty').textContent='Capture conservée. Vous pouvez la restaurer depuis la bibliothèque.';
      for(const id of ['previous','play','next','timeline','recompute','compare','videoButton','cancelJob'])$(id).disabled=true;
      $('comparisonPanel').hidden=true;$('showTrash').checked=true;
    }
    await refreshLibrary();
  }catch(e){if(pending.version===state.sessionVersion)error(e);}
};
$('previous').onclick=()=>{stop();showFrame(state.index-1);};$('next').onclick=()=>{stop();showFrame(state.index+1);};
$('timeline').oninput=()=>{stop();state.frameAbort?.abort();const index=Number($('timeline').value),version=state.sessionVersion;state.seekTimer=setTimeout(()=>{if(version===state.sessionVersion)showFrame(index);},50);};
$('play').onclick=async()=>{if(state.playing){stop();return;}if(!playbackAllowed())return;const version=state.sessionVersion;if(state.index===state.session.frames.length-1)await showFrame(0);if(version!==state.sessionVersion)return;state.playing=true;$('play').textContent='Pause';tick();};
$('speed').onchange=()=>{if(state.playing){clearTimeout(state.timer);tick();}};
$('mode').onchange=()=>{state.mode=$('mode').value;$('comparisonPanel').hidden=state.mode!=='comparison';showFrame(state.index);};
function variantConfig(name){return {trackingMode:$('tracking'+name).value,confirmationSamples:Number($('samples'+name).value),repeatIntervalMs:Number($('repeat'+name).value)};}
async function runJob(kind){
  if(!state.session||state.jobBusy)return;
  if(!replayAllowed(kind==='comparison'?$('compareSource').value:'mac')){error(Error('Traitement refusé : consultez les diagnostics d’intégrité de la capture.'));return;}
  const session=state.session,version=++state.jobVersion,sessionVersion=state.sessionVersion;
  try{
    state.jobBusy=true;
    for(const id of ['compare','recompute'])$(id).disabled=true;
    const shared={sessionId:session.id,confirmationMs:Number($('confirmation').value)};
    const body=kind==='comparison'?{...shared,configA:variantConfig('A'),configB:variantConfig('B'),source:$('compareSource').value}:
      {...shared,policyConfig:{confirmationSamples:Number($('confirmationSamples').value),repeatIntervalMs:Number($('repeatInterval').value)}};
    const r=await request(kind==='comparison'?'/api/compare':'/api/recompute',body);
    if(version!==state.jobVersion||sessionVersion!==state.sessionVersion){request(`/api/job/${r.jobId}/cancel`,{}).catch(()=>{});return;}
    state.job=r.jobId;state.jobKind=kind;state.mode=kind==='comparison'?'comparison':'recomputed';state.comparison=null;
    $('mode').value=state.mode;$('cancelJob').disabled=false;$('exportReport').disabled=true;$('comparisonPanel').hidden=kind!=='comparison';
    $('comparisonSummary').textContent='Comparaison en cours…';$('differenceFrames').replaceChildren();$('announcementsA').replaceChildren();$('announcementsB').replaceChildren();
    pollJob(r.jobId,version);
  }catch(e){if(version===state.jobVersion){error(e);state.jobBusy=false;refreshReplayControls();}}
}
$('recompute').onclick=()=>runJob('recompute');$('compare').onclick=()=>runJob('comparison');
$('compareSource').onchange=refreshReplayControls;
$('cancelJob').onclick=async()=>{const id=state.job;try{if(id)await request(`/api/job/${id}/cancel`,{});}catch(e){error(e);}};
function comparisonSummary(job){
  if(!job.summary)return;state.comparison=job;
  const summary=job.summary,differences=summary.differentFrames;
  $('comparisonSummary').textContent=`${summary.comparedFrames} images comparées · ${job.skippedFrames.length} ignorées faute d’horloge/inférence · ${differences.length} décisions différentes · ${summary.announcementDifferentFrames.length} images avec une annonce différente · ${summary.identityDifferentFrames.length} images avec des identifiants différents (diagnostic). Source : ${job.detectionSource==='recorded'?'détections enregistrées':'un recalcul ONNX commun'}.`;
  if(summary.comparedFrames===0)$('comparisonSummary').textContent+=' Aucune comparaison possible sur les positions de cette capture.';
  if(job.integrity?.visualCoverageComplete===false)$('comparisonSummary').textContent+=' Couverture visuelle incomplète : certaines PNG sont indisponibles, seules les données enregistrées sont comparées.';
  $('differenceFrames').replaceChildren();for(const index of differences.slice(0,100)){const button=document.createElement('button');button.className='secondary';button.textContent=`Image ${index+1}`;button.onclick=()=>{stop();showFrame(index);};$('differenceFrames').append(button);}
  $('nextDifference').disabled=!differences.length;
  for(const name of ['A','B']){$('announcements'+name).replaceChildren();for(const event of summary.announcements[name]){const li=document.createElement('li');li.textContent=`${fmt((event.submittedAtMs-state.session.originMs)/1000,2)} s · ${event.text}`;const button=document.createElement('button');button.className='secondary';button.textContent='Inspecter';button.onclick=()=>{stop();showFrame(event.frameIndex);$('previewText').value=event.text||'';$('previewPan').value=['LEFT','CENTER','RIGHT'].includes(event.zone)?event.zone:'CENTER';};li.append(button);$('announcements'+name).append(li);}}
}
async function pollJob(id,version){
  try{
    const job=await request(`/api/job/${id}`);if(id!==state.job||version!==state.jobVersion)return;
    const terminal=['complete','failed','cancelled'].includes(job.state);
    $('jobStatus').textContent=job.state==='failed'?`Erreur : ${job.error}`:job.state==='cancelled'?'Traitement annulé.':job.state==='complete'?`Terminé : ${job.done} entrées · ${fmt(job.macBatchSeconds)} s sur Mac.`:`Traitement ${job.done}/${job.total} · ${job.state==='queued'?'en attente':'en cours'}…`;
    if(job.kind==='comparison'&&job.state==='complete')comparisonSummary(job);
    $('exportReport').disabled=job.state!=='complete';$('cancelJob').disabled=terminal;
    if(state.mode!=='recorded'&&!state.playing)await showFrame(state.index);
    if(id!==state.job||version!==state.jobVersion)return;
    if(terminal){state.jobBusy=false;refreshReplayControls();}
    else setTimeout(()=>pollJob(id,version),700);
  }catch(e){if(id===state.job&&version===state.jobVersion){error(e);state.jobBusy=false;refreshReplayControls();}}
}
$('nextDifference').onclick=()=>{const frames=state.comparison?.summary?.differentFrames||[];if(frames.length){stop();showFrame(frames.find(i=>i>state.index)??frames[0]);}};
$('exportReport').onclick=()=>{if(!state.job)return;const link=document.createElement('a');link.href=`/api/job/${state.job}/report`;link.download=`OriaLab-comparaison-${state.job}.json`;link.click();};
$('videoButton').onclick=async()=>{const session=state.session,version=state.sessionVersion;try{$('videoButton').disabled=true;$('videoStatus').textContent='Préparation locale…';const r=await request('/api/context-video',{sessionId:session.id});if(version!==state.sessionVersion)return;$('videoStatus').textContent=r.scope;$('contextVideo').src=`/api/session/${session.id}/video`;$('contextVideo').hidden=false;$('videoControls').hidden=false;}catch(e){if(version===state.sessionVersion)$('videoStatus').textContent=e.message;}finally{if(version===state.sessionVersion)$('videoButton').disabled=false;}};
for(const [id,delta]of [['videoPrev',-1],['videoNext',1]])$(id).onclick=()=>{const v=$('contextVideo');v.pause();v.currentTime=Math.max(0,Math.min(v.duration||0,v.currentTime+delta/30));};
$('previewAudio').onclick=async()=>{stopAudio();const version=state.audioVersion;try{$('previewStatus').textContent='Synthèse locale…';const r=await response('/api/audio-preview',{text:$('previewText').value,pan:$('previewPan').value});const blob=await r.blob();if(version!==state.audioVersion)return;state.audioUrl=URL.createObjectURL(blob);$('previewPlayer').src=state.audioUrl;$('previewPlayer').hidden=false;await $('previewPlayer').play();$('previewStatus').textContent='Simulation Mac uniquement. Écoutez avec une sortie stéréo pour comparer les côtés.';}catch(e){if(version===state.audioVersion)$('previewStatus').textContent=e.message;}};
$('stopAudio').onclick=()=>{stopAudio();$('previewStatus').textContent='Écoute arrêtée.';};
document.addEventListener('keydown',e=>{if(['INPUT','SELECT','TEXTAREA','BUTTON'].includes(document.activeElement.tagName))return;if(e.key==='ArrowLeft'){$('previous').click();e.preventDefault();}if(e.key==='ArrowRight'){$('next').click();e.preventDefault();}if(e.key===' '&&state.session){$('play').click();e.preventDefault();}});
refreshSurfaceModel();
request('/api/config').then(async c=>{state.token=c.token;$('footer').textContent=`Stockage local : ${c.storage} · Temps Mac et téléphone distincts.`;$('previewAudio').disabled=!c.audioPreviewAvailable;$('previewStatus').textContent=c.audioPreviewAvailable?'Aucune écoute automatique. Utilisez le bouton pour écouter.':'Aperçu indisponible : voix macOS et FFmpeg local requis.';await refreshLibrary();const imported=new URLSearchParams(location.search).get('session');if(imported)await openSession(imported);}).catch(error);

// Experimental surfaces are isolated from YOLO comparison and all audio controls.
function refreshSurfaceNavigation(){
  const count=state.session?.frames?.length||0;
  $('surfacePosition').textContent=`Image ${count?state.index+1:0} / ${count}`;
  $('surfacePrevious').disabled=!count||state.index<=0;
  $('surfaceNext').disabled=!count||state.index>=count-1;
}
function stepSurfaceFrame(delta){
  const count=state.session?.frames?.length||0;
  if(!count)return;
  stop();
  const index=Math.max(0,Math.min(count-1,state.index+delta));
  if(index!==state.index)return showFrame(index);
}
function selectedSurfaceAnalysisMode(){
  return $('surfaceAnalysisMode').value||(state.surfaceModel&&!state.surfaceModel.modes?'semantic_depth':'depth_only');
}
function displayedSurfaceAnalysisMode(){return state.surfaceJobMode||selectedSurfaceAnalysisMode();}
function surfaceModeStatus(mode=selectedSurfaceAnalysisMode()){
  if(state.surfaceModel?.modes)return state.surfaceModel.modes[mode]||{installed:false,message:'Mode indisponible.'};
  return mode==='semantic_depth'?(state.surfaceModel||{installed:false}):{installed:false,message:'Ce serveur ne propose pas encore le relief seul.'};
}
function refreshSurfaceViewOptions(){
  const depthOnly=displayedSurfaceAnalysisMode()==='depth_only';
  $('surfaceSemanticOption').hidden=depthOnly;$('surfaceSemanticOption').disabled=depthOnly;
  $('surfaceCandidateOption').textContent=depthOnly?'Régions géométriques candidates':'Régions candidates hors boîtes YOLO';
  if(depthOnly&&!['depth','candidate'].includes($('surfaceView').value))$('surfaceView').value='depth';
  if(!depthOnly&&!$('surfaceView').value)$('surfaceView').value='segmentation';
  $('surfaceResultModeLabel').textContent=state.surfaceJob?`Résultats : ${depthOnly?'relief seul, indépendant des catégories':'surfaces + relief, comparaison sémantique'}.`:'';
}
function refreshSurfaceModelText(){
  const mode=selectedSurfaceAnalysisMode(),status=surfaceModeStatus(mode);
  $('surfaceAnalysisDescription').textContent=mode==='depth_only'?'Relief monoculaire seul, sans segmentation ni catégories YOLO. Les régions candidates restent hypothétiques et sans distance métrique.':'Analyse de comparaison : segmentation des surfaces + relief relatif, puis exclusion des boîtes YOLO enregistrées. Recherche et évaluation non commerciales uniquement pour ce mode. La confiance sémantique n’est pas une probabilité étalonnée.';
  $('surfaceModelStatus').textContent=status.message||(status.installed?'Modèle local vérifié.':'Modèle local absent.');
  if(mode==='semantic_depth'&&!state.surfaceModel?.modes&&status.installed)$('surfaceModelStatus').textContent+=' Relief relatif : '+(status.relativeDepth?.installed?'modèle local installé.':'indisponible ; segmentation seule, aucune proposition obstacle.');
  if(!status.installed)$('surfaceModelStatus').textContent+=' Préparation locale : suivre SURFACES_EXPERIMENTAL.md. Aucun téléchargement automatique.';
}
function refreshSurfaceControls(){
  refreshSurfaceNavigation();refreshSurfaceViewOptions();
  $('surfaceAnalyze').disabled=!surfaceModeStatus().installed||state.surfaceBusy||!replayAllowed('mac');
  $('surfaceCancel').disabled=!state.surfaceBusy;
  $('surfaceAnalysisMode').disabled=state.surfaceBusy;
}
async function refreshSurfaceModel(){
  try{const status=await request('/api/surfaces/status');state.surfaceModel=status;
    if(!status.modes&&!state.surfaceBusy)$('surfaceAnalysisMode').value='semantic_depth';
    refreshSurfaceModelText();
  }catch(e){state.surfaceModel=null;$('surfaceModelStatus').textContent='Modèle non disponible : '+e.message;}
  refreshSurfaceControls();
}
function refreshSurfaceLegend(){
  refreshSurfaceViewOptions();
  const depthOnly=displayedSurfaceAnalysisMode()==='depth_only';
  const mode=$('surfaceView').value||'segmentation';
  $('surfaceSemanticLegend').hidden=mode!=='segmentation';
  $('surfaceModeLegend').hidden=mode==='segmentation';
  $('surfaceModeLegend').textContent=mode==='depth'?'Bleu : score relatif faible ; jaune : score relatif élevé. Normalisation propre à chaque image ; aucune distance ni comparaison temporelle directe.':mode==='candidate'?(depthOnly?'Rouge : régions géométriques candidates issues du relief seul, sans catégorie ni exclusion par YOLO. Aucune distance ni certitude d’un obstacle.':'Rouge : régions candidates non-sol, appuyées par le relief relatif et non couvertes par les boîtes YOLO enregistrées. Aucune distance ni certitude d’un obstacle.') :'';
  $('surfaceCanvas').ariaLabel=mode==='depth'?'Carte de relief relatif expérimental sur la PNG sélectionnée, sans distance':mode==='candidate'?(depthOnly?'Régions géométriques candidates sur la PNG sélectionnée':'Régions candidates expérimentales hors boîtes YOLO sur la PNG sélectionnée'):'Masque sémantique expérimental sur la PNG sélectionnée';
}
function renderSurfaceProposal(policy){
  $('surfaceProposal').textContent=policy?.status==='uncertain'?'Interprétation suspendue : qualité d’image insuffisante. Aucun son émis.':policy?.proposal?`Proposition indicative : « ${policy.proposal.text} ». Aucun son émis.`:'Aucune proposition indicative sur cette image. Cela ne signifie pas que le passage est libre.';
}
function renderSurfaceImageQuality(quality){
  const block=$('surfaceImageQuality'),text=$('surfaceImageQualityText');
  block.hidden=false;block.className='imageQuality';
  if(!quality||quality.version!=='rgb-quality-v1'||!['limited','usable'].includes(quality.status)||!Array.isArray(quality.reasons)){
    text.textContent='Qualité de l’image non évaluée dans ce rapport.';return;
  }
  const messages=[];
  if(quality.reasons.includes('low_texture'))messages.push('Image peu structurée : analyse incertaine. Un obstacle peut occuper le champ sans être reconnu.');
  if(quality.reasons.includes('low_light'))messages.push('Image très sombre : analyse incertaine.');
  if(quality.status==='limited'||messages.length){
    block.className='imageQuality limited';
    text.textContent=messages.length?messages.join('\n'):'Analyse incertaine : diagnostic de qualité limité sans motif reconnu dans ce rapport.';
  }else text.textContent='Aucun défaut de luminosité ou de texture signalé ; exactitude non garantie.';
}
function clearSurfaceFrame(message='Résultat surfaces en attente pour cette image.'){
  $('surfaceCanvas').hidden=true;$('surfaceFrameStatus').textContent=message;
  $('surfaceImageQuality').hidden=true;$('surfaceImageQualityText').textContent='';
  $('surfaceZones').replaceChildren();$('surfaceEvidenceStatus').textContent='';$('surfaceProposal').textContent='Aucune proposition indicative.';$('surfaceJson').textContent='';
}
function resetSurfaces(){
  const old=state.surfaceJob;++state.surfaceVersion;++state.surfaceDrawVersion;
  state.surfaceAbort?.abort();clearTimeout(state.surfaceTimer);state.surfaceJob=null;state.surfaceJobMode=null;state.surfaceBusy=false;
  if(old)request(`/api/surfaces/jobs/${old}/cancel`,{}).catch(()=>{});
  $('surfaceExport').disabled=true;$('surfaceJobStatus').textContent='Aucune analyse surfaces.';
  clearSurfaceFrame();refreshSurfaceControls();
}
function drawSurfaceMask(image,result,evidence){
  if(result.width!==image.naturalWidth||result.height!==image.naturalHeight)throw Error('Dimensions de masque incompatibles avec la PNG.');
  const mode=$('surfaceView').value||'segmentation';
  const depth=result.relativeDepth;
  if(mode==='depth'&&(!depth||depth.available!==true))throw Error('Relief relatif indisponible pour cette image.');
  if(mode==='candidate'&&evidence?.status!=='ok')throw Error('Fusion obstacles indisponible pour cette image.');
  const w=mode==='depth'?depth.width:result.maskWidth,h=mode==='depth'?depth.height:result.maskHeight;
  const mask=mode==='depth'?depth.values:mode==='candidate'?evidence.candidateMask:result.mask;
  if(!Number.isInteger(w)||!Number.isInteger(h)||w<1||h<1||w>512||h>512||!Array.isArray(mask)||mask.length!==h||mask.some(row=>!Array.isArray(row)||row.length!==w))throw Error('Masque surfaces invalide.');
  const canvas=$('surfaceCanvas'),ctx=canvas.getContext('2d');canvas.width=image.naturalWidth;canvas.height=image.naturalHeight;
  ctx.drawImage(image,0,0);
  const colors={0:'#ef8738',3:'#31c5ad',14:'#f078b5',8:'#70a9ff',53:'#b887ed'};
  ctx.globalAlpha=.42;
  for(let y=0;y<h;y++)for(let x=0;x<w;x++){
    const value=mask[y][x];
    const color=mode==='candidate'?(value?'#ff5c48':null):mode==='depth'?(Number.isFinite(value)?`rgb(${Math.round(255*value)},${Math.round(210*value)},${Math.round(255*(1-value))})`:null):colors[value];
    if(!color)continue;ctx.fillStyle=color;
    ctx.fillRect(x*canvas.width/w,y*canvas.height/h,canvas.width/w+.3,canvas.height/h+.3);
  }
  ctx.globalAlpha=1;
}
function renderSurfaceZones(result,evidence){
  const depthOnly=displayedSurfaceAnalysisMode()==='depth_only';
  const semantic=(!depthOnly)&&($('surfaceView').value||'segmentation')==='segmentation';
  $('surfaceZones').replaceChildren();
  const percentages=value=>Number.isFinite(value)?`${fmt(100*value)} %`:'indisponible';
  for(const name of ['LEFT','CENTER','RIGHT']){
    const card=document.createElement('p'),heading=document.createElement('strong'),detail=document.createElement('span');
    heading.textContent={LEFT:'À gauche',CENTER:'Au centre',RIGHT:'À droite'}[name];
    const zone=(semantic?result.zones:evidence.status==='ok'?evidence.zones:[])?.find(value=>value.zone===name);
    if(semantic){
      detail.textContent=zone?`Mur : ${percentages(zone.wallFraction)} de la zone · score moyen ${fmt(zone.wallMeanConfidence,2)} (non étalonné).`:'Mesures sémantiques indisponibles pour cette zone.';
    }else if(zone&&depthOnly){
      detail.textContent=`Régions géométriques candidates : ${percentages(zone.candidateFraction)} de la zone analysée.\nScore de relief relatif médian : ${Number.isFinite(zone.relativeDepthMedian)?fmt(zone.relativeDepthMedian,2):'indisponible'} (sans unité, propre à l’image). Aucune distance.`;
    }else if(zone){
      const support=zone.obstructionFraction===0?'non applicable (aucune surface éligible)':`${percentages(zone.depthRelativeSupport)} des pixels éligibles`;
      detail.textContent=`Candidats hors boîtes YOLO : ${percentages(zone.unrecognizedFraction)} de la zone analysée.
Surfaces éligibles : ${percentages(zone.obstructionFraction)} de la zone analysée.
Appui du relief relatif : ${support}. Aucune distance.`;
    }else{
      detail.textContent='Mesures obstacles indisponibles pour cette zone ; aucune fraction n’est déduite.';
    }
    card.append(heading,detail);$('surfaceZones').append(card);
  }
}
async function showSurfaceFrame(index){
  const version=++state.surfaceDrawVersion,session=state.session,job=state.surfaceJob;
  refreshSurfaceNavigation();refreshSurfaceLegend();
  state.surfaceAbort?.abort();state.surfaceAbort=new AbortController();const signal=state.surfaceAbort.signal;
  clearSurfaceFrame(job?'Chargement du résultat surfaces…':'Lancez une analyse surfaces pour inspecter cette image.');
  if(!session||!job)return;
  try{
    const value=await request(`/api/surfaces/jobs/${job}?frame=${index}`,undefined,false,signal);
    if(version!==state.surfaceDrawVersion||session.id!==state.session?.id||job!==state.surfaceJob)return;
    if(value.sessionId!==session.id||value.frameIndex!==index)throw Error('Identité surfaces incompatible avec la sélection.');
    if(value.pending){$('surfaceFrameStatus').textContent=['failed','cancelled'].includes(value.state)?'Cette position n’a pas été analysée : résultat partiel.':'Cette image attend son analyse surfaces.';return;}
    const result=value.segmentation,policy=value.policy;
    state.surfaceJobMode=value.analysisMode||'semantic_depth';refreshSurfaceLegend();
    const depthOnly=state.surfaceJobMode==='depth_only';
    renderSurfaceImageQuality(result.imageQuality);
    const evidence=value.obstacles||{};
    $('surfaceEvidenceStatus').textContent=evidence.status==='ok'?(depthOnly?'Régions géométriques candidates calculées avec le relief seul. Calcul indépendant des catégories ; aucune distance mesurée.':'Candidats non-sol + relief relatif, hors boîtes YOLO enregistrées. Non couvert par YOLO ne signifie pas objet inconnu avec certitude.'):(depthOnly?'Géométrie indisponible : ':'Fusion indisponible : ')+(evidence.reason||'preuves insuffisantes')+(depthOnly?'. Le relief reste inspectable.':'. La segmentation reste inspectable.');
    const image=new Image();image.onload=()=>{
      if(version!==state.surfaceDrawVersion||job!==state.surfaceJob||session.id!==state.session?.id)return;
      try{drawSurfaceMask(image,result,value.obstacles);$('surfaceCanvas').hidden=false;}catch(e){clearSurfaceFrame(e.message);renderSurfaceImageQuality(result.imageQuality);renderSurfaceProposal(policy);}
    };
    image.onerror=()=>{if(version===state.surfaceDrawVersion)clearSurfaceFrame('PNG absente ou illisible ; aucun masque affiché.');};
    image.src=`/api/session/${session.id}/image/${index}`;
    const timing=depthOnly?`relief seul sur Mac ${fmt(result.relativeDepth?.inferenceMs??result.totalInferenceMs)} ms`:`calcul Mac combiné ${fmt(result.totalInferenceMs??result.inferenceMs)} ms (surfaces ${fmt(result.inferenceMs)} ms, relief ${fmt(result.relativeDepth?.inferenceMs)} ms)`;
    $('surfaceFrameStatus').textContent=`Image ${index+1} · ${timing} · ${value.partial?'résultat partiel':'analyse complète'}.`;
    renderSurfaceZones(result,evidence);
    renderSurfaceProposal(policy);
    textJson('surfaceJson',{analysisMode:state.surfaceJobMode,frameIndex:value.frameIndex,videoSessionId:value.videoSessionId,observedAtMs:value.observedAtMs,sourcePngSha256:value.sourcePngSha256,imageQuality:result.imageQuality??null,policy,zones:result.zones,obstacles:{...evidence,candidateMask:undefined},recordedDetections:value.recordedDetections,totalInferenceMs:result.totalInferenceMs,segmentationMs:depthOnly?null:result.inferenceMs,relativeDepthMs:result.relativeDepth?.inferenceMs});
  }catch(e){if(e.name!=='AbortError'&&version===state.surfaceDrawVersion)clearSurfaceFrame('Résultat surfaces indisponible : '+e.message);}
}
async function runSurfaceJob(){
  if(state.surfaceBusy||!surfaceModeStatus().installed||!replayAllowed('mac'))return;
  const session=state.session,version=++state.surfaceVersion,analysisMode=selectedSurfaceAnalysisMode();
  try{
    state.surfaceBusy=true;state.surfaceJob=null;state.surfaceJobMode=analysisMode;$('surfaceExport').disabled=true;refreshSurfaceControls();
    clearSurfaceFrame();$('surfaceJobStatus').textContent='Préparation de l’analyse surfaces…';
    const result=await request('/api/surfaces/analyze',{sessionId:session.id,analysisMode});
    if(version!==state.surfaceVersion||session.id!==state.session?.id){request(`/api/surfaces/jobs/${result.jobId}/cancel`,{}).catch(()=>{});return;}
    state.surfaceJob=result.jobId;pollSurfaceJob(result.jobId,version);
  }catch(e){if(version===state.surfaceVersion){state.surfaceBusy=false;$('surfaceJobStatus').textContent='Analyse refusée : '+e.message;refreshSurfaceControls();}}
}
async function pollSurfaceJob(id,version){
  try{
    const job=await request(`/api/surfaces/jobs/${id}`);
    if(id!==state.surfaceJob||version!==state.surfaceVersion||job.sessionId!==state.session?.id)return;
    state.surfaceJobMode=job.analysisMode||'semantic_depth';
    const terminal=['complete','failed','cancelled'].includes(job.state);
    $('surfaceJobStatus').textContent=job.state==='complete'?`Terminé : ${job.done}/${job.total} PNG · ${fmt(job.macBatchSeconds)} s sur Mac.`:
      terminal?`Résultat partiel ${job.done}/${job.total} : ${job.error||'analyse annulée'}.`:`Analyse surfaces ${job.done}/${job.total}…`;
    state.surfaceBusy=!terminal;refreshSurfaceControls();$('surfaceExport').disabled=!terminal||!job.reportAvailable;
    if(terminal||state.index<job.done)showSurfaceFrame(state.index);
    if(!terminal){clearTimeout(state.surfaceTimer);state.surfaceTimer=setTimeout(()=>pollSurfaceJob(id,version),1000);}
  }catch(e){if(id===state.surfaceJob&&version===state.surfaceVersion){$('surfaceJobStatus').textContent='Statut indisponible : '+e.message;state.surfaceBusy=false;refreshSurfaceControls();}}
}
$('surfaceAnalyze').onclick=runSurfaceJob;
$('surfaceRefresh').onclick=refreshSurfaceModel;
$('surfaceCancel').onclick=async()=>{
  const id=state.surfaceJob;
  if(!id){resetSurfaces();return;}
  try{await request(`/api/surfaces/jobs/${id}/cancel`,{});if(id===state.surfaceJob)$('surfaceJobStatus').textContent='Annulation demandée ; le calcul de l’image en cours doit se terminer.';}catch(e){if(id===state.surfaceJob)$('surfaceJobStatus').textContent=e.message;}
};
$('surfaceExport').onclick=()=>{if(!state.surfaceJob)return;const link=document.createElement('a');link.href=`/api/surfaces/jobs/${state.surfaceJob}/report`;link.download=`OriaLab-surfaces-${state.surfaceJob}.jsonl`;link.click();};

$('surfaceView').onchange=()=>showSurfaceFrame(state.index);

$('surfacePrevious').onclick=()=>stepSurfaceFrame(-1);
$('surfaceNext').onclick=()=>stepSurfaceFrame(1);

$('surfaceAnalysisMode').onchange=()=>{if(state.surfaceBusy){$('surfaceAnalysisMode').value=state.surfaceJobMode||'semantic_depth';return;}resetSurfaces();refreshSurfaceModelText();refreshSurfaceLegend();};
