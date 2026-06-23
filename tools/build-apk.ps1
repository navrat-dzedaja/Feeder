#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Builds a sideloadable Feeder APK for a given product flavor.

.DESCRIPTION
    Core build script used by build-fdroid.ps1 / build-play.ps1.

    Flavors:
      fdroid -> OpenAI-compatible API + on-device llama.cpp  (fully FOSS, no Google services)
      play   -> OpenAI-compatible API + on-device llama.cpp + ML Kit GenAI (Gemini Nano)

    Builds the debug variant by default (auto-signed with the debug keystore, so it installs
    without any extra setup). The first build compiles llama.cpp from source and is slow (~5 min);
    later builds are incremental.

.PARAMETER Flavor
    'fdroid' or 'play'.

.PARAMETER Install
    After building, install the APK onto the connected device with 'adb install -r'.

.PARAMETER Release
    Build the release variant instead of debug. NOTE: release signing needs the STORE_FILE /
    STORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD gradle properties; without them you get an
    unsigned APK that a phone will refuse to install. Debug (the default) is what you want for
    sideloading.

.EXAMPLE
    .\tools\build-apk.ps1 -Flavor fdroid

.EXAMPLE
    .\tools\build-apk.ps1 -Flavor play -Install
#>
param(
    [Parameter(Mandatory)]
    [ValidateSet('fdroid', 'play')]
    [string]$Flavor,

    [switch]$Install,

    [switch]$Release
)

$ErrorActionPreference = 'Stop'

$repo = (Resolve-Path "$PSScriptRoot\..").Path
$buildType = if ($Release) { 'Release' } else { 'Debug' }
$flavorTitle = $Flavor.Substring(0, 1).ToUpper() + $Flavor.Substring(1)
$task = "assemble$flavorTitle$buildType"

# Make sure the llama.cpp submodule is present, otherwise the native build can't run.
if (-not (Test-Path "$repo\app\src\main\cpp\llama.cpp\CMakeLists.txt")) {
    Write-Host "==> Fetching the llama.cpp submodule (first run only)..." -ForegroundColor Cyan
    & git -C $repo submodule update --init --depth 1 "app/src/main/cpp/llama.cpp"
    if ($LASTEXITCODE -ne 0) { throw "Failed to init the llama.cpp submodule." }
}

Write-Host "==> Building $Flavor$buildType  (./gradlew :app:$task)" -ForegroundColor Cyan
Push-Location $repo
try {
    & "$repo\gradlew.bat" ":app:$task" --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed (exit $LASTEXITCODE)." }
} finally {
    Pop-Location
}

$apkDir = Join-Path $repo "app\build\outputs\apk\$Flavor\$($buildType.ToLower())"
$apk = Get-ChildItem -Path $apkDir -Filter '*.apk' -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $apk) { throw "Build succeeded but no APK was found in $apkDir" }

Write-Host ""
Write-Host "APK ready:" -ForegroundColor Green
Write-Host "  $($apk.FullName)"
Write-Host ("  size: {0:N1} MB" -f ($apk.Length / 1MB))

if ($Install) {
    $adbCmd = Get-Command adb -ErrorAction SilentlyContinue
    $adb = if ($adbCmd) { $adbCmd.Source } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe' }
    if (-not (Test-Path $adb)) {
        throw "adb not found. Add platform-tools to PATH, or install it via the SDK manager."
    }

    Write-Host ""
    Write-Host "==> Installing onto the connected device via adb..." -ForegroundColor Cyan
    & $adb install -r "$($apk.FullName)"
    if ($LASTEXITCODE -ne 0) { throw "adb install failed (exit $LASTEXITCODE). Is the Pixel connected with USB/wireless debugging on?" }
    Write-Host "Installed." -ForegroundColor Green
} else {
    Write-Host ""
    Write-Host "Sideload it with:  adb install -r `"$($apk.FullName)`"" -ForegroundColor DarkGray
    Write-Host "or re-run this script with -Install to do that automatically." -ForegroundColor DarkGray
}
