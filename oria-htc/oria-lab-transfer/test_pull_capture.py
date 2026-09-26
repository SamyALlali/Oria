import contextlib
import io
import json
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import pull_capture as transfer

ID = '11111111-1111-1111-1111-111111111111'
MANIFEST = {'sessionId': ID, 'status': 'complete', 'startedAtEpochMs': 1}

def tar_fixture(frames=3):
    stream=io.BytesIO()
    with tarfile.open(fileobj=stream,mode='w') as archive:
        files=[('manifest.json',json.dumps(MANIFEST).encode()),('session-label.json',b'{"schemaVersion":1,"displayName":"Fixture longue"}')]
        files.extend((f'frames/{i:010}.png',b'fixture-png') for i in range(frames))
        for name,data in files:
            info=tarfile.TarInfo(ID+'/'+name);info.size=len(data);archive.addfile(info,io.BytesIO(data))
    stream.seek(0);return stream

class FakeProcess:
    def __init__(self,source,code=0):self.stdout,self.code,self.killed=source,code,False
    def poll(self):return self.code if self.killed else None
    def kill(self):self.killed=True
    def wait(self,**unused):return self.code

def fake_read(command,**unused):
    if command[-1]=='devices':return 'List of devices attached\nFIXTURE device\n'
    if command[-2:]==['ls','files/oria-lab']:return ID.encode()
    if command[-2]=='cat':return json.dumps(MANIFEST).encode()
    raise AssertionError(command)

@contextlib.contextmanager
def transport(tmp,source=None,code=0):
    with patch.object(transfer.subprocess,'check_output',side_effect=fake_read),patch.object(transfer.subprocess,'Popen',return_value=FakeProcess(source or tar_fixture(),code)) as launch,patch.object(sys,'argv',['pull_capture.py','--output',str(tmp)]),contextlib.redirect_stdout(io.StringIO()):
        yield launch

