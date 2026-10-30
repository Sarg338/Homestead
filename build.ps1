#
# Homestead - build, sign and (optionally) install, for Windows PowerShell 5.1 or later.
# The same steps as build.sh (aapt2/javac/d8/apksigner, no Gradle), without Git Bash.
# Keep the two in step: tools/test-builds.sh checks that they make the same APK.
#
#   powershell -ExecutionPolicy Bypass -File build.ps1            build + sign
#   powershell -ExecutionPolicy Bypass -File build.ps1 install    also adb install -r to $env:ADB_TARGET
#
# Tools are found like build.sh does: ANDROID_SDK_ROOT / ANDROID_HOME, else the usual SDK
# folder; ANDROID_BUILD_TOOLS and ANDROID_JAR override the defaults; JAVA_HOME, else the
# java on PATH.
# Signing key (not in this repository): HOMESTEAD_KEYSTORE / HOMESTEAD_KEYPASS, or
# KEY-INFO.txt in $env:HOMESTEAD_KEYDIR (default ~\.homestead-keys).
param([string]$Action = '')

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

function Or($a, $b) { if ($a) { $a } else { $b } }

$SDK = Or (Or $env:ANDROID_SDK_ROOT $env:ANDROID_HOME) "$env:LOCALAPPDATA\Android\Sdk"
$BT = Or $env:ANDROID_BUILD_TOOLS "$SDK\build-tools\36.1.0"
$ANDROID_JAR = Or $env:ANDROID_JAR "$SDK\platforms\android-34\android.jar"
$KEYDIR = Or $env:HOMESTEAD_KEYDIR "$env:USERPROFILE\.homestead-keys"
$KEYSTORE = Or $env:HOMESTEAD_KEYSTORE "$KEYDIR\homestead-release.keystore"
$KEYALIAS = Or $env:HOMESTEAD_KEYALIAS 'homestead'
$ADB = Or $env:ADB "$SDK\platform-tools\adb.exe"
$ADB_TARGET = $env:ADB_TARGET

$MIN_SDK = 22       # Fire OS 5 (Android 5.1). TYPE_ACCESSIBILITY_OVERLAY exists from 22.
$TARGET_SDK = 28    # Fire OS 7 (Android 9). Not raised: see build.sh.

if ($env:JAVA_HOME) { $env:PATH = "$env:JAVA_HOME\bin;$env:PATH" }
foreach ($t in 'java', 'javac') {
    if (-not (Get-Command $t -ErrorAction SilentlyContinue)) { throw "$t not found. Install a JDK 11+ or set JAVA_HOME." }
}
if (-not (Test-Path "$BT\aapt2.exe")) { throw "No build-tools at $BT. Set ANDROID_SDK_ROOT or ANDROID_BUILD_TOOLS." }

# Native tools write progress to stderr; only the exit code means failure.
function Run([string]$exe, [string[]]$argv) {
    & $exe @argv
    if ($LASTEXITCODE -ne 0) { throw "$([IO.Path]::GetFileName($exe)) failed ($LASTEXITCODE)" }
}

$KEYPASS = $env:HOMESTEAD_KEYPASS
if (-not $KEYPASS) {
    $info = "$KEYDIR\KEY-INFO.txt"
    if (-not (Test-Path $info)) { throw "No signing key. Set HOMESTEAD_KEYPASS, or see the README (Building)." }
    $line = Get-Content $info | Where-Object { $_ -match '^Password:' } | Select-Object -First 1
    $KEYPASS = ($line -replace '^Password:\s*', '').Trim().Split(' ')[0]
}

Write-Host '==> clean'
foreach ($p in 'classes', 'dex', 'gen', 'app-unsigned.apk', 'app-with-dex.apk', 'app-aligned.apk', 'compiled.flata') {
    if (Test-Path $p) { Remove-Item -Recurse -Force $p }
}
New-Item -ItemType Directory -Force classes, dex, 'res\drawable-nodpi' | Out-Null

Write-Host '==> artwork'
Run 'java' @('tools\MakeArt.java', 'res\drawable-nodpi') | Out-Null
Run 'java' @('tools\MakeLandscapes.java', 'assets') | Out-Null

Write-Host '==> aapt2 compile'
Run "$BT\aapt2.exe" @('compile', '--dir', 'res', '-o', 'compiled.flata')

Write-Host '==> aapt2 link'
Run "$BT\aapt2.exe" @('link', '-o', 'app-unsigned.apk', '-I', $ANDROID_JAR,
    '--manifest', 'AndroidManifest.xml', '-R', 'compiled.flata',
    '--min-sdk-version', "$MIN_SDK", '--target-sdk-version', "$TARGET_SDK",
    '-A', 'assets', '--java', 'gen', '--auto-add-overlay')

Write-Host '==> javac'
Get-ChildItem -Recurse src, gen -Filter *.java | ForEach-Object { '"' + ($_.FullName -replace '\\', '/') + '"' } |
    Set-Content -Encoding ascii sources.txt
Run 'javac' @('--release', '11', '-nowarn', '-encoding', 'UTF-8',
    '-classpath', $ANDROID_JAR, '-d', 'classes', '@sources.txt')
Remove-Item sources.txt

Write-Host '==> d8'
Get-ChildItem -Recurse classes -Filter *.class | ForEach-Object { $_.FullName } | Set-Content -Encoding ascii classes.txt
Run "$BT\d8.bat" @('--lib', $ANDROID_JAR, '--min-api', "$MIN_SDK", '--release',
    '--output', "$PWD\dex", '@classes.txt')
Remove-Item classes.txt

Write-Host '==> package dex'
Copy-Item app-unsigned.apk app-with-dex.apk
Push-Location dex
try { Run "$BT\aapt.exe" @('add', '..\app-with-dex.apk', 'classes.dex') | Out-Null } finally { Pop-Location }

Write-Host '==> zipalign'
Run "$BT\zipalign.exe" @('-f', '-p', '4', 'app-with-dex.apk', 'app-aligned.apk')

Write-Host '==> sign'
Run "$BT\apksigner.bat" @('sign', '--ks', $KEYSTORE, '--ks-key-alias', $KEYALIAS,
    '--ks-pass', "pass:$KEYPASS", '--key-pass', "pass:$KEYPASS",
    '--min-sdk-version', "$MIN_SDK", '--out', 'homestead.apk', 'app-aligned.apk')
Run "$BT\apksigner.bat" @('verify', '--min-sdk-version', "$MIN_SDK", 'homestead.apk')

$manifest = Get-Content -Raw AndroidManifest.xml
$vc = [regex]::Match($manifest, 'versionCode="(\d+)"').Groups[1].Value
$vn = [regex]::Match($manifest, 'versionName="([^"]*)"').Groups[1].Value
Write-Host "==> homestead.apk  v$vn ($vc)  $((Get-Item homestead.apk).Length) bytes"

if ($Action -eq 'install') {
    if (-not $ADB_TARGET) { throw 'Set $env:ADB_TARGET = "<tv-ip>:5555" for install.' }
    Write-Host "==> install to $ADB_TARGET"
    Run $ADB @('-s', $ADB_TARGET, 'install', '-r', '-d', 'homestead.apk')
}
