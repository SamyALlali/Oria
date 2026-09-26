#!/usr/bin/env python3
"""Copy one finalized OriaLab capture over USB, without deleting data on the phone."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tarfile
import zipfile

PACKAGE = 'com.htc.vive.eagle.hackathon.starter'
UUID = re.compile(r'[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}')
LIMIT = 2 * 1024 ** 3

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial')
    parser.add_argument('--session', help='Capture UUID; defaults to latest finalized capture')
    parser.add_argument('--output', type=Path, default=Path.home()/'Documents/Oria Lab Captures')
    parser.add_argument('--reveal', action='store_true')
    parser.add_argument('--open', action='store_true', help='Open this capture directly in the Mac viewer')
    args = parser.parse_args()
    adb = shutil.which('adb') or str(Path.home()/'Library/Android/sdk/platform-tools/adb')
    devices = subprocess.check_output([adb, 'devices'], text=True).splitlines()[1:]
    ready = [line.split()[0] for line in devices if len(line.split()) >= 2 and line.split()[1] == 'device']
    serial = args.serial
    if serial is None:
        if len(ready) != 1:
            raise ValueError('Brancher un seul téléphone autorisé, ou préciser --serial. Aucun appareil modifié.')
        serial = ready[0]
    if serial not in ready:
        raise ValueError('Téléphone absent ou débogage USB non autorisé')
    base = [adb, '-s', serial]
    def read(*command):
        return subprocess.check_output(base + ['exec-out', 'run-as', PACKAGE, *command])
    ids = [name for name in read('ls', 'files/oria-lab').decode().split() if UUID.fullmatch(name)]
    captures = []
    for identifier in ids:
        try:
            manifest = json.loads(read('cat', f'files/oria-lab/{identifier}/manifest.json'))
            if manifest.get('sessionId') == identifier and manifest.get('status') in ('complete', 'incomplete'):
                captures.append(manifest)
        except (subprocess.CalledProcessError, json.JSONDecodeError):
            continue
    if args.session:
        if not UUID.fullmatch(args.session): raise ValueError('Identifiant de capture invalide')
        captures = [capture for capture in captures if capture['sessionId'] == args.session]
    if not captures:
        raise ValueError('Aucune capture finalisée. Dans Oria Lab, enregistrer puis arrêter et attendre la sauvegarde.')
    capture = max(captures, key=lambda item: item.get('startedAtEpochMs', 0))
    identifier = capture['sessionId']
    args.output.mkdir(parents=True, exist_ok=True)
    destination = args.output/f'OriaLab-{identifier}.zip'
    if destination.exists():
        with zipfile.ZipFile(destination) as existing:
            if existing.testzip() is not None or json.loads(existing.read('manifest.json')).get('sessionId') != identifier:
                raise ValueError('Une archive différente ou corrompue existe déjà ; choisir un autre --output.')
    else:
        temporary = destination.with_suffix('.zip.part')
        if temporary.exists(): raise ValueError('Transfert .part déjà présent ; choisir un autre --output pour le préserver.')
        process = subprocess.Popen(base + ['exec-out', 'run-as', PACKAGE, 'tar', '-cf', '-', '-C', 'files/oria-lab', identifier],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        total, names = 0, set()
        try:
            with tarfile.open(fileobj=process.stdout, mode='r|') as source, zipfile.ZipFile(temporary, 'x', zipfile.ZIP_STORED) as output:
                for member in source:
                    path = PurePosixPath(member.name)
                    if path.is_absolute() or '..' in path.parts or '\\' in member.name or not path.parts or path.parts[0] != identifier:
                        raise ValueError('Chemin inattendu dans la capture')
                    if member.isdir(): continue
                    if not member.isfile() or len(path.parts) < 2:
                        raise ValueError('La capture contient une entrée non régulière')
                    name = PurePosixPath(*path.parts[1:]).as_posix()
                    if name in names or len(names) >= 10000: raise ValueError('Index de capture invalide')
                    names.add(name); total += member.size
                    if member.size < 0 or total > LIMIT: raise ValueError('Capture supérieure à 2 Gio')
                    with source.extractfile(member) as file, output.open(name, 'w', force_zip64=True) as target:
                        shutil.copyfileobj(file, target, length=1024*1024)
            process.stdout.close()
            stderr = process.stderr.read().decode(errors='replace')
            if process.wait(timeout=10): raise RuntimeError('Transfert USB interrompu : ' + stderr)
            with zipfile.ZipFile(temporary) as archive:
                if archive.testzip() is not None: raise ValueError('Archive transférée corrompue')
                received = json.loads(archive.read('manifest.json'))
                if received != capture: raise ValueError('Capture modifiée pendant le transfert ; réessayer après finalisation')
            temporary.rename(destination)
        except Exception:
            if process.poll() is None: process.kill()
            process.wait()
            temporary.unlink(missing_ok=True)
            raise
        finally:
            process.stdout.close(); process.stderr.close()
    digest = hashlib.sha256()
    with destination.open('rb') as stream:
        for block in iter(lambda: stream.read(1024*1024), b''): digest.update(block)
    proof = dict(at=datetime.datetime.now(datetime.timezone.utc).isoformat(), device=serial,
                 sessionId=identifier, path=str(destination.resolve()), sha256=digest.hexdigest(),
                 bytes=destination.stat().st_size, status=capture['status'], counts=capture.get('counts', {}),
                 phone_data_preserved=True)
    destination.with_suffix('.transfer.json').write_text(json.dumps(proof, indent=2, ensure_ascii=False)+'\n')
    print(json.dumps(proof, indent=2, ensure_ascii=False))
    if args.reveal: subprocess.run(['open', '-R', str(destination)], check=True)
    if args.open:
        root = Path(__file__).resolve().parent.parent
        subprocess.run([str(root/'ml/.venv/bin/python'), str(root/'oria-lab-desktop/launch.py'),
                        '--import', str(destination)], check=True)

if __name__ == '__main__':
    try: main()
    except (ValueError, RuntimeError, subprocess.SubprocessError, OSError, tarfile.TarError, zipfile.BadZipFile) as error:
        raise SystemExit(str(error))
