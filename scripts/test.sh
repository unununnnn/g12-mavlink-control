#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p artifacts/classes
find core/src -name '*.java' -print > artifacts/sources.txt
javac --release 17 -Xlint:all -Werror -encoding UTF-8 -d artifacts/classes @artifacts/sources.txt
java -cp artifacts/classes com.g12.core.CoreTests
find bench-core/src -name '*.java' -print > artifacts/bench-sources.txt
javac --release 17 -Xlint:all -Werror -encoding UTF-8 -d artifacts/classes @artifacts/bench-sources.txt
java -cp artifacts/classes com.g12.bench.ReadOnlySessionTest
java -cp artifacts/classes com.g12.bench.RetainedReadBackendTest
find sender-core/src -name '*.java' -print > artifacts/sender-sources.txt
javac --release 17 -Xlint:all -Werror -encoding UTF-8 -cp artifacts/classes -d artifacts/classes @artifacts/sender-sources.txt
java -cp artifacts/classes com.g12.sendercore.SenderTests
find x9-core/src -name '*.java' -print > artifacts/x9-sources.txt
javac --release 17 -Xlint:all -Werror -encoding UTF-8 -d artifacts/classes @artifacts/x9-sources.txt
java -cp artifacts/classes com.g12.x9core.DirectTests
java -cp artifacts/classes com.g12.x9core.ChannelTests
