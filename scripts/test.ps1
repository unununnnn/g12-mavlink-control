$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    $javaBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { '' }
    $javac = if ($javaBin) { Join-Path $javaBin 'javac.exe' } else { 'javac' }
    $java = if ($javaBin) { Join-Path $javaBin 'java.exe' } else { 'java' }
    New-Item -ItemType Directory -Force artifacts/classes | Out-Null
    $sources = @(Get-ChildItem core/src -Recurse -Filter '*.java' | ForEach-Object FullName)
    & $javac --release 17 -Xlint:all -Werror -encoding UTF-8 -d artifacts/classes @sources
    if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
    & $java -cp artifacts/classes com.g12.core.CoreTests
    if ($LASTEXITCODE -ne 0) { throw 'Regression failed' }
    $benchSources = @(Get-ChildItem bench-core/src -Recurse -Filter '*.java' | ForEach-Object FullName)
    & $javac --release 17 -Xlint:all -Werror -encoding UTF-8 -d artifacts/classes @benchSources
    if ($LASTEXITCODE -ne 0) { throw 'Bench javac failed' }
    & $java -cp artifacts/classes com.g12.bench.ReadOnlySessionTest
    if ($LASTEXITCODE -ne 0) { throw 'Bench regression failed' }
    & $java -cp artifacts/classes com.g12.bench.RetainedReadBackendTest
    if ($LASTEXITCODE -ne 0) { throw 'Capture restart regression failed' }
    $senderSources = @(Get-ChildItem sender-core/src -Recurse -Filter '*.java' | ForEach-Object FullName)
    & $javac --release 17 -Xlint:all -Werror -encoding UTF-8 -cp artifacts/classes -d artifacts/classes @senderSources
    if ($LASTEXITCODE -ne 0) { throw 'Sender javac failed' }
    & $java -cp artifacts/classes com.g12.sendercore.SenderTests
    if ($LASTEXITCODE -ne 0) { throw 'Sender regression failed' }
    $directSources = @(Get-ChildItem x9-core/src -Recurse -Filter '*.java' | ForEach-Object FullName)
    & $javac --release 17 -Xlint:all -Werror -encoding UTF-8 -d artifacts/classes @directSources
    if ($LASTEXITCODE -ne 0) { throw 'X9 javac failed' }
    & $java -cp artifacts/classes com.g12.x9core.DirectTests
    if ($LASTEXITCODE -ne 0) { throw 'X9 regression failed' }
    & $java -cp artifacts/classes com.g12.x9core.ChannelTests
    if ($LASTEXITCODE -ne 0) { throw 'Raw channel regression failed' }
} finally { Pop-Location }
