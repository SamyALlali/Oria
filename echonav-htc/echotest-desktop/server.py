#!/usr/bin/env python3
"""Local Oria Lab UI. Launch with ../ml/.venv/bin/python server.py; no internet dependencies."""
import argparse
import json
import mimetypes
from pathlib import Path
import secrets
import tempfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from replay import DEFAULT_STORAGE, MAX_UPLOAD, ReplayJobs, SessionStore, export_context_video, safe_path

HERE = Path(__file__).resolve().parent


def create_server(storage=DEFAULT_STORAGE, port=8765):
    store, jobs, token = SessionStore(storage), ReplayJobs(), secrets.token_urlsafe(32)

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            # Local paths/session events are intentionally not printed in HTTP access logs.
            pass

        def check_origin(self, mutation=False):
            host = self.headers.get('Host', '')
            expected = f'127.0.0.1:{self.server.server_port}'
            if host != expected:
                raise PermissionError('Hôte local invalide')
            origin = self.headers.get('Origin')
            if origin and origin != 'http://' + expected:
                raise PermissionError('Origine refusée')
            if mutation and self.headers.get('X-EchoTest-Token') != token:
                raise PermissionError('Jeton local invalide')

        def write_headers(self, status, content_type, length, extra=None):
            self.send_response(status)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(length))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; media-src 'self'; connect-src 'self'; frame-ancestors 'none'")
            for key, value in (extra or {}).items():
                self.send_header(key, value)
            self.end_headers()

        def json(self, value, status=200):
            data = json.dumps(value, ensure_ascii=False, allow_nan=False).encode()
            self.write_headers(status, 'application/json; charset=utf-8', len(data))
            self.wfile.write(data)

        def file(self, path, media_type=None):
            if not path.is_file():
                raise FileNotFoundError('Fichier introuvable')
            size = path.stat().st_size
            start, end, status = 0, size - 1, 200
            extra = {'Accept-Ranges': 'bytes'}
            requested = self.headers.get('Range')
            if requested:
                import re
                match = re.fullmatch(r'bytes=(\d+)-(\d*)', requested)
                if not match:
                    raise ValueError('Intervalle vidéo invalide')
                start = int(match.group(1)); end = min(size - 1, int(match.group(2))) if match.group(2) else size - 1
                if start > end or start >= size:
                    self.write_headers(416, 'text/plain', 0, {'Content-Range': f'bytes */{size}'})
                    return
                status = 206; extra['Content-Range'] = f'bytes {start}-{end}/{size}'
            remaining = end - start + 1
            self.write_headers(status, media_type or mimetypes.guess_type(path.name)[0] or 'application/octet-stream', remaining, extra)
            with path.open('rb') as stream:
                stream.seek(start)
                while remaining:
                    chunk = stream.read(min(remaining, 1024 * 1024))
                    if not chunk: break
                    self.wfile.write(chunk); remaining -= len(chunk)

        def do_GET(self):
            try:
                self.check_origin()
                url = urlparse(self.path)
                parts = url.path.strip('/').split('/')
                query = parse_qs(url.query)
                if url.path == '/api/config':
                    return self.json({'token': token, 'storage': str(store.storage), 'maxUploadBytes': MAX_UPLOAD})
                if len(parts) == 3 and parts[:2] == ['api', 'session']:
                    return self.json(store.get(parts[2]).index())
                if len(parts) == 5 and parts[:2] == ['api', 'session'] and parts[3] == 'frame':
                    return self.json(store.get(parts[2]).frame(int(parts[4])))
                if len(parts) == 5 and parts[:2] == ['api', 'session'] and parts[3] == 'image':
                    session = store.get(parts[2]); frame = session.frame(int(parts[4]))['frame']
                    return self.file(safe_path(session.path, frame['imagePath']), 'image/png')
                if len(parts) == 4 and parts[:2] == ['api', 'session'] and parts[3] == 'video':
                    return self.file(store.get(parts[2]).path / 'context-video.mp4', 'video/mp4')
                if len(parts) == 3 and parts[:2] == ['api', 'job']:
                    return self.json(jobs.get(parts[2], int(query['frame'][0]) if 'frame' in query else None))
                if url.path in ('/', '/app.js', '/style.css'):
                    return self.file(HERE / 'static' / {'/': 'index.html', '/app.js': 'app.js', '/style.css': 'style.css'}[url.path])
                raise FileNotFoundError('Page inconnue')
            except (BrokenPipeError, ConnectionResetError):
                pass
            except Exception as error:
                self.fail(error)

        def read_json(self):
            size = int(self.headers.get('Content-Length', '0'))
            if not 0 < size <= 1024 * 1024:
                raise ValueError('Corps JSON invalide ou trop volumineux')
            return json.loads(self.rfile.read(size))

        def do_POST(self):
            try:
                self.check_origin(mutation=True)
                if self.path == '/api/import/zip':
                    size = int(self.headers.get('Content-Length', '0'))
                    if not 0 < size <= MAX_UPLOAD:
                        raise ValueError('ZIP vide ou supérieur à 1 Gio')
                    with tempfile.NamedTemporaryFile(prefix='.upload-', suffix='.zip', dir=store.storage) as upload:
                        remaining = size
                        while remaining:
                            chunk = self.rfile.read(min(remaining, 1024 * 1024))
                            if not chunk:
                                raise ValueError('Envoi ZIP incomplet')
                            upload.write(chunk); remaining -= len(chunk)
                        upload.flush()
                        return self.json(store.import_zip(Path(upload.name)).index())
                if self.path == '/api/import/folder':
                    return self.json(store.import_folder(self.read_json()['path']).index())
                if self.path == '/api/recompute':
                    body = self.read_json()
                    identifier = jobs.create(store.get(body['sessionId']), body.get('confirmationMs', 2000), body.get('policyConfig', {}))
                    return self.json({'jobId': identifier})
                if self.path == '/api/context-video':
                    body = self.read_json(); session = store.get(body['sessionId'])
                    with session.lock:
                        return self.json(export_context_video(session))
                raise FileNotFoundError('Action inconnue')
            except (BrokenPipeError, ConnectionResetError):
                pass
            except Exception as error:
                self.fail(error)

        def fail(self, error):
            status = 403 if isinstance(error, PermissionError) else 404 if isinstance(error, (KeyError, IndexError, FileNotFoundError)) else 400
            self.json({'error': str(error)}, status)

    server = ThreadingHTTPServer(('127.0.0.1', port), Handler)
    server.daemon_threads = True
    return server


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--storage', type=Path, default=DEFAULT_STORAGE)
    args = parser.parse_args()
    server = create_server(args.storage, args.port)
    print(f'Oria Lab : http://127.0.0.1:{server.server_port}', flush=True)
    print(f'Captures locales : {args.storage}', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
