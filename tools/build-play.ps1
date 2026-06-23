#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Builds the Play Feeder APK for sideloading on your Pixel.

.DESCRIPTION
    Play flavor = OpenAI-compatible API + on-device llama.cpp + ML Kit GenAI (Gemini Nano).
    This is the build to use if you also want the ML Kit on-device option.
    Installs as 'com.nononsenseapps.feeder.play.debug', separate from the F-Droid build.

.PARAMETER Install
    Install onto the connected device via adb after building.

.EXAMPLE
    .\tools\build-play.ps1

.EXAMPLE
    .\tools\build-play.ps1 -Install
#>
param(
    [switch]$Install,
    [switch]$Release
)

$ErrorActionPreference = 'Stop'
& "$PSScriptRoot\build-apk.ps1" -Flavor play -Install:$Install -Release:$Release
