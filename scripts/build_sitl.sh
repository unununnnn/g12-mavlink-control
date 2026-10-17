#!/usr/bin/env bash
# Uses an existing compiler/Python environment; no apt/pip/license installation.
set -euo pipefail
revision=dbe792162d06cab66c3475fd5556bf7a120f119e
destination=${1:?Provide a dedicated persistent build directory}
origin=https://github.com/ArduPilot/ardupilot.git
if [[ ! -e "$destination" ]]; then
    mkdir -p "$destination"
    git -C "$destination" init -q
    git -C "$destination" remote add origin "$origin"
    # Optional local object source is read-only. No working files are copied.
    git -C "$destination" fetch --depth 1 "${G12_SOURCE_CACHE:-$origin}" "$revision"
    git -C "$destination" checkout --detach FETCH_HEAD
fi
test "$(git -C "$destination" rev-parse HEAD)" = "$revision"
test "$(git -C "$destination" remote get-url origin)" = "$origin"
test -z "$(git -C "$destination" status --porcelain --untracked-files=no)"
cd "$destination"
git -c http.lowSpeedLimit=1000 -c http.lowSpeedTime=30 submodule update --init --recursive --depth 1 \
    modules/waf modules/mavlink modules/gtest modules/littlefs modules/lwip \
    modules/DroneCAN/DSDL modules/DroneCAN/pydronecan \
    modules/DroneCAN/dronecan_dsdlc modules/DroneCAN/libcanard
./waf configure --board sitl --no-submodule-update
./waf copter -j"${G12_BUILD_JOBS:-4}"
PYTHONPATH=modules/mavlink python3 modules/mavlink/pymavlink/tools/mavgen.py \
    --lang Python3 --wire-protocol 2.0 --output build/g12_dialect.py \
    modules/mavlink/message_definitions/v1.0/ardupilotmega.xml
python3 - "$revision" <<'PY'
import hashlib, json, pathlib, subprocess, sys
binary = pathlib.Path('build/sitl/bin/arducopter')
manifest = {
    'revision': sys.argv[1], 'binary_sha256': hashlib.sha256(binary.read_bytes()).hexdigest(),
    'dialect_sha256': hashlib.sha256(pathlib.Path('build/g12_dialect.py').read_bytes()).hexdigest(),
    'submodules': subprocess.check_output(['git', 'submodule', 'status', '--recursive'], text=True),
    'compiler': subprocess.check_output(['g++', '--version'], text=True).splitlines()[0],
    'configure': './waf configure --board sitl --no-submodule-update',
    'build': './waf copter',
}
pathlib.Path('build/g12-provenance.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(json.dumps({k: v for k, v in manifest.items() if k != 'submodules'}, indent=2))
PY
