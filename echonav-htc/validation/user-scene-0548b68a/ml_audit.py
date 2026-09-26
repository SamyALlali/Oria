#!/usr/bin/env python3
"""Read-only EchoTest capture audit: unchanged ONNX, strict parity, contact sheets.

Run from echonav-htc with ml/.venv/bin/python validation/user-scene-0548b68a/ml_audit.py
No Android commands, server changes, model export or thresholds adjusted here.
"""
from __future__ import annotations
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import platform
import subprocess
import sys
import tempfile
import time
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'echotest-desktop'))
import numpy as np
import onnxruntime
import PIL
from PIL import Image, ImageDraw, ImageFont
import scipy
from scipy.optimize import linear_sum_assignment
from replay import (SessionStore, MacDetector, MODEL, MODEL_SHA, CLASSES, frame_key,
                    find_ffmpeg, sha256)

FLOOR = np.float32(.70)
MAX_XY = 1.0
MAX_SCORE = .001


def stats(values):
    a = np.asarray(values, dtype=float)
    if not a.size:
        return None
    return {'count': int(a.size), 'min': float(a.min()), 'median': float(np.median(a)),
            'p95': float(np.percentile(a, 95)), 'max': float(a.max())}


def match_raw(recorded, recalculated):
    a = np.asarray(recorded, dtype=np.float32).reshape(300, 6)
    b = np.asarray(recalculated, dtype=np.float32).reshape(300, 6)
    if not np.isfinite(a).all() or not np.isfinite(b).all():
        raise ValueError('Non-finite model output')
    xy = np.max(np.abs(a[:, None, :4] - b[None, :, :4]), axis=2)
    scores = np.abs(a[:, None, 4] - b[None, :, 4])
    allowed = (a[:, None, 5] == b[None, :, 5]) & (xy <= MAX_XY) & (scores <= MAX_SCORE)
    rows, cols = linear_sum_assignment(np.where(allowed, xy + scores, 1e9 + xy))
    passed = allowed[rows, cols]
    application = (a[rows, 4] >= FLOOR) | (b[cols, 4] >= FLOOR)
    unmatched = [{'recordedRowIndex': int(i), 'macRowIndex': int(j),
                  'recordedRow': a[i].tolist(), 'macRow': b[j].tolist(),
                  'sameClass': bool(a[i, 5] == b[j, 5]),
                  'coordinateDifferencePx': float(xy[i, j]),
                  'confidenceDifference': float(scores[i, j]),
                  'bothBelowApplicationFloor': bool(a[i, 4] < FLOOR and b[j, 4] < FLOOR)}
                 for i, j in zip(rows[~passed], cols[~passed])]
    return {'passed': bool(passed.all()), 'matchedRows': int(passed.sum()),
            'unmatchedRows': unmatched, 'applicationRows': int(application.sum()),
            'unmatchedApplicationRows': int((~passed & application).sum()),
            'applicationThresholdFlips': int(((a[rows, 4] >= FLOOR) != (b[cols, 4] >= FLOOR)).sum()),
            'matchedCoordinateDifferencePx': stats(xy[rows[passed], cols[passed]]),
            'matchedScoreDifference': stats(scores[rows[passed], cols[passed]]),
            'applicationCoordinateDifferencePx': stats(xy[rows[passed & application], cols[passed & application]]),
            'applicationScoreDifference': stats(scores[rows[passed & application], cols[passed & application]])}


def decode_recorded(raw, transform):
    """Independently verify stored >=.70 list against its own raw tensor."""
    detections = []
    for row in np.asarray(raw, dtype=np.float32).reshape(300, 6):
        if row[4] < FLOOR:
            continue
        b = np.asarray([(row[0] - np.float32(transform['left'])) / np.float32(transform['resizedWidth']),
                        (row[1] - np.float32(transform['top'])) / np.float32(transform['resizedHeight']),
                        (row[2] - np.float32(transform['left'])) / np.float32(transform['resizedWidth']),
                        (row[3] - np.float32(transform['top'])) / np.float32(transform['resizedHeight'])], dtype=np.float32)
        b = np.clip(b, 0, 1)
        if b[2] <= b[0] or b[3] <= b[1]:
            continue
        detections.append({'classId': int(row[5]), 'confidence': float(row[4]),
                           'box': dict(zip(('left', 'top', 'right', 'bottom'), map(float, b)))})
    return detections


