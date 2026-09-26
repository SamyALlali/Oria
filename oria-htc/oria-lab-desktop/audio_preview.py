"""Explicit local Mac preview; never a simulated confirmation or HTC audio proof."""
from pathlib import Path
import struct
import subprocess
import tempfile
import threading
import wave

from replay import find_ffmpeg, normalize_label

_LOCK = threading.Lock()
GAINS = {'LEFT': (7, 3), 'RIGHT': (3, 7), 'CENTER': (10, 10)}


def stereo_pcm16(mono, pan):
    if pan not in GAINS or len(mono) % 2:
        raise ValueError('Canal ou PCM invalide')
    left, right = GAINS[pan]
    def gain(sample, value):
        return (1 if sample >= 0 else -1) * ((abs(sample) * value + 5) // 10)
    return b''.join(struct.pack('<hh', gain(sample, left), gain(sample, right))
                    for (sample,) in struct.iter_unpack('<h', mono))


def available():
    return Path('/usr/bin/say').is_file() and find_ffmpeg() is not None


def synthesize(text, pan):
    # Reuse the shared 80-scalar text sanitation; UI never passes shell fragments.
    text = normalize_label(text)
    if pan not in GAINS:
        raise ValueError('Canal inconnu')
    ffmpeg = find_ffmpeg()
    if not available():
        raise ValueError('Aperçu indisponible : voix macOS et FFmpeg local requis')
    if not _LOCK.acquire(blocking=False):
        raise ValueError('Une synthèse vocale est déjà en cours')
    try:
        with tempfile.TemporaryDirectory(prefix='oria-voice-') as tmp:
            root = Path(tmp); speech = root / 'speech.aiff'; mono = root / 'mono.wav'; output = root / 'preview.wav'
            subprocess.run(['/usr/bin/say', '-v', 'Thomas', '-o', str(speech), '--', text],
                           check=True, capture_output=True, timeout=20)
            subprocess.run([ffmpeg, '-hide_banner', '-loglevel', 'error', '-i', str(speech),
                            '-ac', '1', '-ar', '22050', '-c:a', 'pcm_s16le', str(mono)],
                           check=True, capture_output=True, timeout=20)
            with wave.open(str(mono), 'rb') as source:
                if source.getnframes() > 22050 * 30 or source.getsampwidth() != 2:
                    raise ValueError('Aperçu vocal hors limites')
                pcm = source.readframes(source.getnframes())
            with wave.open(str(output), 'wb') as destination:
                destination.setnchannels(2); destination.setsampwidth(2); destination.setframerate(22050)
                destination.writeframes(stereo_pcm16(pcm, pan))
            return output.read_bytes()
    except subprocess.CalledProcessError as error:
        raise ValueError('La voix locale macOS n’a pas pu produire cet aperçu') from error
    finally:
        _LOCK.release()
