'use strict';
const $ = id => document.getElementById(id);
const state = {token:null,session:null,index:0,playing:false,timer:null,job:null,jobKind:null,
  drawVersion:0,sessionVersion:0,loadVersion:0,libraryVersion:0,jobVersion:0,audioVersion:0,mode:'recorded',comparison:null,audioUrl:null,pendingArchive:null,frameAbort:null,seekTimer:null,exportId:null,exportVersion:0,exportTimer:null,jobBusy:false};
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
      state.session=null;state.job=null;state.jobKind=null;state.comparison=null;state.jobBusy=false;
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
request('/api/config').then(async c=>{state.token=c.token;$('footer').textContent=`Stockage local : ${c.storage} · Temps Mac et téléphone distincts.`;$('previewAudio').disabled=!c.audioPreviewAvailable;$('previewStatus').textContent=c.audioPreviewAvailable?'Aucune écoute automatique. Utilisez le bouton pour écouter.':'Aperçu indisponible : voix macOS et FFmpeg local requis.';await refreshLibrary();const imported=new URLSearchParams(location.search).get('session');if(imported)await openSession(imported);}).catch(error);
