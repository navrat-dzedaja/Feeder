#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Builds the F-Droid (fully open-source) Feeder APK for sideloading.

.DESCRIPTION
    F-Droid flavor = OpenAI-compatible API + on-device llama.cpp. No Google services.
    Installs as a separate app id from the Play build, so both can coexist on the device.

.PARAMETER Install
    Install onto the connected device via adb after building.

.EXAMPLE
    .\tools\build-fdroid.ps1

.EXAMPLE
    .\tools\build-fdroid.ps1 -Install
#>
param(
    [switch]$Install,
    [switch]$Release
)

$ErrorActionPreference = 'Stop'
& "$PSScriptRoot\build-apk.ps1" -Flavor fdroid -Install:$Install -Release:$Release
