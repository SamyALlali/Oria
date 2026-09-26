#!/usr/bin/env python3
"""Identify this delivery from preserved build/install evidence; does not run device tests."""
import argparse, datetime, hashlib, json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NEW = ROOT / 'validation/new-device-CN46V3M00284'
MANIFEST = ROOT / 'artifacts/delivery_manifest.json'

def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda: f.read(1024*1024), b''): h.update(block)
    return h.hexdigest()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--stage', default='paced', help='Preserved build/install evidence prefix')
    stage = parser.parse_args().stage
    import re
    assert re.fullmatch(r'[a-z0-9-]+', stage)
    delivery = json.loads(MANIFEST.read_text())
    build = json.loads((NEW/f'{stage}-build.json').read_text())
    install = json.loads((NEW/f'production-update-{stage}.json').read_text())
    apk = ROOT/'artifacts/echonav-silmo-debug.apk'
    assert sha(apk) == build['apk_sha256'] == install['apk_sha256']
    assert install['returncode'] == 0 and build['unit_tests']['failures'] == build['unit_tests']['errors'] == 0
    assert "name='com.htc.vive.eagle.hackathon.starter'" in (NEW/f'{stage}-apk-badging.txt').read_text()
    assert delivery['signer_sha256'] in (NEW/f'{stage}-apk-certificate.txt').read_text()
    delivery.update(date=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                    apk_sha256=sha(apk), apk_size_bytes=apk.stat().st_size,
                    unit_tests=build['unit_tests'], build='PASSED')
    delivery['unit_tests']['skipped'] = sum(int(s['skipped']) for s in build['unit_tests']['suites'])
    device = delivery['device_validation']
    device.update(installation='PASSED_COMPATIBLE_UPDATE_AFTER_USER_AUTHORIZED_INITIAL_REPLACEMENT',
                  installation_evidence=f'validation/new-device-CN46V3M00284/production-update-{stage}.json',
                  current_apk_sha256=sha(apk),
                  actual_audio_audibility='USER_CONFIRMED_AUTOMATIC_STEREO_DURING_REAL_VIDEO',
                  full_physical_chain='DEMONSTRATED_WITH_PEOPLE_AND_CORRECT_STEREO_SIDES',
                  voice_backend='LOCAL_FRENCH_PCM_STEREO_TO_VERIFIED_VIVE_BLUETOOTH_ROUTE',
                  htc_sdk_simultaneous_speech='FAILED_ERROR_RESOURCE_CONFLICT',
                  human_evidence='validation/new-device-CN46V3M00284/human-confirmations.json')
    device['sample_interval_ms'] = build.get('sample_interval_ms', 250)
    device['stereo_listening_confirmation_build'] = '412eec7f9106b9faadda77c62dd3f41102994d66003fa7d29e8dfdfc64781199'
    device['subsequent_cadence_change_scope'] = 'Only manager sample interval and start trace changed; audio PCM, routing and stereo unchanged.' if stage == 'paced' else None
    if stage.startswith('echotest'):
        device['subsequent_cadence_change_scope'] = 'EchoTest adds optional capture and replay tooling; audio implementation unchanged. Previous endurance was measured with recording disabled on build40175f.'
    if stage in {'stereo7030', 'oria'}:
        device['actual_audio_audibility'] = 'NEW_70_30_MIX_NOT_YET_HUMAN_CONFIRMED; PREVIOUS_100_0_CONFIRMED_ON_412EEC'
        device['full_physical_chain'] = 'HISTORICALLY_DEMONSTRATED; CURRENT_MIX_PENDING_LISTENING'
        device['subsequent_cadence_change_scope'] = 'Sample interval remains333ms; only panning amplitude changed to70/30 and30/70; centre1/1, model and deterministic policy unchanged.'
        device['current_build_audio_scope'] = '51 JVM tests pass, compatible install. New mix listening not confirmed; old stereo confirmation is100/0.'
    if stage == 'oria':
        delivery['product_name'] = 'Oria'
        delivery['laboratory_name'] = 'Oria Lab'
        device['subsequent_cadence_change_scope'] = 'User-visible branding and manual test phrase renamed; model, deterministic policy,333ms sampling,70/30 PCM gains and capture schema unchanged.'
        device['branding_validation'] = 'validation/new-device-CN46V3M00284/oria-branding.json'
    if 'live_metrics_first_session' in device:
        device['live_metrics_first_session']['apk_sha256'] = 'c93320f055eb2cd4165516a372fa1ea65706a8423dcfd8fc8bfe986c894f7f6c'
        device['live_metrics_first_session']['scope'] = 'Historical first session on this phone, before local Bluetooth/stereo; not current APK metrics.'
    for name in ('live-stereo-summary.json', 'endurance-stereo-summary.json',
                 'live-paced-summary.json', 'endurance-paced-summary.json', 'lifecycle-results.json'):
        if (NEW/name).exists(): device[name.removesuffix('.json').replace('-', '_')] = 'validation/new-device-CN46V3M00284/'+name
    # Explicit verdict is maintained by the orchestrator in the recipe and this device field.
    # Never infer acoustic proof or a 600 s run just because a sampling file exists.
    sources = {ROOT/name for name in delivery['sources_sha256']}
    sources.update(p for p in (ROOT/'android-project/app/src').rglob('*') if p.is_file())
    for folder in ['ml', 'fixtures']:
        sources.update(p for p in (ROOT/folder).glob('*') if p.is_file())
    for folder in ['android-project/gradle', 'android-project/repository', 'android-project/app/libs']:
        sources.update(p for p in (ROOT/folder).rglob('*') if p.is_file())
    sources.update((ROOT/'validation').glob('*.py'))
    for folder in ['echotest-policy', 'echotest-desktop', 'echotest-transfer']:
        sources.update(p for p in (ROOT/folder).rglob('*')
                       if p.is_file() and not any(part in {'vendor', 'build', '__pycache__', '.pytest_cache'} for part in p.relative_to(ROOT/folder).parts))
    sources.update((ROOT/'android-project').glob('*.kts'))
    sources.update([ROOT/'android-project/gradle.properties', ROOT/'android-project/gradlew'])
    delivery['sources_sha256'] = {str(p.relative_to(ROOT)):sha(p) for p in sorted(sources) if p.is_file()}
    extensions = {'.json', '.jsonl', '.md', '.log', '.xml', '.txt', '.f32', '.png', '.patch'}
    reports = {p for p in NEW.rglob('*') if p.is_file() and p.suffix in extensions}
    reports.update([ROOT/'README.md', ROOT/'DEMONSTRATION.md', ROOT/'IMPLEMENTATION_COORDINATION.md'])
    reports.update((ROOT/'validation').glob('*.md'))
    for audit_dir in (ROOT/'validation').glob('user-scene-*'):
        reports.update(p for p in audit_dir.rglob('*') if p.is_file() and
                       p.suffix in extensions | {'.jpg', '.py', '.kt', '.csv', '.html'})
    # The audit output refers back to this manifest; avoid an unnecessary circular hash record.
    reports = {p for p in reports if p.name != 'delivery-integrity-final.json'}
    delivery['reports_sha256'] = {str(p.relative_to(ROOT)):sha(p) for p in sorted(reports)}
    delivery['reference_documents_sha256'] = {name:sha(ROOT.parent/name) for name in [
        'CAHIER_DES_CHARGES_ECHONAV_SILMO.md', 'DECISIONS_ARCHITECTURE_ECHONAV_SILMO.md',
        'PORTAGE_ECHONAV_SWIFT_ANDROID.md', 'ECHONAV_MODEL_METADATA.json']}
    MANIFEST.write_text(json.dumps(delivery, indent=2, ensure_ascii=False)+'\n')
    print(json.dumps({'apk_sha256':delivery['apk_sha256'], 'unit_tests':delivery['unit_tests']['total'],
                      'sources':len(delivery['sources_sha256']), 'reports':len(delivery['reports_sha256'])}))

if __name__ == '__main__': main()
