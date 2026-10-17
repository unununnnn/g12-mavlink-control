param([Parameter(Mandatory=$true)][string]$SdkAar)
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK17+ installation' }
    $sdkPath = (Resolve-Path -LiteralPath $SdkAar).Path
    $hash = (Get-FileHash -LiteralPath $sdkPath -Algorithm SHA256).Hash
    if ($hash -ne 'E5977BA3292246BCEE026DEFDB8563A18ADC9D5573FDFB20A6880EE469F0FDE2') { throw 'Unverified RCSDK AAR version/hash' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    New-Item -ItemType Directory -Force artifacts/sdk-signatures | Out-Null
    $jarPath = Join-Path (Get-Location) 'artifacts/sdk-signatures/classes.jar'
    $zip = [System.IO.Compression.ZipFile]::OpenRead($sdkPath)
    try { [System.IO.Compression.ZipFileExtensions]::ExtractToFile($zip.GetEntry('classes.jar'), $jarPath, $true) } finally { $zip.Dispose() }
    & (Join-Path $env:JAVA_HOME 'bin/javac.exe') --release 17 -encoding UTF-8 -cp $jarPath -d artifacts/sdk-signatures/classes sdk-probe/src/main/java/com/g12/sdk/ReadOnlyChannels.java
    if ($LASTEXITCODE -ne 0) { throw 'SDK signature compile failed' }
    Write-Output 'PASS: original read-only probe compiles against the pinned RCSDK1.9.2 classes. SDK not executed; no hardware validation.'
} finally { Pop-Location }
