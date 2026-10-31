"""Extract only pinned runtime dictionary files; Python standard library only."""
import hashlib
import json
import shutil
import tarfile
import urllib.request
from pathlib import Path

root = Path(__file__).resolve().parent.parent
directory = root / '.cache/unidic-runtime'
directory.mkdir(parents=True, exist_ok=True)
archive = directory / 'unidic-lite-1.0.8.tar.gz'
if not archive.exists():
    partial = archive.with_suffix('.part')
    urllib.request.urlretrieve('https://files.pythonhosted.org/packages/55/2b/8cf7514cb57d028abcef625afa847d60ff1ffbf0049c36b78faa7c35046f/unidic-lite-1.0.8.tar.gz', partial)
    partial.rename(archive)
with archive.open('rb') as source:
    assert hashlib.file_digest(source, 'sha256').hexdigest() == 'db9d4572d9fdd4d00a97949d4b0741ec480ee05a7e7e2e32f547500dae27b245'
manifest = json.loads((root / 'models/manifest.json').read_text(encoding='utf-8'))
with tarfile.open(archive) as source:
    for entry in manifest['models'][0]['files']:
        if entry.get('source') != 'unidic-lite-1.0.8':
            continue
        filename = entry['file']
        assert '/' not in filename and '\\' not in filename and filename not in ('.', '..')
        target = directory / filename
        with source.extractfile('unidic-lite-1.0.8/unidic_lite/dicdir/' + filename) as data, target.open('wb') as out:
            shutil.copyfileobj(data, out)
        with target.open('rb') as data:
            assert target.stat().st_size == entry['bytes'] and hashlib.file_digest(data, 'sha256').hexdigest() == entry['sha256']
print('Pinned UniDic runtime verified')
