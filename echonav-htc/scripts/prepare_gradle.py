#!/usr/bin/env python3
"""Restore the official Gradle distribution expected by the HTC offline wrapper."""
import hashlib
from pathlib import Path
import shutil
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
SHA256 = '20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78'
URL = 'https://services.gradle.org/distributions/gradle-8.13-bin.zip'


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def main():
    target = ROOT / 'gradle-dist/gradle-8.13-bin.zip'
    if target.exists():
        if digest(target) != SHA256:
            raise SystemExit(f'Empreinte différente : {target}. Fichier conservé ; vérifier sa provenance.')
        print(f'Gradle 8.13 déjà présent et vérifié : {target}')
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=target.parent, suffix='.part', delete=False) as output:
            temporary = Path(output.name)
            print('Téléchargement de Gradle 8.13 depuis la distribution officielle…', flush=True)
            with urllib.request.urlopen(URL, timeout=60) as response:
                shutil.copyfileobj(response, output)
        if digest(temporary) != SHA256:
            raise SystemExit('Empreinte Gradle inattendue ; distribution non installée.')
        # Keep an existing file intact if another preparation completed meanwhile.
        if target.exists():
            if digest(target) != SHA256:
                raise SystemExit('Une distribution différente est apparue ; fichier conservé.')
        else:
            temporary.rename(target)
        print(f'Gradle 8.13 prêt : {target}')
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
