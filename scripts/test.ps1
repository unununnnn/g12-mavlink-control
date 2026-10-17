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
} finally { Pop-Location }
