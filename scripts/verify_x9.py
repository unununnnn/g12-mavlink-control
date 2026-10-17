"""Inspect the built APK without executing vendor SDK or opening hardware."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile

apk, aar = Path(sys.argv[1]), Path(sys.argv[2])
bt = Path(os.environ['ANDROID_HOME']) / 'build-tools' / '35.0.0'
def run(*args):
    return subprocess.check_output([str(a) for a in args], text=True)
badging = run(bt/'aapt', 'dump', 'badging', apk)
for field in ("name='com.g12.x9'", "versionCode='5'", "versionName='0.5.0-x9-direct'", "sdkVersion:'33'", "targetSdkVersion:'33'"):
    assert field in badging, field
permissions = run(bt/'aapt', 'dump', 'permissions', apk)
assert [s for s in permissions.splitlines() if s.startswith('uses-permission:')] == ["uses-permission: name='android.permission.INTERNET'"]
signature = run(bt/'apksigner', 'verify', '--verbose', '--print-certs', apk)
alignment = run(bt/'zipalign', '-c', '-P', '16', '-v', '4', apk)
assert hashlib.sha256(aar.read_bytes()).hexdigest() == 'e5977ba3292246bcee026defdb8563a18adc9d5573fdfb20a6880ee469f0fde2'
with zipfile.ZipFile(aar) as vendor, zipfile.ZipFile(apk) as app:
    native = [n for n in vendor.namelist() if n.startswith('jni/') and n.endswith('.so')]
    assert len(native) == 14
    for name in native:
        assert vendor.read(name) == app.read(name.replace('jni/', 'lib/', 1)), name
    dex = b''.join(app.read(n) for n in app.namelist() if n.endswith('.dex'))
    for name in (b'Lcom/skydroid/rcsdk/PipelineManager;', b'Lcom/g12/x9/SdkLink;', b'Lcom/g12/x9core/ChannelSession;', b'Lcom/g12/bench/RetainedReadBackend;'):
        assert name in dex, name
    for name in (b'Lcom/g12/core/ControlSession;', b'Lcom/g12/sendercore/SendEngine;', b'Lcom/g12/simulator/', b'Lcom/g12/x9/ProfileStore;'):
        assert name not in dex, name
classes = Path('x9/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes')
own = sorted(str(p.relative_to(classes)).replace(os.sep, '.').removesuffix('.class') for p in classes.rglob('*.class'))
assert own
code = run('javap', '-classpath', classes, '-c', '-p', *own)
for forbidden in ('KeyManager.set:', 'KeyManager.action:', 'PayloadManager', 'UpgradeManager', 'java/net/', 'java/lang/Runtime.exec', 'ProcessBuilder', 'ProfileCodec', 'ProfileStore', 'Calibration', 'SharedPreferences'):
    assert forbidden not in code, forbidden
for required in ('createG12G20Pipeline', 'connectPipeline', 'disconnectPipeline', 'writeData', 'getKeyChannels', '/system/bin/data_stream', 'ReadOnlySession.pause', 'ChannelSession.connect'):
    assert required in code, required
report = {
    'commit': os.environ.get('GITHUB_SHA', run('git', 'rev-parse', 'HEAD').strip()),
    'apk': apk.name, 'apk_bytes': apk.stat().st_size, 'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
    'sdk_sha256': hashlib.sha256(aar.read_bytes()).hexdigest(), 'official_native_libraries_verified': 14,
    'signature_verified': True, 'zip_alignment_verified': True, 'only_permission': 'INTERNET',
    'actual_sdk_pipeline_linked': True, 'foreground_read_connection_retained': True,
    'no_app_parameter_arm_mode_or_upgrade_calls': True,
    'default_sending': False, 'activation': 'user clicks connect, automatic heartbeat/source discovery, fresh inputs',
    'first_run_setup': False, 'saved_profile_or_binding': False, 'uid_or_firmware_version_required': False,
    'channel_mapping': 'SDK index + 1 to same RC channel, including auxiliary switches, no scaling/reversal',
    'maximum_channels': 18, 'mavlink1_maximum_channels': 8, 'silent_truncation': False,
    'verified_copter_4_7_1_adoption_channels': 16, 'feedback_check_max_channels': 16,
    'auxiliary_controls_may_trigger_configured_actions': True,
    'total_duration_limit_ms': None, 'armed_state_gate': False, 'max_rate_hz': 10,
    'local_input_timeout_ms': 250, 'sdk_write_timeout_ms': 200, 'telemetry_timeout_ms': 3000,
    'parameter_writes': False, 'periodic_gcs_heartbeat': False, 'automatic_release': False,
    'source_hardware_freshness_verified': False, 'cross_device_exclusivity_deployed': False,
    'hardware_tested': False, 'flight_validated': False,
}
out = Path('artifacts/x9'); out.mkdir(parents=True, exist_ok=True)
for name, value in [('apk-badging.txt', badging), ('apk-signature.txt', signature), ('apk-alignment.txt', alignment), ('apk-verification.json', json.dumps(report, indent=2)+'\n')]:
    (out/name).write_text(value, encoding='utf-8')
(out/'SHA256SUMS.txt').write_text(f"{report['apk_sha256']}  {apk.name}\n", encoding='utf-8')
print(json.dumps(report, indent=2))
