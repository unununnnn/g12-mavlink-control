#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test "$(id -u)" = 0 # net namespace setup needs CAP_NET_ADMIN, never changes host networking.
test "$(readlink /proc/self/ns/net)" != "$(readlink /proc/1/ns/net)"
ip link set lo up
exec python3 -m lab.sitl_e2e --source "${1:?Provide fixed source/build directory}" \
    --output "${2:-$PWD/artifacts/sitl}"