def float32_recording_equal(recorded, decoded):
    if len(recorded) != len(decoded):
        return False
    for a, b in zip(recorded, decoded):
        if a['classId'] != b['classId']:
            return False
        if np.float32(a['confidence']) != np.float32(b['confidence']):
            return False
        if any(np.float32(a['box'][k]) != np.float32(b['box'][k]) for k in b['box']):
            return False
    return True


def category_threshold_rows(detections, categories):
    return [d for d in detections if categories[d['classId']]['alertable']
            and np.float32(d['confidence']) >= np.float32(categories[d['classId']]['confidenceThreshold'])]


def fonts(size):
    for path in ['/System/Library/Fonts/Supplemental/Arial.ttf', '/System/Library/Fonts/Helvetica.ttc']:
        if Path(path).exists():
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def contacts(session, frame_results, output):
    # Original PNGs remain untouched; these are review derivatives with phone boxes.
    colors = ['#33c96c', '#f07831', '#d565ea', '#41b7eb', '#e6d82e', '#fb809b']
    textfont, labelfont, headingfont = fonts(16), fonts(12), fonts(20)
    paths = []
    for start in range(0, len(session.frames), 12):
        sheet = Image.new('RGB', (1080, 1572), '#f6f7f9')
        draw = ImageDraw.Draw(sheet)
        draw.text((12, 10), f'EchoTest 0548b68a · images {start + 1}–{min(start + 12, len(session.frames))}', font=headingfont, fill='#102038')
        draw.text((12, 39), 'Boîtes téléphone ≥ 0,70 · vert piéton / orange véhicule / violet deux-roues / bleu poteau · pas de vérité terrain', font=labelfont, fill='#384253')
        for position, frame in enumerate(session.frames[start:start + 12]):
            result = frame_results[start + position]
            x, y = 10 + (position % 4) * 270, 68 + (position // 4) * 500
            image = Image.open(session.path / frame['imagePath']).convert('RGB')
            image.thumbnail((250, 446), Image.Resampling.LANCZOS)
            sheet.paste(image, (x, y + 38))
            draw.text((x, y), f'#{start + position + 1}  frame {frame["frameId"]}', font=textfont, fill='#102038')
            draw.text((x, y + 18), f't={result["timeFromCaptureStartSeconds"]:.3f}s · âge {result["decisionAgeMs"]} ms', font=labelfont, fill='#384253')
            for d in result['phoneDetections']:
                b = d['box']; color = colors[d['classId']]
                box = [x + b['left'] * image.width, y + 38 + b['top'] * image.height,
                       x + b['right'] * image.width, y + 38 + b['bottom'] * image.height]
                draw.rectangle(box, outline=color, width=2)
                label = f'{CLASSES[d["classId"]]} {d["confidence"]:.2f}'
                lx = min(max(x, box[0]), x + image.width - draw.textlength(label, font=labelfont))
                ly = max(y + 38, box[1] - 14)
                draw.rectangle((lx, ly, lx + draw.textlength(label, font=labelfont), ly + 14), fill='#10141b')
                draw.text((lx, ly), label, font=labelfont, fill=color)
            parity_label = 'en cours' if result.get('rawParity') is None else ('PASS' if result['rawParity']['passed'] else 'FAIL')
            draw.text((x, y + 486), f'{len(result["phoneDetections"])} détection(s) · brut {parity_label}', font=labelfont, fill='#384253')
        path = output / f'ml_contact_{start // 12 + 1:02d}.jpg'
        sheet.save(path, quality=93, subsampling=0)
        paths.append({'path': path.name, 'firstIndexOneBased': start + 1,
                      'lastIndexOneBased': min(start + 12, len(session.frames)), 'sha256': sha256(path)})
    return paths


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path, default=Path('/Users/sam/Documents/EchoTest Captures/EchoTest-0548b68a-b9e3-445f-a072-0182e9ce98d6.zip'))
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parent)
    args = parser.parse_args(); args.output.mkdir(parents=True, exist_ok=True)
    log = (args.output / 'ml_audit.log').open('w')
    def note(message):
        print(message, flush=True); print(message, file=log, flush=True)
    started = datetime.now(timezone.utc).isoformat(); began = time.perf_counter()
    original_sha = sha256(args.archive)
    with zipfile.ZipFile(args.archive) as archive:
        crc_error = archive.testzip()
        uncompressed_bytes = sum(i.file_size for i in archive.infolist())
    if crc_error:
        raise ValueError(f'ZIP CRC failure {crc_error}')
    with tempfile.TemporaryDirectory(prefix='echotest-ml-0548b68a-') as tmp:
        session = SessionStore(Path(tmp) / 'sessions').import_zip(args.archive)
        if session.model_sha != MODEL_SHA or sha256(MODEL) != MODEL_SHA:
            raise ValueError('Model identity mismatch; no substitution allowed')
        note(f'Imported {len(session.frames)} PNG, {len(session.inferences)} inference events, {len(session.packets)} packets. Warnings: {session.warnings}')
        preview = []
        for frame in session.frames:
            decision = next(e for e in session.event_frames[frame_key(frame)] if e['type'] == 'decision')
            preview.append({'timeFromCaptureStartSeconds': (frame['receivedAtMs'] - session.origin) / 1000,
                            'decisionAgeMs': decision['evaluatedAtMs'] - frame['receivedAtMs'],
                            'phoneDetections': session.inferences[frame_key(frame)]['detections'], 'rawParity': None})
        contacts(session, preview, args.output)
        note('Contact sheets ready for visual review; initial raw parity labels explicitly pending.')
        detector = MacDetector(); results = []
        categories = {c['classId']: c for c in session.manifest['metadata']['policyConfig']['categories']}
        for index, frame in enumerate(session.frames):
            original = session.inferences[frame_key(frame)]
            decision = next(e for e in session.event_frames[frame_key(frame)] if e['type'] == 'decision')
            recalculated = detector.detect(session.path / frame['imagePath'])
            parity = match_raw(original['rawModelOutput'], recalculated['rawOutput'])
            decoded = decode_recorded(original['rawModelOutput'], recalculated['transform'])
            results.append({'indexOneBased': index + 1, 'frameId': frame['frameId'], 'videoSessionId': frame['videoSessionId'],
                            'imagePath': frame['imagePath'], 'pngSha256': sha256(session.path / frame['imagePath']),
                            'observedAtMs': frame['receivedAtMs'], 'timeFromCaptureStartSeconds': (frame['receivedAtMs'] - session.origin) / 1000,
                            'inferenceEmittedAtMs': original['atMs'], 'inferenceAgeMs': original['resultAgeMs'],
                            'decisionEvaluatedAtMs': decision['evaluatedAtMs'], 'decisionAgeMs': decision['evaluatedAtMs'] - frame['receivedAtMs'],
                            'inferenceAccepted': original['accepted'], 'decisionStatus': decision['frameStatus'],
                            'copyMs': frame['copyMs'], 'conversionMs': frame['conversionMs'],
                            'phonePreprocessingMs': original['preprocessMs'], 'phoneInferenceMs': original['inferenceMs'],
                            'phoneDetections': original['detections'], 'macDetections': recalculated['detections'],
                            'phoneStoredDetectionDecodeExactlyFloat32Equal': float32_recording_equal(original['detections'], decoded),
                            'phoneCategoryThresholdRows': category_threshold_rows(original['detections'], categories),
                            'macCategoryThresholdRows': category_threshold_rows(recalculated['detections'], categories),
                            'categoryThresholdScope': 'Per-class confidence eligibility only, not tracked/confirmed/selected or audio eligibility.',
                            'rawParity': parity, 'macRawOutput': recalculated['rawOutput'],
                            'inputTensorSha256': recalculated['inputTensorSha256'], 'transform': recalculated['transform'],
                            'macTimingsMs': recalculated['macTimingsMs']})
            if (index + 1) % 40 == 0 or index + 1 == len(session.frames):
                note(f'Inference {index + 1}/{len(session.frames)}: strict raw failures {sum(not f["rawParity"]["passed"] for f in results)}')
        frame_path = args.output / 'ml_frames.jsonl'
        frame_path.write_text(''.join(json.dumps(x, ensure_ascii=False) + '\n' for x in results))
        sheets = contacts(session, results, args.output)
        note(f'Wrote {len(sheets)} contact sheets, 12 frames/sheet maximum.')
        ffmpeg = find_ffmpeg()
        if ffmpeg:
            decode = subprocess.run([ffmpeg, '-hide_banner', '-loglevel', 'warning', '-i', str(session.path / 'video.h264'), '-an', '-f', 'null', '-', '-progress', 'pipe:1'], capture_output=True, text=True, timeout=120)
            progress = [dict(x.split('=', 1) for x in block.splitlines() if '=' in x) for block in decode.stdout.split('progress=') if '=' in block]
            video = {'returnCode': decode.returncode, 'decodedFrames': int(progress[-1]['frame']) if progress else None,
                     'stderr': decode.stderr, 'ffmpegPath': ffmpeg,
                     'scope': 'All native H264 frames decoded; decode frame clock is not packet reception/PNG clock.'}
        else:
            video = {'available': False, 'reason': 'FFmpeg unavailable'}
        failures = [f for f in results if not f['rawParity']['passed']]
        application_rows = sum(f['rawParity']['applicationRows'] for f in results)
        events = session.events; inf = [e for e in events if e['type'] == 'inference']; dec = [e for e in events if e['type'] == 'decision']
        keys = [frame_key(f) for f in session.frames]
        inferences_keys = [frame_key(e) for e in inf]; decision_keys = [frame_key(e) for e in dec]
        seconds = session.manifest['durationMs'] / 1000
        report = {'schemaVersion': 1, 'startedAtUtc': started, 'finishedAtUtc': datetime.now(timezone.utc).isoformat(),
                  'wallSeconds': time.perf_counter() - began,
                  'scope': 'All captured PNG re-inferred using unchanged Mac CPU ONNX. No accuracy/recall claim without annotations, no same-hardware performance claim, no physical-audio validation. Contact sheets show phone predictions, not ground truth. Exact temporal policy and voice transactions audited separately by another agent.',
                  'sourceArchive': str(args.archive), 'zipSha256Before': original_sha, 'zipSha256After': sha256(args.archive),
                  'zipBytes': args.archive.stat().st_size, 'zipCrcErrorEntry': crc_error, 'zipUncompressedBytes': uncompressed_bytes,
                  'manifest': session.manifest, 'importWarnings': session.warnings,
                  'provenance': {'modelSha256': sha256(MODEL), 'modelBytes': MODEL.stat().st_size, 'scriptSha256': sha256(Path(__file__)),
                                 'replaySourceSha256': sha256(ROOT / 'echotest-desktop/replay.py'), 'python': sys.version,
                                 'numpy': np.__version__, 'onnxruntime': onnxruntime.__version__, 'pillow': PIL.__version__, 'scipy': scipy.__version__,
                                 'platform': platform.platform(), 'provider': 'CPUExecutionProvider, Mac, 2 intra-op / 1 inter-op threads'},
                  'counts': {'png': len(results), 'inference': len(inf), 'decision': len(dec), 'packets': len(session.packets),
                             'events': len(events), 'eventTypes': dict(Counter(e['type'] for e in events)),
                             'phoneDetectionRows': sum(len(f['phoneDetections']) for f in results),
                             'phoneDetectionClassCounts': dict(Counter(CLASSES[d['classId']] for f in results for d in f['phoneDetections'])),
                             'phoneCategoryThresholdRows': sum(len(f['phoneCategoryThresholdRows']) for f in results)},
                  'integrity': {'uniqueFrameKeys': len(keys) == len(set(keys)), 'uniqueInferenceKeys': len(inferences_keys) == len(set(inferences_keys)),
                                'uniqueDecisionKeys': len(decision_keys) == len(set(decision_keys)), 'allPngInferenceDecisionKeysEqual': set(keys) == set(inferences_keys) == set(decision_keys),
                                'allReceivedTimesIncreasing': all(a['receivedAtMs'] < b['receivedAtMs'] for a, b in zip(session.frames, session.frames[1:])),
                                'allBitmapClockOrder': all(f['receivedAtMs'] <= f['deliveredAtMs'] <= f['recordedAtMs'] for f in session.frames),
                                'allDecisionClockOrder': all(f['observedAtMs'] <= f['inferenceEmittedAtMs'] <= f['decisionEvaluatedAtMs'] for f in results),
                                'packetBytes': sum(p['length'] for p in session.packets), 'h264Bytes': (session.path / 'video.h264').stat().st_size,
                                'rawToStoredDetectionListsExactFloat32': all(f['phoneStoredDetectionDecodeExactlyFloat32Equal'] for f in results)},
                  'parity': {'coordinateTolerancePx': MAX_XY, 'scoreTolerance': MAX_SCORE,
                             'applicationFloor': float(FLOOR), 'classesMustMatch': True, 'matching': 'one-to-one Hungarian set matching; same frozen bounds as previous audits',
                             'fullRawPassedFrames': len(results) - len(failures), 'fullRawFailedFrames': len(failures), 'totalRows': len(results) * 300,
                             'unmatchedRows': sum(len(f['rawParity']['unmatchedRows']) for f in results), 'applicationRows': application_rows,
                             'unmatchedApplicationRows': sum(f['rawParity']['unmatchedApplicationRows'] for f in results),
                             'applicationThresholdFlips': sum(f['rawParity']['applicationThresholdFlips'] for f in results),
                             'allUnmatchedRowsBelowFloor': all(r['bothBelowApplicationFloor'] for f in failures for r in f['rawParity']['unmatchedRows']),
                             'maxUnmatchedConfidence': max((max(r['recordedRow'][4], r['macRow'][4]) for f in failures for r in f['rawParity']['unmatchedRows']), default=None),
                             'failureLocations': [{'frameId': f['frameId'], 'indexOneBased': f['indexOneBased'], 'timeSeconds': f['timeFromCaptureStartSeconds'], 'unmatchedRows': len(f['rawParity']['unmatchedRows'])} for f in failures],
                             'fullRawVerdict': 'PASS' if not failures else 'FAIL'},
                  'freshness': {'captureSeconds': seconds, 'inferencesPerCaptureSecond': len(inf) / seconds,
                                'acceptedInferences': sum(e['accepted'] for e in inf), 'decisionStatuses': dict(Counter(e['frameStatus'] for e in dec)),
                                'inferenceAgeMs': stats([f['inferenceAgeMs'] for f in results]), 'decisionAgeMs': stats([f['decisionAgeMs'] for f in results]),
                                'copyMs': stats([f['copyMs'] for f in results]), 'conversionMs': stats([f['conversionMs'] for f in results]),
                                'phonePreprocessingMs': stats([f['phonePreprocessingMs'] for f in results]), 'phoneInferenceMs': stats([f['phoneInferenceMs'] for f in results]),
                                'receptionGapMs': stats(np.diff([f['observedAtMs'] for f in results])),
                                'staleFrameLocations': [{'frameId': f['frameId'], 'timeSeconds': f['timeFromCaptureStartSeconds'], 'inferenceAgeMs': f['inferenceAgeMs'], 'decisionAgeMs': f['decisionAgeMs']} for f in results if f['inferenceAgeMs'] > 500 or f['decisionAgeMs'] > 500],
                                'scope': 'Absolute capture observations, including startup and recording; no matched recording-OFF baseline or thermal endurance claim.'},
                  'macTimings': {k: stats([f['macTimingsMs'][k] for f in results]) for k in ('imageDecode', 'preprocessing', 'inference')},
                  'video': video, 'framesJsonl': {'path': frame_path.name, 'sha256': sha256(frame_path)}, 'contactSheets': sheets}
        summary_path = args.output / 'ml_summary.json'
        summary_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        p = report['parity']; fresh = report['freshness']; c = report['counts']
        lines = ['# Audit ML — capture 0548b68a', '',
                 f"Capture de {seconds:.3f} s : **{len(results)} PNG, {len(inf)} inférences, {len(dec)} décisions**, {len(session.packets)} paquets. ZIP CRC valide ; archive originale inchangée : `{original_sha}`.", '',
                 f"Même ONNX FP32 416 (`{MODEL_SHA}`), letterbox et inverse raster existants. Réinférence des {len(results)} PNG exactes sur **CPU Mac**, sans rotation supplémentaire, export ou seuil modifié. Ces temps ne mesurent pas le HTC.", '',
                 f"**Parité brute {p['fullRawVerdict']} : {p['fullRawPassedFrames']}/{len(results)} images**, {p['unmatchedRows']} lignes non appariées sur {p['totalRows']}. Tolérances conservées : même classe, 1 pixel d'entrée et 0,001 de confiance. Confiance maximale des lignes non appariées : {p['maxUnmatchedConfidence']}. Toutes sous 0,70 : {p['allUnmatchedRowsBelowFloor']}.", '',
                 f"À partir de 0,70 : {p['applicationRows']} lignes comparées, **{p['unmatchedApplicationRows']} non appariée(s)**, {p['applicationThresholdFlips']} bascule(s) de seuil. Les listes enregistrées correspondent exactement (float32) au décodage de leurs sorties brutes : {report['integrity']['rawToStoredDetectionListsExactFloat32']}. Une détection à 0,70 ne signifie pas une annonce : les seuils par classe puis confirmation, sélection et disponibilité audio s'appliquent ensuite.", '',
                 f"Téléphone : {fresh['acceptedInferences']}/{len(inf)} inférences acceptées ; états décision {fresh['decisionStatuses']}. Âge décision médian {fresh['decisionAgeMs']['median']:.1f} ms, p95 {fresh['decisionAgeMs']['p95']:.2f} ms, max {fresh['decisionAgeMs']['max']:.0f} ms. Débit sur la capture entière : {fresh['inferencesPerCaptureSecond']:.3f} Hz. Copie bitmap p95 {fresh['copyMs']['p95']:.3f} ms. Le surcoût isolé de l'enregistrement exige un essai OFF/ON comparable.", '',
                 f"H264 décodé entièrement : code {video.get('returnCode')}, {video.get('decodedFrames')} images. L'horloge nominale du décodeur n'est pas l'horloge de réception. Les PNG et leurs timestamps sont la référence pour la comparaison.", '',
                 '## Écarts bruts localisés', '', '| Image | frameId | Temps depuis début | Lignes non appariées |', '|---:|---:|---:|---:|']
        lines += [f"| {f['indexOneBased']} | {f['frameId']} | {f['timeFromCaptureStartSeconds']:.3f} s | {len(f['rawParity']['unmatchedRows'])} |" for f in failures]
        if not failures:
            lines.append('| — | — | — | 0 |')
        lines += ['', '## Inspection visuelle et limites', '',
                  f"{len(sheets)} planches `ml_contact_XX.jpg` couvrent les {len(results)} images à environ 250 px de largeur chacune. Les boîtes sont les prédictions téléphone ≥0,70, et ne constituent pas des annotations humaines. Classes détectées : {c['phoneDetectionClassCounts']}.", '',
                  'Les compteurs de voix et l’orientation sont conservés dans le manifeste ; l’audit des transactions et du moteur temporel est séparé. Aucun microphone enregistré : cet audit ne prouve pas l’écoute physique. Aucun rappel, taux de faux positifs ou sécurité de navigation ne peut être établi sans annotation de la scène.', '',
                  'Reproduction depuis `echonav-htc` :', '',
                  '```sh', 'ml/.venv/bin/python validation/user-scene-0548b68a/ml_audit.py', '```', '',
                  'Artefacts : `ml_summary.json`, `ml_frames.jsonl` (164 résultats et sorties brutes Mac), `ml_audit.log`, `ml_contact_01.jpg` à `ml_contact_14.jpg`. Les originaux et les sources du serveur ne sont pas modifiés.']
        (args.output / 'ml_summary.md').write_text('\n'.join(lines) + '\n')
        note(json.dumps({'parity': report['parity'], 'freshness': report['freshness'], 'video': video}, ensure_ascii=False))
        note(f'Report: {summary_path}; original ZIP unchanged: {report["zipSha256Before"] == report["zipSha256After"]}')
    log.close()


if __name__ == '__main__':
    main()
