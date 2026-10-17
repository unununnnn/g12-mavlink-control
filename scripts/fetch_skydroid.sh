#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
revision=d9c6bcab8bb9993cd16f117c01a7601dce10ea6a
expected=e5977ba3292246bcee026defdb8563a18adc9d5573fdfb20a6880ee469f0fde2
destination="$PWD/vendor/rcsdk-v1.9.2.aar"
mkdir -p vendor
if [[ ! -f "$destination" ]]; then
  # Official public vendor source, fetched at build time; never committed or uploaded standalone.
  checkout=$(mktemp -d)
  git -C "$checkout" init -q
  git -C "$checkout" remote add origin https://gitee.com/skydroid/rcsdk-demo.git
  GIT_TERMINAL_PROMPT=0 git -C "$checkout" fetch --depth=1 origin "$revision"
  git -C "$checkout" show FETCH_HEAD:app/libs/rcsdk-v1.9.2.aar > "$destination"
fi
echo "$expected  $destination" | sha256sum --check --strict
