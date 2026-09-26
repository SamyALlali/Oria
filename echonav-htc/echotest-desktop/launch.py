#!/usr/bin/env python3
"""Mac launcher: starts only this local server and opens its actual selected port."""
import argparse
from pathlib import Path
import threading
import webbrowser
from server import create_server
from replay import SessionStore

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--import', dest='import_path', type=Path, help='ZIP ou dossier local à importer avant ouverture')
args = parser.parse_args()
session_id = None
if args.import_path:
    print('Import local de la capture…', flush=True)
    store = SessionStore()
    session = store.import_folder(args.import_path) if args.import_path.is_dir() else store.import_zip(args.import_path)
    session_id = session.id
server = create_server(port=0)
url = f'http://127.0.0.1:{server.server_port}' + (f'/?session={session_id}' if session_id else '')
threading.Thread(target=lambda: webbrowser.open(url), daemon=True).start()
print(f'Oria Lab prêt : {url}\nGardez ce terminal ouvert. Ctrl+C pour arrêter.', flush=True)
try:
    server.serve_forever()
except KeyboardInterrupt:
    pass
finally:
    server.server_close()
