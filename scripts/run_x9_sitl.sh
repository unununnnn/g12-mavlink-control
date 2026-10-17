#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test "$(id -u)" = 0
test "$(readlink /proc/self/ns/net)" != "$(readlink /proc/1/ns/net)"
ip link set lo up
mkdir -p artifacts/x9-sitl-classes
find x9-core/src -name '*.java' -print > artifacts/x9-sitl-sources.txt
# Same production sources; Java 11 target accommodates the preinstalled WSL JDK.
# Android and main regression builds separately compile with --release 17.
javac --release 11 -Xlint:all -Werror -encoding UTF-8 -d artifacts/x9-sitl-classes @artifacts/x9-sitl-sources.txt
exec python3 -m lab.x9_sitl --source "${1:?Provide fixed source/build directory}" --output "${2:-$PWD/artifacts/x9-sitl}"
