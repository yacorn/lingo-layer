"""Verify that the release APK really contains the pinned offline models."""
import hashlib
import json
import sys
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parent.parent
apk = Path(sys.argv[1]) if len(sys.argv) > 1 else root / 'build/app/outputs/flutter-apk/app-release.apk'
manifest = json.loads((root / 'models/manifest.json').read_text(encoding='utf-8-sig'))
with zipfile.ZipFile(apk) as archive:
    bundled = json.loads(archive.read('assets/model-manifest.json'))
    assert bundled == manifest, 'Bundled registry does not match pinned models'
    for filename in ('AIHub-JA-KO.txt', 'ONNX-MeCab-UniDic.txt'):
        expected = (root / 'android/app/src/main/assets/licenses' / filename).read_bytes()
        assert archive.read('assets/licenses/' + filename) == expected, f'Stale bundled license notice: {filename}'
        print('Verified bundled notice ' + filename)
    assert 'lib/arm64-v8a/libscreen_translate.so' in archive.namelist(), 'ARM64 inference engine missing'
    assert 'lib/arm64-v8a/libonnxruntime.so' in archive.namelist(), 'ARM64 ONNX Runtime missing'
    assert 'lib/arm64-v8a/libc++_shared.so' in archive.namelist(), 'C++ runtime missing'
    assert any(b'Lai/onnxruntime/TensorInfo;' in archive.read(name)
               for name in archive.namelist() if name.endswith('.dex')), 'R8 renamed TensorInfo required by ORT JNI'
    japanese_ocr = 'assets/mlkit-google-ocr-models/gocr/gocr_models/line_recognition_legacy_mobile/Jpan_ctc/optical/'
    for filename in ('conv_model.fb', 'lstm_model.fb'):
        assert archive.getinfo(japanese_ocr + filename).file_size > 0, 'Bundled Japanese OCR missing'
    assert not any(name.endswith(('.gguf', 'model.bin')) for name in archive.namelist()), 'Previous comparison weights leaked into selected APK'
    for model in manifest['models']:
        assert sum(file['bytes'] for file in model['files']) == model['bytes']
        for file in model['files']:
            entry = archive.getinfo('assets/models/' + model['directory'] + '/' + file['file'])
            assert entry.file_size == file['bytes'], f"Wrong size: {file['file']}"
            if file['file'].endswith(('.bin', '.dic', '.onnx')):
                assert entry.compress_type == zipfile.ZIP_STORED, 'Weights must be stored without compression'
            with archive.open(entry) as source:
                checksum = hashlib.file_digest(source, 'sha256').hexdigest()
            assert checksum == file['sha256'], f"Wrong SHA-256: {file['file']}"
        print('Verified bundled ' + model['id'])
print(f'Offline release APK verified: {apk.stat().st_size:,} bytes')
