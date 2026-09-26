#!/usr/bin/env python3
"""Copy one finalized OriaLab capture over USB, without deleting data on the phone."""
import argparse
import contextlib
import os
import datetime
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tempfile
import tarfile
import zipfile

PACKAGE = 'com.htc.vive.eagle.hackathon.starter'
UUID = re.compile(r'[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}')
LIMIT = 128 * 1024 ** 3
MAX_FILES = 100000
MAX_TAR_ENTRIES = 2 * MAX_FILES + 32
RESERVED_FREE_BYTES = 512 * 1024 ** 2
MAX_MANIFEST_BYTES = 1024 ** 2
CHUNK_BYTES = 1024 ** 2


def require_disk_space(path, required):
    if required < 0 or shutil.disk_usage(path).free - required < RESERVED_FREE_BYTES:
        raise ValueError('Espace disque insuffisant : conserver 512 Mio libres sur le Mac')


class ReservedDiskWriter:
    """Guard every ZIP write, including central-directory metadata, without buffering the capture."""
    def __init__(self, stream, directory):
        self.stream, self.directory = stream, directory
        self.available, self.since_probe = 0, CHUNK_BYTES

    def write(self, data):
        length = len(data)
        if self.since_probe + length >= CHUNK_BYTES or self.available - length < RESERVED_FREE_BYTES:
            self.available = shutil.disk_usage(self.directory).free
            self.since_probe = 0
        if self.available - length < RESERVED_FREE_BYTES:
            raise ValueError('Espace disque insuffisant : conserver 512 Mio libres sur le Mac')
        written = self.stream.write(data)
        self.available -= written
        self.since_probe += written
        return written

    def __getattr__(self, name):
        return getattr(self.stream, name)


def validate_member(member, identifier, names, total):
    path = PurePosixPath(member.name)
    if path.is_absolute() or '..' in path.parts or '\\' in member.name or not path.parts or path.parts[0] != identifier:
        raise ValueError('Chemin inattendu dans la capture')
    if member.isdir():
        return None, total
    if not member.isfile() or len(path.parts) < 2:
        raise ValueError('La capture contient une entrée non régulière')
    name = PurePosixPath(*path.parts[1:]).as_posix()
    if name in names:
        raise ValueError('Entrée dupliquée dans la capture')
    if len(names) >= MAX_FILES:
        raise ValueError('Capture supérieure à la borne technique de 100000 fichiers')
    if member.size < 0 or total + member.size > LIMIT:
        raise ValueError('Capture supérieure à la borne technique de 128 Gio')
    names.add(name)
    return name, total + member.size


def copy_capture_tar(source_stream, temporary, identifier, output_stream=None):
    """Copy one finalized capture; no ADB dependency and no unbounded read of media."""
    total, names = 0, set()
    with (contextlib.nullcontext(output_stream) if output_stream is not None else temporary.open('xb', buffering=0)) as raw:
        guarded = ReservedDiskWriter(raw, temporary.parent)
        with tarfile.open(fileobj=source_stream, mode='r|') as source, zipfile.ZipFile(guarded, 'w', zipfile.ZIP_STORED) as output:
            for header_count, member in enumerate(source, 1):
                # tarfile otherwise retains every TarInfo even in stream mode on older Python.
                source.members.clear()
                if header_count > MAX_TAR_ENTRIES:
                    raise ValueError('Trop d’en-têtes TAR dans la capture')
                name, total = validate_member(member, identifier, names, total)
                if name is None:
                    continue
                require_disk_space(temporary.parent, member.size)
                copied = 0
                with source.extractfile(member) as content, output.open(name, 'w', force_zip64=True) as target:
                    while chunk := content.read(CHUNK_BYTES):
                        copied += len(chunk)
                        target.write(chunk)
                if copied != member.size:
                    raise ValueError('Capture tronquée pendant le transfert')
    if temporary.stat().st_size > LIMIT:
        raise ValueError('Archive ZIP supérieure à la borne technique de 128 Gio')
    return {'files': len(names), 'payloadBytes': total}


