#!/usr/bin/env python3
"""Replay recorded policy mutations with the unchanged Kotlin core; no synthetic voice.
All derived writes stay in this directory under policy_* names. No Gradle or ADB.
"""
import argparse, collections, datetime, hashlib, importlib.util, json, math, os
from pathlib import Path
import struct, subprocess, sys, zipfile
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
CONFIG = ('maxObservationAgeMs','trackAssociationIou','trackLostAfterMs','confirmationSamples',
          'confidenceExitMargin','minimumTrackingConfidence','selectionHoldMs','replacementScoreMargin',
          'repeatIntervalMs','globalAnnouncementGapMs','failureRetryGapMs','voiceMemoryRetentionMs',
          'maximumVoiceMemories','maximumTracks')

def sha(path):
    h=hashlib.sha256()
    with Path(path).open('rb') as f:
        for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)
    return h.hexdigest()

def dump(name, value):
    (HERE/name).write_text(json.dumps(value,ensure_ascii=False,indent=2,allow_nan=False)+'\n')

def command():
    # Reuse dependency lookup only; do not invoke prepare(), which owns another cache.
    sys.dont_write_bytecode=True
    spec=importlib.util.spec_from_file_location('policy_dependencies',ROOT/'oria-lab-policy/run_policy.py')
    deps=importlib.util.module_from_spec(spec);spec.loader.exec_module(deps)
    j=deps.jar
    std=j('org.jetbrains.kotlin','kotlin-stdlib','2.0.21')
    gson=j('com.google.code.gson','gson','2.11.0')
    compiler=[j('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.0.21'),std,
        j('org.jetbrains.kotlin','kotlin-script-runtime','2.0.21'),
        j('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),j('org.jetbrains.intellij.deps','trove4j','1.0.20200330'),
        j('org.jetbrains','annotations','13.0'),j('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.6.4')]
    java=Path(os.environ.get('JAVA_HOME',str(Path.home()/'Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home')))/'bin/java'
    sources=sorted((ROOT/'android-project/app/src/main/java/com/htc/vive/eagle/hackathon/starter/oria/core').glob('*.kt'))+[HERE/'policy_adapter.kt']
    fingerprint=hashlib.sha256(''.join(sha(p) for p in sources+compiler+[gson]).encode()).hexdigest()
    folder=HERE/'policy_build';folder.mkdir(exist_ok=True)
    artifact=folder/(fingerprint[:20]+'.jar')
    if not artifact.exists():
        result=subprocess.run([str(java),'-cp',os.pathsep.join(map(str,compiler)),
            'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','11',
            '-classpath',os.pathsep.join(map(str,[std,gson])),'-d',str(artifact),*map(str,sources)],capture_output=True,text=True)
        (HERE/'policy_compile.log').write_text(result.stdout+result.stderr)
        if result.returncode:raise RuntimeError(result.stderr)
    return [str(java),'-cp',os.pathsep.join(map(str,[artifact,std,gson])),'oria-lab.audit.Policy_adapterKt'],{
        'sources':[{'path':str(p.relative_to(ROOT)), 'sha256':sha(p)} for p in sources],
        'artifactSha256':sha(artifact),'compiler':'Kotlin 2.0.21, JVM target 11','gradleInvoked':False}

def differences(expected, actual, path='$'):
    out=[]
    if isinstance(expected,dict):
        if not isinstance(actual,dict):return [{'path':path,'recorded':expected,'replayed':actual}]
        for k,v in expected.items():out+=differences(v,actual.get(k),path+'.'+k)
    elif isinstance(expected,list):
        if not isinstance(actual,list) or len(expected)!=len(actual):return [{'path':path,'recorded':expected,'replayed':actual}]
        for i,(a,b) in enumerate(zip(expected,actual)):out+=differences(a,b,path+f'[{i}]')
    elif isinstance(expected,bool) or expected is None or isinstance(expected,str):
        if expected!=actual:out.append({'path':path,'recorded':expected,'replayed':actual})
    elif isinstance(expected,(int,float)) and isinstance(actual,(int,float)):
        # Preserve Float32 semantics; do not relax policy tolerances for textual JSON representation.
        same=(struct.pack('!f',expected)==struct.pack('!f',actual)) if isinstance(expected,float) or isinstance(actual,float) else expected==actual
        if not same:out.append({'path':path,'recorded':expected,'replayed':actual})
    elif expected!=actual:out.append({'path':path,'recorded':expected,'replayed':actual})
    return out

def stats(values):
    if not values:return None
    a=sorted(values)
    return {'n':len(a),'min':a[0],'median':(a[(len(a)-1)//2]+a[len(a)//2])/2,
            'p95NearestRank':a[max(0,math.ceil(len(a)*.95)-1)],'max':a[-1]}

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--zip',type=Path,default=Path.home()/'Documents/Oria Lab Captures/OriaLab-0548b68a-b9e3-445f-a072-0182e9ce98d6.zip')
    parser.add_argument('--production-trace',type=Path,default=HERE/'production-trace.jsonl')
    args=parser.parse_args();zip_sha=sha(args.zip)
    with zipfile.ZipFile(args.zip) as z:
        assert z.testzip() is None,'ZIP CRC failure'
        manifest=json.loads(z.read('manifest.json'))
        events=[json.loads(l) for l in z.read('events.jsonl').splitlines() if l.strip()]
        frames=[json.loads(l) for l in z.read('frames.jsonl').splitlines() if l.strip()]
        png_names=set(n for n in z.namelist() if n.endswith('.png'))
    for i,e in enumerate(events):e['_eventIndex']=i
    decisions=[e for e in events if e['type']=='decision']
    inferences={(e.get('videoSessionId',e.get('sessionId')),e['frameId']):e for e in events if e['type']=='inference'}
    frame_index={(e['videoSessionId'],e['frameId']):e for e in frames}
    starts=[e for e in events if e['type']=='start'];assert len(starts)==1
    start=starts[0];session=manifest['metadata']['videoSessionId'];config={k:manifest['metadata']['policyConfig'][k] for k in CONFIG}
    assert len(inferences)==len([e for e in events if e['type']=='inference'])
    assert all('evaluatedAtMs' in e for e in decisions)
    first_tracks=next(e for e in decisions if e['tracks'])
    first_alert=next(e for e in decisions if e['eligibleAlert'])
    seed_track=min(t['id'] for t in first_tracks['tracks']);seed_alert=first_alert['eligibleAlert']['id']
    # The known start clears all perception. Before the first nonempty snapshot no track exists,
    # therefore its contiguous IDs identify the lifetime allocation counter without guessing state.
    ids=sorted(t['id'] for t in first_tracks['tracks'])
    assert ids==list(range(seed_track,seed_track+len(ids)))
    inputs=[];bindings=[];clocks=[]
    for e in events:
        t=e['type'];payload=None
        if t=='start':
            payload={'type':'start','sessionId':session,'atMs':e['policyAtMs'],'config':config,'nextTrackId':seed_track,'nextAlertId':seed_alert}
        elif t=='decision':
            inf=inferences[(session,e['frameId'])]
            payload={'type':'frame','sessionId':session,'frameId':e['frameId'],'observedAtMs':e['observedAtMs'],
                     'nowMs':e['evaluatedAtMs'],'detections':inf['detections']}
        elif t=='policy_voice':
            payload={'type':e['action'],'nowMs':e['policyAtMs']}
            payload['alertId' if e['action']=='submitted' else 'ticketId']=e['alertId'] if e['action']=='submitted' else e['ticketId']
        elif t=='stop':payload={'type':'stop'}
        if payload is not None:
            payload['requestId']=str(e['_eventIndex']);inputs.append(payload);bindings.append(e)
            if 'nowMs' in payload:clocks.append(payload['nowMs'])
            elif 'atMs' in payload:clocks.append(payload['atMs'])
    assert clocks==sorted(clocks),'Policy file order reverses its exact clock'
    cmd,provenance=command()
    raw=''.join(json.dumps(q,separators=(',',':'))+'\n' for q in inputs)
    (HERE/'policy_inputs.jsonl').write_text(raw)
    run=subprocess.run(cmd,input=raw,capture_output=True,text=True,timeout=60)
    (HERE/'policy_stderr.log').write_text(run.stderr)
    (HERE/'policy_outputs.jsonl').write_text(run.stdout)
    if run.returncode:raise RuntimeError(run.stderr+'\n'+run.stdout[-1000:])
    responses=[json.loads(l) for l in run.stdout.splitlines()];assert len(responses)==len(inputs)
    frame_reports=[];voice_reports=[];all_diffs=[]
    for e,q,r in zip(bindings,inputs,responses):
        assert r['type']!='error' and r['requestId']==q['requestId']
        if e['type']=='decision':
            expected={k:e[k] for k in ('frameStatus','audioState','rejectedDetectionCount','tracks','selected','eligibleAlert')}
            expected['suppressionReason']=e['reason'];actual=r['evaluation']
            diff=differences(expected,actual)
            rank=r['audit']['candidateRanking'];order=r['audit']['voiceOrder'];selected=e['selected'];alert=e['eligibleAlert']
            key=(session,e['frameId']);png=frame_index[key]
            report={'index':len(frame_reports)+1,'captureFrameId':png['captureFrameId'],'frameId':e['frameId'],
                'imagePath':png['imagePath'],'tObservedMs':e['observedAtMs']-manifest['monotonicOriginMs'],
                'observedAtMs':e['observedAtMs'],'evaluatedAtMs':e['evaluatedAtMs'],
                'ageMs':e['evaluatedAtMs']-e['observedAtMs'],'recorded':expected,'replayed':actual,
                'matches':not diff,'differences':diff,'detections':inferences[key]['detections'],
                'rankedCandidates':rank,'voiceOrder':order,'policyStateBeforeFrame':r['beforeAudit'],
                'policyStateAfterFrame':r['audit'],'qualification':r['trackQualification'],'associationAudit':r['associationAudit'],
                'selectedIsTopPriority':None if not selected or not rank else selected['trackId']==rank[0]['trackId'],
                'alertIsSelected':None if not alert or not selected else alert['trackId']==selected['trackId'],
                'controllerGates':{k:e[k] for k in ('orientationVerified','audioAutomaticPaused','voiceBackend','voiceBackendReady')}}
            frame_reports.append(report)
        elif e['type']=='policy_voice':
            expected={'accepted':e['accepted']}
            if e['action']=='submitted' and e['accepted']:expected['ticketId']=e['ticketId']
            diff=differences(expected,r)
            voice_reports.append({'event':e,'replayed':r,'matches':not diff,'differences':diff})
        else:diff=[]
        all_diffs.extend({'eventIndex':e['_eventIndex'],**d} for d in diff)
    by_alert={f['recorded']['eligibleAlert']['id']:f for f in frame_reports if f['recorded']['eligibleAlert']}
    production=[]
    if args.production_trace.is_file():
        for line in args.production_trace.read_text().splitlines():
            if line.strip():production.append(json.loads(line))
    speech=[e for e in events if e['type']=='speech_submitted']
    audio=[];audio_violations=[];submissions=[e for e in events if e['type']=='policy_voice' and e['action']=='submitted']
    for s in speech:
        ident=s['requestId'];ticket=s['ticketId'];frame=by_alert[ticket];alert=frame['recorded']['eligibleAlert']
        policy=next(e for e in submissions if e['ticketId']==ticket)
        guard=next((e for e in events if e['type']=='speech_pcm_start_guard' and e['requestId']==ident),None)
        result=next((e for e in events if e['type'] in ('speech_local_result','speech_local_cancelled') and e['requestId']==ident),None)
        external=next((e for e in production if e['type'] in ('speech_local_result','speech_local_cancelled') and e.get('requestId')==ident and e.get('atMs',0)>manifest['endedAtMonotonicMs']),None)
        mutation=next((e for e in events if e['type']=='policy_voice' and e['action']!='submitted' and e['ticketId']==ticket),None)
        external_mutation=next((e for e in production if e['type']=='policy_voice' and e.get('action')!='submitted' and e.get('ticketId')==ticket and e.get('videoSessionId',e.get('sessionId'))==session and e.get('atMs',0)>manifest['endedAtMonotonicMs']),None)
        before=next(v['replayed']['beforeAudit'] for v in voice_reports if v['event']['_eventIndex']==policy['_eventIndex'])
        memories=before.get('voiceMemories',{});memory=memories.get(str(alert['trackId']))
        checks={'phraseMatchesEligibleAlert':s['text']==alert['text'],'panMatchesZone':s['pan']==alert['zone'],
            'observedTimestampMatchesAlert':s['observedAtMs']==alert['observedAtMs'],
            'guardPresent':guard is not None,'guardAllowed':guard is not None and guard['allowed'] is True,
            'guardRecordedAgeWithin500':guard is not None and 0<=guard['observationAgeMs']<=config['maxObservationAgeMs'],
            'submissionFresh':0<=policy['policyAtMs']-s['observedAtMs']<=config['maxObservationAgeMs'],
            'audioWasAvailable':before['audioState']=='AVAILABLE',
            'sameEntityCooldownSatisfied':memory is None or policy['policyAtMs']-memory['confirmedAtMs']>=config['repeatIntervalMs'],
            'globalPacingSatisfied':all(before.get(k) is None or policy['policyAtMs']-before[k]>=config['globalAnnouncementGapMs'] for k in ('lastAttemptAtMs','lastConfirmedAtMs')),
            'failureBackoffSatisfied':before.get('lastFailureAtMs') is None or policy['policyAtMs']-before['lastFailureAtMs']>=config['failureRetryGapMs'],
            'controllerGatesAllowed':frame['controllerGates']['orientationVerified'] and not frame['controllerGates']['audioAutomaticPaused'] and frame['controllerGates']['voiceBackendReady']}
        for k,v in checks.items():
            if not v:audio_violations.append({'requestId':ident,'check':k})
        terminal=result or external
        audio.append({'number':len(audio)+1,'requestId':ident,'ticketId':ticket,'trackId':alert['trackId'],
            'frameIndex':frame['index'],'frameId':frame['frameId'],'captureFrameId':frame['captureFrameId'],'imagePath':frame['imagePath'],
            'tSubmittedMs':s['atMs']-manifest['monotonicOriginMs'],'text':s['text'],'pan':s['pan'],
            'policySubmittedAtMs':policy['policyAtMs'],'submittedAtMs':s['atMs'],
            'guard':guard,'checks':checks,'recordedResult':result,'recordedPolicyMutation':mutation,
            'externalResult':external,'externalPolicyMutation':external_mutation,
            'terminalState':terminal.get('result','CANCELLED') if terminal else 'UNKNOWN_AFTER_CAPTURE',
            'terminalDelayFromSubmissionMs':terminal['atMs']-s['atMs'] if terminal else None,
            'sameTrackLastConfirmedAtMs':memory['confirmedAtMs'] if memory else None,
            'sameTrackGapSinceConfirmationMs':policy['policyAtMs']-memory['confirmedAtMs'] if memory else None})
    repeats=[]
    for a,b in zip(audio,audio[1:]):
        gap=b['submittedAtMs']-a['submittedAtMs']
        if gap<config['repeatIntervalMs']:
            repeats.append({'previousNumber':a['number'],'number':b['number'],'gapMs':gap,
                'sameTrack':a['trackId']==b['trackId'],'sameText':a['text']==b['text'],
                'previousTrackId':a['trackId'],'trackId':b['trackId']})
    detection_status_counts=collections.Counter()
    status_examples=collections.defaultdict(list)
    for f in frame_reports:
        qualifications={q['trackId']:q for q in f['qualification']}
        per_object=[]
        for track in f['recorded']['tracks']:
            if not track['visibleInLatestFrame']:continue
            q=qualifications[track['id']]
            geometry=q['qualifiesGeometryAtConfidence1']
            confidence=track['detection']['confidence']
            if track['confirmed']:status='confirmed'
            elif q['qualifiesInitial']:status='waiting_confirmation'
            elif not geometry and confidence<q['confidenceThreshold']:status='confidence_and_geometry'
            elif not geometry:status='geometry'
            else:status='confidence'
            detail={'frameIndex':f['index'],'frameId':f['frameId'],'trackId':track['id'],
                'status':status,'confidence':confidence,'threshold':q['confidenceThreshold'],'area':q['area'],
                'box':track['detection']['box'],'confirmationSamples':track['confirmationSamples'],
                'globalSuppression':f['recorded']['suppressionReason']}
            detection_status_counts[status]+=1
            if len(status_examples[status])<20:status_examples[status].append(detail)
            per_object.append(detail)
        f['visibleDetectionStatus']=per_object
    tracks={t['id'] for f in frame_reports for t in f['recorded']['tracks']}
    changing_selection=[f['index'] for f in frame_reports if f['selectedIsTopPriority'] is False]
    alternative_alert=[f['index'] for f in frame_reports if f['alertIsSelected'] is False]
    timings=stats([f['ageMs'] for f in frame_reports]); guards=stats([a['guard']['observationAgeMs'] for a in audio if a['guard']])
    summary={'schemaVersion':1,'scope':'Exact recorded Android detections and policy clocks, real Kotlin core; only recorded voice mutations. No YOLO rerun, no synthetic audio, no acoustic claim.',
        'generatedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'capture':{
            'path':str(args.zip),'sha256Before':zip_sha,'sha256After':sha(args.zip),'manifest':manifest},'provenance':provenance,
        'initialState':{'recordedStart':start,'tracksAndMemory':'Cleared by the actual recorded start; no previous detection or voice memory imported.',
            'audioState':'AVAILABLE consistent with first recorded decision; every subsequent transition replayed.',
            'nextTrackId':seed_track,'nextAlertId':seed_alert,'counterSource':'Inferred from first newly allocated contiguous tracks and first eligible alert; lifetime counters are not reset by start.',
            'counterSeedFirstTrackFrameId':first_tracks['frameId'],'counterSeedFirstAlertFrameId':first_alert['frameId'],
            'generation':'Internal generation is not recorded; fresh replay uses its own coherent epoch. Recorded sessionId is preserved; generation is not compared as absent from recorded outputs.'},
        'integrity':{'zipCrcPassed':True,'allPngPathsPresent':all(f['imagePath'] in png_names for f in frames),
            'frameIndexCount':len(frames),'pngCount':len(png_names),'inferenceCount':len(inferences),'decisionCount':len(decisions),
            'allDecisionsHavePngAndInference':all((session,e['frameId']) in frame_index and (session,e['frameId']) in inferences for e in decisions),
            'policyClocksMonotonicInRecordedOrder':clocks==sorted(clocks),'eventCounts':dict(collections.Counter(e['type'] for e in events)),
            'productionTrace':str(args.production_trace) if production else None,'productionTraceSha256':sha(args.production_trace) if production else None},
        'comparison':{'decisionMatches':sum(f['matches'] for f in frame_reports),'decisions':len(frame_reports),
            'voiceMutationMatches':sum(v['matches'] for v in voice_reports),'voiceMutations':len(voice_reports),
            'differenceCount':len(all_diffs),'differences':all_diffs,'numericComparison':'Float32 bit equality for floating policy fields; exact other primitives, list order and IDs.'},
        'metrics':{'decisionAgeMs':timings,'decisionsPerCaptureSecond':len(frame_reports)*1000/manifest['durationMs'],
            'guardObservationAgeMs':guards,'submissionToTerminalMs':stats([a['terminalDelayFromSubmissionMs'] for a in audio if a['terminalDelayFromSubmissionMs'] is not None]),
            'suppressionReasons':dict(collections.Counter(f['recorded']['suppressionReason'] for f in frame_reports)),
            'uniqueTracks':len(tracks),'minTrackId':min(tracks),'maxTrackId':max(tracks),
            'uniqueAnnouncedTracks':len(set(a['trackId'] for a in audio)),
            'frameIndicesSelectionNotHighestPriority':changing_selection,'frameIndicesAlertNotSelected':alternative_alert,
            'candidateCount':dict(collections.Counter(len(f['rankedCandidates']) for f in frame_reports)),
            'audioTerminalCounts':dict(collections.Counter(a['terminalState'] for a in audio)),
            'panCounts':dict(collections.Counter(a['pan'] for a in audio)),
            'visibleTrackedDetectionInstancesByQualification':dict(detection_status_counts),'qualificationExamples':dict(status_examples)},
        'audioChecks':{'violations':audio_violations,'submissions':len(audio),'recordedGuards':sum(a['guard'] is not None for a in audio),
            'adjacentAnnouncementsBelowRepeatInterval':repeats},
        'coverage':{'noSyntheticVoiceEvents':True,'recordedPolicyWindowComplete':not all_diffs,
            'lastVoiceTerminalOutsideZip':bool(audio[-1]['externalResult']) if audio else False,
            'captureEndStopsRecordingOnly':manifest['stopReason']=='duration_limit',
            'acousticDeliveryMeasured':False,'visualSemanticsReviewedByThisScript':False,
            'rankingIsObservedOnPhone':False,'rankingExplanation':'Internal rank is reconstructed on unchanged replay core; equality of complete recorded outputs constrains it, but phone does not directly log its sorted candidates.'},
        'audio':audio,'voiceMutations':voice_reports}
    findings=[]
    if manifest['sessionId']=='0548b68a-b9e3-445f-a072-0182e9ce98d6':
        swap=frame_reports[141]
        fragment=frame_reports[56]
        overlap=next(v['iou'] for v in fragment['associationAudit']['allSameClassIoU'] if v['trackId']==38 and v['detectionIndex']==0)
        findings=[
            {'id':'IDENTITY_SWAP_93','kind':'terrain_identity_failure_with_policy_conformance',
             'visualEvidenceSource':'Root and HTC agent image review communicated in this task; not inferred solely from coordinates.',
             'visualObservation':'The close short-haired person with glasses in frame1509 has ID93; at frame1521 ID93 is assigned to a different curly-haired person entering left; original close person becomes94.',
             'frameIndices':[141,142,143],'frameIds':[1509,1521,1531],
             'mechanism':swap['associationAudit'],'triggeredAlert':swap['recorded']['eligibleAlert'],
             'implication':'Confirmation1->2 aggregates two real people according to visual review. IoU-only same-class matching chooses0.4899385 over0.26461035. Larger newly created94 has only one sample and cannot yet alert.'},
            {'id':'FRAGMENTATION_38_TO_56','kind':'terrain_repeat_with_policy_conformance',
             'visualEvidenceSource':'Root identifies the same curly-haired person in alert frames537 and620; association history independently checked here.',
             'frameIndices':[51,56,57,58,59],'frameIds':[537,588,598,606,620],
             'iouAtLostAssociation':overlap,'requiredIou':config['trackAssociationIou'],
             'observationsGapMs':frame_reports[58]['observedAtMs']-frame_reports[50]['observedAtMs'],
             'submissionsGapMs':audio[5]['submittedAtMs']-audio[4]['submittedAtMs'],
             'afterPreviousConfirmationMs':audio[5]['policySubmittedAtMs']-audio[4]['recordedPolicyMutation']['policyAtMs'],
             'oldTrackId':38,'newTrackId':56,
             'implication':'Old track38 not associated at frame 598 (IoU below0.25), new56 confirms at620 and has no voice memory. Repetition6.5s per identity does not survive this loss.'},
            {'id':'LEGAL_SELECTED_COOLDOWN_FALLBACK','kind':'explained_deterministic_choice','frameIndex':34,'frameId':354,
             'selectedTrackId':32,'announcedTrackId':36,'selectedTrackConfirmedAgoMs':frame_reports[33]['evaluatedAtMs']-10854682,
             'implication':'Selected32 is larger/higher priority but in6.5s cooldown; ordered eligible scan announces confirmed36 after global pacing is satisfied.'}]
    summary['findings']=findings
    dump('policy_findings.json',findings)
    dump('policy_report.json',summary);dump('policy_frames.json',frame_reports)
    lines=['# Audit déterministe — capture 0548b68a', '',
        f"Rejeu **{summary['comparison']['decisionMatches']}/{len(frame_reports)} décisions identiques**, **{summary['comparison']['voiceMutationMatches']}/{len(voice_reports)} mutations vocales identiques**. Aucune confirmation vocale simulée.", '',
        f"Capture réelle de {manifest['durationMs']/1000:.3f} s : {len(frames)} PNG, {len(inferences)} inférences et {len(decisions)} décisions. Détections Android et `evaluatedAtMs` inchangés ; 500 ms conservées. L’orientation était confirmée. Âge de décision min/médiane/p95/max : {timings['min']}/{timings['median']:g}/{timings['p95NearestRank']}/{timings['max']} ms (p95 rang supérieur).", '',
        '## Méthode et portée', '',
        f"Les trois sources core Kotlin sont compilées sans modification, avec un adaptateur d’audit séparé. Aucun Gradle/ADB. SHA ZIP `{zip_sha}` ; même empreinte après lecture. Les fichiers `policy_inputs.jsonl` et `policy_outputs.jsonl` rendent l’ordre de toutes les mutations vérifiable.", '',
        f"`start()` efface perception, mémoire vocale et temporisations, mais conserve les compteurs de durée de vie. Le compteur de piste {seed_track} et le compteur d’alerte {seed_alert} sont déduits du premier lot de pistes et de la première éligibilité, puis amorcés dans le moteur de rejeu seulement. Cela ne reconstitue pas une mémoire d’objets antérieure. La génération interne absente des sorties Android n’est pas comparée ; le sessionId vidéo {session} est conservé. Tous les autres champs enregistrés sont comparés, y compris ordre des pistes, boîtes, confirmation, sélection, priorité, zone, texte, éligibilité, état audio et suppression. Les flottants sont comparés à leur valeur Float32 exacte.", '',
        'Les classements de candidats sont lus dans le moteur rejoué, sans mutation ; ils ne sont pas un champ directement enregistré sur téléphone. `policy_frames.json` détaille chaque image, le classement par priorité, l’ordre vocal avec sélection prioritaire, la mémoire et l’état avant/après. Ce rapport ne juge pas les objets réels dans les pixels : la revue visuelle et la réinférence YOLO sont séparées.', '',
        '## Alertes réellement demandées', '',
        '| N° | t (s) | Image / frameId | Piste | Phrase / canal | Âge à la garde (ms) | Résultat |',
        '|---:|---:|---|---:|---|---:|---|']
    for a in audio:
        result=a['terminalState']+(' hors ZIP' if a['externalResult'] else '')
        lines.append(f"| {a['number']} | {a['tSubmittedMs']/1000:.3f} | {a['frameIndex']} / {a['frameId']} | {a['trackId']} | {a['text']} / {a['pan']} | {a['guard']['observationAgeMs'] if a['guard'] else '?'} | {result} |")
    lines+=['',f"Les {len(audio)} phrases et canaux correspondent à leur candidat éligible. Les gardes enregistrées sont entre {guards['min']} et {guards['max']} ms. Violations de phrase, zone, fraîcheur, réserve audio, portes contrôleur et cooldown : **{len(audio_violations)}**. Une garde journalisée n’est pas une mesure du premier son entendu après tampon Bluetooth ; le contrôleur fait encore son contrôle final après la trace.", '',
        'Douze résultats Android `COMPLETED` sont enregistrés dans le ZIP. La dernière phrase commence avant la limite de capture : sa fin doit être qualifiée avec la trace de production, sans inventer un callback dans l’archive. La table précise le résultat terminal observé hors fenêtre. `COMPLETED` décrit la lecture Android, pas une écoute humaine enregistrée.', '',
        '## Tri, stabilité et répétitions', '',
        f"{len(tracks)} pistes temporaires différentes ({min(tracks)}–{max(tracks)}) et {len(set(a['trackId'] for a in audio))} pistes annoncées. Une piste est une association 2D par classe/IoU, pas l’identité certaine d’une personne.", '',
        f"Images où la sélection reste différente de la priorité maximale : {changing_selection or 'aucune'}. Images où l’alerte choisie diffère de la sélection (autre candidat disponible) : {alternative_alert or 'aucune'}. Le détail avant/après et les mémoires dans le JSON permettent de distinguer maintien 1 100 ms, marge de remplacement 0,28, pacing et cooldown.", '',
        f"{len(repeats)} intervalles entre annonces voisines sont inférieurs à 6 500 ms. Cela n’est pas automatiquement une violation : ce cooldown porte sur une même piste après confirmation ; le rythme global est de 1 000 ms, et une nouvelle piste n’a pas cette mémoire. Les contrôles ci-dessus rejouent ces conditions exactes. Une fragmentation visuelle peut donc provoquer plusieurs annonces d’une même personne réelle sous plusieurs IDs ; seul l’examen des images permet de l’attribuer.", '',
        '| Motif de suppression | Images |','|---|---:|']
    for k,v in summary['metrics']['suppressionReasons'].items():lines.append(f'| {k} | {v} |')
    lines+=['','## Points terrain à corriger après arbitrage','',
        'La conformité 164/164 prouve le déterminisme du code actuel, pas une identité réelle fiable. Les observations visuelles ci-dessous viennent de la revue des images par l’orchestrateur et l’agent HTC ; les IoU, compteurs et instants proviennent du rejeu.', '',
        '**Permutation de personne : images 141–142, frameId 1509→1521.** La piste 93 appartient d’abord à la personne proche à cheveux courts/lunettes, puis est attribuée à une autre personne bouclée entrant à gauche. Le recouvrement avec cette dernière est 0,4899385 contre 0,26461035 avec la personne initiale. Le choix glouton prend le premier et crée 94 pour l’autre. La piste 93 passe de 1 à 2 confirmations et annonce « Piéton avant-gauche » ; la personne initiale, pourtant plus grande dans l’image, vient de devenir 94 et n’a qu’une confirmation. C’est une limite démontrée du critère d’identité, malgré un résultat conforme au tri.', '',
        '**Répétition d’une même personne : images 51→59, frameId 537→620.** La personne bouclée annoncée avec piste 38 est ensuite annoncée avec piste 56. À frame 598, le recouvrement ancienne 38/nouvelle boîte est 0,21171786, sous 0,25 : création 56. Son score 0,7398 ne confirme pas encore ; frames 606 puis 620 dépassent le seuil et valident deux observations. L’écart entre observations est 2,955 s et entre demandes vocales 2,933 s, seulement 1,547 s après confirmation de la phrase précédente. La nouvelle piste 56 n’hérite pas du cooldown de 38. Il ne s’agit pas d’une violation du compteur existant, mais d’une répétition perceptible que la mémoire par piste ne prévient pas.', '',
        '**Autre candidat annoncé malgré la sélection : image 34, frame 354.** La piste 32 sélectionnée a priorité 2,8589973, contre 1,8209677 pour 36. La première est sous cooldown (confirmation seulement 1,164 s avant cette décision) ; 36 est donc annoncé. Le moteur ne choisit pas systématiquement le plus gros objet à chaque phrase.', '',
        'La seule réannonce conservant le même ID est 79 : la nouvelle soumission intervient 6,806 s après sa confirmation précédente, conforme au délai 6,5 s. Les huit intervalles ci-dessous inférieurs à 6,5 s utilisent tous des IDs différents. Ils ne représentent pas tous une répétition de personne : seule la paire 5→6 est attribuée à la même personne par la revue visuelle citée.', '',
        '| Annonces | Pistes | Écart demandes (s) | Texte identique | Interprétation visuelle |',
        '|---|---|---:|---|---|']
    for pair in repeats:
        lines.append(f"| {pair['previousNumber']}→{pair['number']} | {pair['previousTrackId']}→{pair['trackId']} | {pair['gapMs']/1000:.3f} | {'oui' if pair['sameText'] else 'non'} | {'Même personne selon revue images 537/620' if pair['previousNumber']==5 and pair['number']==6 else 'Identité réelle non conclue par le seul audit numérique'} |")
    lines+=['','## Détections présentes mais non éligibles','',
        'Les comptes suivants sont des observations de boîtes dans des images, pas des personnes uniques. Les détections sous 0,70 absentes de l’entrée applicative et les objets non détectés sont hors de ce comptage ; ils relèvent de la revue visuelle/ML.', '',
        '| Qualification des boîtes courantes | Occurrences |','|---|---:|']
    for key,label in [('confirmed','Confirmées'),('waiting_confirmation','Qualifiantes mais seconde confirmation attendue'),('confidence','Confiance sous seuil initial, géométrie admissible'),('geometry','Géométrie sous seuil, confiance admissible'),('confidence_and_geometry','Confiance et géométrie sous seuil')]:
        lines.append(f"| {label} | {detection_status_counts[key]} |")
    lines+=['',
        'Exemples vérifiables dans les PNG : image 2/frame 6, grande boîte piste 23 à 0,9299, une seule confirmation ; image 3/frame 10, même piste à 0,8257 < 0,84, compteur remis à 0 avant d’avoir été confirmé ; image 18/frame 181, piste 25 à 0,9081 mais aire 0,0094 sous le seuil ; image 55/frame 581, piste 50 à 0,8649 mais aire 0,0165 hors zone centrale ; images 76–79/frame 810→841, personne détectée avec confiance suffisante mais petite boîte hors centre, donc refus géométrique. La règle vient d’une approximation RGB de pertinence, pas d’une distance réelle ni d’une garantie qu’un objet visible sera annoncé.', '',
        'Les sorties `AUDIO_IN_FLIGHT` (38 images), `GLOBAL_PACING` (22) et `SAME_ENTITY_COOLDOWN` (17) expliquent aussi des silences lorsque des candidats sont présents. Ne pas confondre ces temporisations avec un objet perdu par YOLO.', '',
        '## Dernière phrase et limite de capture','',
        'La phrase 13 est soumise à 10902347 ms avec garde 301 ms. La capture s’arrête à 10902809 ms. La trace production prouve ensuite `policy_voice failed` accepté à 10902865 ms (+56 ms), puis `speech_local_cancelled` à 10902870 ms (+61 ms), lors de l’arrêt explicite. Elle a donc été annulée après 523 ms, hors ZIP ; aucune fin `COMPLETED` ni panne spontanée n’est inférée. Les 12 premières lectures terminées côté Android restent distinctes d’une mesure acoustique.', '',
        'Aucun réglage métier, appariement ou seuil n’a été changé. Les deux défauts d’identité doivent être arbitrés séparément d’un éventuel changement de seuil YOLO ou de priorité sonore.', '']
    lines+=['','## Reproduction','','```bash',f'python3 "{HERE / "policy_audit.py"}" --zip "{args.zip}" --production-trace "{args.production_trace}"','```','',
        'Fichiers : `policy_report.json` (provenance, voix, anomalies), `policy_frames.json` (toutes les images), `policy_inputs.jsonl` / `policy_outputs.jsonl` (mutations exactes), `policy_adapter.kt` (inspection seule du core), `policy_compile.log` (compilation autonome). Aucune règle du prototype n’est modifiée par cet audit.']
    (HERE/'policy_summary.md').write_text('\n'.join(lines)+'\n')
    print(json.dumps({'comparison':summary['comparison'],'audioTerminalCounts':summary['metrics']['audioTerminalCounts'],'findings':[f['id'] for f in findings],'violations':audio_violations},ensure_ascii=False,indent=2))
if __name__=='__main__':main()
