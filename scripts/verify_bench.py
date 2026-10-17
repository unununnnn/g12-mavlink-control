"""Verify SDK artifact, native libraries and compiled read-only application boundary.
No SDK execution or device connection. Fail closed on unexpected permissions.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile

apk = Path(sys.argv[1])
aar = Path(sys.argv[2])
sdk = Path(os.environ['ANDROID_HOME'])
build_tools = sdk / 'build-tools' / '35.0.0'
def run(*args):
    return subprocess.check_output([str(a) for a in args], text=True)

badging = run(build_tools / 'aapt', 'dump', 'badging', apk)
permissions = run(build_tools / 'aapt', 'dump', 'permissions', apk)
assert "name='com.g12.bench.readonly'" in badging
assert "targetSdkVersion:'33'" in badging
assert "sdkVersion:'23'" in badging
permission_lines = [x for x in permissions.splitlines() if x.startswith('uses-permission:')]
assert permission_lines == ["uses-permission: name='android.permission.INTERNET'"], permissions
signing = run(build_tools / 'apksigner', 'verify', '--verbose', '--print-certs', apk)
aligned = run(build_tools / 'zipalign', '-c', '-P', '16', '-v', '4', apk)
# check_output already rejects nonzero exit; zipalign's success text is not a stable API.
with zipfile.ZipFile(aar) as vendor, zipfile.ZipFile(apk) as app:
    native = [n for n in vendor.namelist() if n.startswith('jni/') and n.endswith('.so')]
    assert len(native) == 14
    for name in native:
        assert vendor.read(name) == app.read(name.replace('jni/', 'lib/', 1)), name
    dex = b''.join(app.read(n) for n in app.namelist() if n.endswith('.dex'))
    for required in [b'Lcom/skydroid/rcsdk/RCSDKManager;', b'Lcom/g12/bench/SkydroidBackend;', b'Lkotlin/jvm/internal/Intrinsics;']:
        assert required in dex, required
    assert b'Lcom/g12/core/' not in dex, 'Control core must not be bundled'
    assert b'Lcom/g12/simulator/' not in dex, 'Simulation must not be bundled'

# Inspect our classes: vendor AAR itself naturally contains unused write/upgrade APIs.
classes = Path('bench/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes')
own = sorted(str(p.relative_to(classes)).replace(os.sep, '.').removesuffix('.class') for p in classes.rglob('*.class'))
bytecode = run('javap', '-classpath', classes, '-c', '-p', *own)
for forbidden in ['PipelineManager', 'writeData', 'KeyManager.set:', 'KeyManager.action:', 'PayloadManager', 'UpgradeManager', 'java/net/', 'java.net.', 'com/g12/core']:
    assert forbidden not in bytecode, forbidden
for required in ['getKeyChannels', 'getKeyRawSignalQuality', 'getKeySignalQuality', 'connectToRC', 'disconnectRC']:
    assert required in bytecode, required
report = {
    'commit': os.environ.get('GITHUB_SHA', run('git', 'rev-parse', 'HEAD').strip()),
    'apk': apk.name, 'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
    'apk_bytes': apk.stat().st_size, 'sdk_sha256': hashlib.sha256(aar.read_bytes()).hexdigest(),
    'target_android': '13 / API 33', 'native_libraries_verified': len(native),
    'permissions': permission_lines, 'sdk_classes_present': True, 'kotlin_runtime_present': True,
    'own_bytecode_readonly_boundary': 'PASS', 'simulation_and_control_core_absent': True,
    'apk_signature_verified': True, 'zip_alignment_verified': True,
    'hardware_tested': False, 'flight_control_send_enabled': False,
}
out = Path('artifacts/bench'); out.mkdir(parents=True, exist_ok=True)
(out / 'apk-verification.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
(out / 'apk-badging.txt').write_text(badging, encoding='utf-8')
(out / 'apk-signature.txt').write_text(signing, encoding='utf-8')
(out / 'apk-alignment.txt').write_text(aligned, encoding='utf-8')
(out / 'SHA256SUMS.txt').write_text(f"{report['apk_sha256']}  {apk.name}\n", encoding='utf-8')
print(json.dumps(report, indent=2))