class TransferTests(unittest.TestCase):
    def test_thirty_minute_and_two_hour_sizes_are_admitted_without_materializing_media(self):
        for minutes in (30,120):
            item=tarfile.TarInfo(ID+'/video.h264');item.size=minutes*80*1024**2
            name,total=transfer.validate_member(item,ID,set(),0)
            self.assertEqual(name,'video.h264');self.assertEqual(total,item.size);self.assertGreater(total,2*1024**3)

    def test_file_and_byte_bounds_are_explicit_and_never_truncate(self):
        names={f'frames/{i}.png' for i in range(transfer.MAX_FILES)}
        with self.assertRaisesRegex(ValueError,'100000 fichiers'):transfer.validate_member(tarfile.TarInfo(ID+'/next'),ID,names,0)
        item=tarfile.TarInfo(ID+'/video.h264');item.size=transfer.LIMIT
        self.assertEqual(transfer.validate_member(item,ID,set(),0)[1],transfer.LIMIT)
        item.size+=1
        with self.assertRaisesRegex(ValueError,'128 Gio'):transfer.validate_member(item,ID,set(),0)

    def test_over_ten_thousand_files_transfer_losslessly(self):
        with tempfile.TemporaryDirectory() as tmp:
            output=Path(tmp)/'capture.zip.part';counts=transfer.copy_capture_tar(tar_fixture(10001),output,ID)
            self.assertEqual(counts['files'],10003);transfer.verify_archive(output,MANIFEST)
            with zipfile.ZipFile(output) as archive:self.assertEqual(archive.read('frames/0000010000.png'),b'fixture-png')

    def test_traversal_links_and_duplicate_members_are_rejected(self):
        for path in (ID+'/../outside','/absolute',ID+'/a\\b'):
            with self.subTest(path=path),self.assertRaises(ValueError):transfer.validate_member(tarfile.TarInfo(path),ID,set(),0)
        item=tarfile.TarInfo(ID+'/link');item.type=tarfile.SYMTYPE
        with self.assertRaises(ValueError):transfer.validate_member(item,ID,set(),0)
        with self.assertRaisesRegex(ValueError,'dupliquée'):transfer.validate_member(tarfile.TarInfo(ID+'/manifest.json'),ID,{'manifest.json'},0)

    def test_disk_guard_includes_zip_metadata(self):
        stream=io.BytesIO();guard=transfer.ReservedDiskWriter(stream,Path('/tmp'))
        with patch.object(transfer.shutil,'disk_usage',return_value=type('Usage',(),{'free':transfer.RESERVED_FREE_BYTES+100})()):guard.write(b'x'*100)
        with patch.object(transfer.shutil,'disk_usage',return_value=type('Usage',(),{'free':transfer.RESERVED_FREE_BYTES})()):
            with self.assertRaisesRegex(ValueError,'512 Mio'):guard.write(b'y')
        self.assertEqual(stream.getvalue(),b'x'*100)

    def test_fake_transport_produces_crc_verified_zip_and_hash_receipt(self):
        with tempfile.TemporaryDirectory() as tmp:
            with transport(tmp):transfer.main()
            output=Path(tmp)/f'OriaLab-{ID}.zip';transfer.verify_archive(output,MANIFEST)
            receipt=json.loads(output.with_suffix('.transfer.json').read_text())
            self.assertTrue(receipt['phone_data_preserved']);self.assertEqual(len(receipt['sha256']),64)
            self.assertEqual(receipt['transfer_limits']['maxBytes'],128*1024**3);self.assertFalse(receipt['archive_reused'])

    def test_failed_transport_removes_only_its_own_partial(self):
        for source,code in ((tar_fixture(),1),(io.BytesIO(b'not tar'),0)):
            with self.subTest(code=code),tempfile.TemporaryDirectory() as tmp:
                keep=Path(tmp)/'existing.zip';keep.write_bytes(b'keep')
                with transport(tmp,source,code):
                    with self.assertRaises((RuntimeError,tarfile.TarError)):transfer.main()
                self.assertEqual(keep.read_bytes(),b'keep');self.assertEqual(list(Path(tmp).iterdir()),[keep])

    def test_existing_partial_and_raced_partial_are_never_removed(self):
        for race in (False,True):
            with self.subTest(race=race),tempfile.TemporaryDirectory() as tmp:
                part=Path(tmp)/f'OriaLab-{ID}.zip.part'
                if not race:part.write_bytes(b'previous interruption')
                def disk(path,required):
                    if race:part.write_bytes(b'competing transfer')
                with transport(tmp) as launch,patch.object(transfer,'require_disk_space',side_effect=disk):
                    with self.assertRaises((ValueError,FileExistsError)):transfer.main()
                    launch.assert_not_called()
                self.assertEqual(part.read_bytes(),b'competing transfer' if race else b'previous interruption')

    def test_concurrent_destination_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as tmp:
            final=Path(tmp)/f'OriaLab-{ID}.zip';original=transfer.verify_archive
            def raced_verify(path,manifest):
                original(path,manifest);final.write_bytes(b'concurrent destination')
            with transport(tmp),patch.object(transfer,'verify_archive',side_effect=raced_verify):
                with self.assertRaises(FileExistsError):transfer.main()
            self.assertEqual(final.read_bytes(),b'concurrent destination');self.assertFalse(final.with_suffix('.zip.part').exists())

    def test_existing_archive_requires_full_manifest_identity(self):
        with tempfile.TemporaryDirectory() as tmp:
            archive=Path(tmp)/'capture.zip';transfer.copy_capture_tar(tar_fixture(),archive,ID)
            with self.assertRaisesRegex(ValueError,'différente ou modifiée'):transfer.verify_archive(archive,{**MANIFEST,'status':'incomplete'})
            self.assertTrue(archive.exists())

    def test_directory_headers_cannot_bypass_stream_bounds(self):
        stream=io.BytesIO()
        with tarfile.open(fileobj=stream,mode='w') as archive:
            for i in range(4):
                item=tarfile.TarInfo(f'{ID}/folder{i}');item.type=tarfile.DIRTYPE;archive.addfile(item)
        stream.seek(0)
        with tempfile.TemporaryDirectory() as tmp,patch.object(transfer,'MAX_TAR_ENTRIES',3):
            with self.assertRaisesRegex(ValueError,'en-têtes TAR'):transfer.copy_capture_tar(stream,Path(tmp)/'capture.zip',ID)

if __name__=='__main__':unittest.main()