def verify_archive(path, expected_manifest):
    with zipfile.ZipFile(path) as archive:
        if len(archive.infolist()) > MAX_FILES or path.stat().st_size > LIMIT or sum(i.file_size for i in archive.infolist()) > LIMIT:
            raise ValueError('Archive au-delà des bornes techniques : 128 Gio / 100000 fichiers')
        if archive.getinfo('manifest.json').file_size > MAX_MANIFEST_BYTES:
            raise ValueError('Manifeste de capture trop volumineux')
        if archive.testzip() is not None:
            raise ValueError('Archive transférée corrompue')
        received = json.loads(archive.read('manifest.json'))
        if received != expected_manifest:
            raise ValueError('Capture différente ou modifiée pendant le transfert ; préserver cet export et choisir un autre --output')


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
    reused = destination.exists()
    if reused:
        verify_archive(destination, capture)
    else:
        temporary = destination.with_suffix('.zip.part')
        if temporary.exists(): raise ValueError('Transfert .part déjà présent ; choisir un autre --output pour le préserver.')
        require_disk_space(args.output, 0)
        # Claim the partial atomically. A failed competing open must never remove its owner's file.
        owned = False
        process = None
        try:
            with temporary.open('xb', buffering=0) as raw:
                owned = True
                # File-backed stderr cannot fill a pipe and deadlock a long stdout transfer.
                with tempfile.TemporaryFile() as errors:
                    process = subprocess.Popen(base + ['exec-out', 'run-as', PACKAGE, 'tar', '-cf', '-', '-C', 'files/oria-lab', identifier],
                                               stdout=subprocess.PIPE, stderr=errors)
                    copy_capture_tar(process.stdout, temporary, identifier, output_stream=raw)
                    process.stdout.close()
                    if process.wait(timeout=10):
                        errors.seek(0)
                        raise RuntimeError('Transfert USB interrompu : ' + errors.read(64 * 1024).decode(errors='replace'))
            verify_archive(temporary, capture)
            # Same-directory hard-link publication is atomic and refuses an existing destination.
            # Unlike rename(), it cannot silently replace an archive created by a concurrent transfer.
            os.link(temporary, destination)
            temporary.unlink()
            owned = False
        except BaseException:
            if process is not None:
                if process.poll() is None: process.kill()
                process.wait()
            if owned: temporary.unlink(missing_ok=True)
            raise
        finally:
            if process is not None: process.stdout.close()
    digest = hashlib.sha256()
    with destination.open('rb') as stream:
        for block in iter(lambda: stream.read(1024*1024), b''): digest.update(block)
    proof = dict(at=datetime.datetime.now(datetime.timezone.utc).isoformat(), device=serial,
                 sessionId=identifier, path=str(destination.resolve()), sha256=digest.hexdigest(),
                 bytes=destination.stat().st_size, status=capture['status'], counts=capture.get('counts', {}),
                 phone_data_preserved=True, archive_reused=reused,
                 transfer_limits=dict(maxBytes=LIMIT, maxFiles=MAX_FILES, reservedFreeBytes=RESERVED_FREE_BYTES))
    destination.with_suffix('.transfer.json').write_text(json.dumps(proof, indent=2, ensure_ascii=False)+'\n')
    print(json.dumps(proof, indent=2, ensure_ascii=False))
    if args.reveal: subprocess.run(['open', '-R', str(destination)], check=True)
    if args.open:
        root = Path(__file__).resolve().parent.parent
        subprocess.run([str(root/'ml/.venv/bin/python'), str(root/'oria-lab-desktop/launch.py'),
                        '--import', str(destination)], check=True)

if __name__ == '__main__':
    try: main()
    except (ValueError, KeyError, RuntimeError, subprocess.SubprocessError, OSError, tarfile.TarError, zipfile.BadZipFile) as error:
        raise SystemExit(str(error))
