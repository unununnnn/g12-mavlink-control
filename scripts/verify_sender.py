"""Inspect actual SDK sender APK without running SDK or opening a device."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile

apk, aar = Path(sys.argv[1]), Path(sys.argv[2])
bt = Path(os.environ['ANDROID_HOME']) / 'build-tools' / '35.0.0'
def run(*args): return subprocess.check_output([str(x) for x in args],text=True)
badging=run(bt/'aapt','dump','badging',apk)
assert "name='com.g12.bench.sender'" in badging
assert "targetSdkVersion:'33'" in badging and "sdkVersion:'23'" in badging
permissions=run(bt/'aapt','dump','permissions',apk)
assert [x for x in permissions.splitlines() if x.startswith('uses-permission:')]==["uses-permission: name='android.permission.INTERNET'"]
signature=run(bt/'apksigner','verify','--verbose','--print-certs',apk)
alignment=run(bt/'zipalign','-c','-P','16','-v','4',apk)
with zipfile.ZipFile(aar) as vendor, zipfile.ZipFile(apk) as app:
    native=[n for n in vendor.namelist() if n.startswith('jni/') and n.endswith('.so')]
    assert len(native)==14
    for name in native: assert vendor.read(name)==app.read(name.replace('jni/','lib/',1)),name
    dex=b''.join(app.read(n) for n in app.namelist() if n.endswith('.dex'))
    for name in [b'Lcom/skydroid/rcsdk/PipelineManager;',b'Lcom/g12/sender/SdkPipelineLink;',b'Lcom/g12/sendercore/SendEngine;',b'Lkotlin/jvm/internal/Intrinsics;']:
        assert name in dex,name
    for name in [b'Lcom/g12/core/ControlSession;',b'Lcom/g12/core/VendorAdapter;',b'Lcom/g12/simulator/']:
        assert name not in dex,name
classes=Path('sender/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes')
own=sorted(str(p.relative_to(classes)).replace(os.sep,'.').removesuffix('.class') for p in classes.rglob('*.class'))
code=run('javap','-classpath',classes,'-c','-p',*own)
for forbidden in ['KeyManager.set:', 'KeyManager.action:', 'PayloadManager', 'UpgradeManager', 'java/net/', 'java/lang/Runtime.exec', 'ProcessBuilder']:
    assert forbidden not in code,forbidden
for required in ['createG12G20Pipeline','connectPipeline','disconnectPipeline','writeData','getKeyChannels','/system/bin/data_stream','SendEngine.start','releaseAndStop']:
    assert required in code,required
report={
    'commit':os.environ.get('GITHUB_SHA',run('git','rev-parse','HEAD').strip()),
    'apk':apk.name,'apk_bytes':apk.stat().st_size,'apk_sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),
    'sdk_sha256':hashlib.sha256(aar.read_bytes()).hexdigest(),
    'target_android':'13 / API 33','official_native_libraries_verified':14,
    'signature_verified':True,'zip_alignment_verified':True,'only_permission':'INTERNET',
    'actual_sdk_pipeline_linked':True,'simulation_control_session_absent':True,
    'no_app_parameter_arm_mode_or_upgrade_calls':True,
    'default_sending':False,'intended_receiver':'PC serial capture with exact frame echo; flight controller disconnected',
    'max_outstanding_frames':1,'max_burst_ms':10000,'max_rate_hz':10,
    'local_input_timeout_ms':250,'write_timeout_ms':200,'echo_timeout_ms':800,
    'source_hardware_freshness_verified':False,'cross_device_exclusivity_deployed':False,
    'hardware_sender_tested':False,'readonly_previous_version_test':'user reported success, no independent hardware evidence',
}
out=Path('artifacts/sender');out.mkdir(parents=True,exist_ok=True)
for name,value in [('apk-badging.txt',badging),('apk-signature.txt',signature),('apk-alignment.txt',alignment),('apk-verification.json',json.dumps(report,indent=2)+'\n')]:
    (out/name).write_text(value,encoding='utf-8')
(out/'SHA256SUMS.txt').write_text(f"{report['apk_sha256']}  {apk.name}\n",encoding='utf-8')
print(json.dumps(report,indent=2))
