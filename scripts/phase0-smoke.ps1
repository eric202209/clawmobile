param(
    [string]$PackageName = "com.user",
    [string]$ActivityName = "com.user.ui.activities.MainActivity",
    [string]$ApkPath = "app/build/outputs/apk/debug/app-debug.apk"
)

$ErrorActionPreference = "Stop"

function Require-Command {
    param([string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command not found: $Name"
    }
}

function Require-Device {
    $devices = adb devices | Select-String "`tdevice$"
    if (-not $devices) {
        throw "No Android device or emulator is attached. Start an emulator or connect a device, then rerun this script."
    }
}

Require-Command "adb"
Require-Device

if (-not (Test-Path $ApkPath)) {
    Write-Host "Debug APK missing. Building app-debug.apk..."
    .\gradlew.bat assembleDebug
}

if (-not (Test-Path $ApkPath)) {
    throw "Debug APK still not found at $ApkPath"
}

Write-Host "Uninstalling previous app data, if present..."
adb uninstall $PackageName | Out-Null

Write-Host "Installing fresh debug APK..."
adb install -r $ApkPath | Out-Null

Write-Host "Launching app..."
adb shell am start -n "$PackageName/$ActivityName" | Out-Null

Write-Host ""
Write-Host "Phase 0 manual smoke checklist"
Write-Host "1. Complete onboarding if shown."
Write-Host "2. Open Settings."
Write-Host "3. Enter real Gateway Host, Port, Token, and HTTPS mode."
Write-Host "4. Tap Test Gateway and confirm inline success."
Write-Host "5. Return to chat and send a real message."
Write-Host "6. Enable airplane mode and confirm the offline banner appears without a crash."
Write-Host ""
Write-Host "Helpful adb commands:"
Write-Host "  adb shell cmd connectivity airplane-mode enable"
Write-Host "  adb shell cmd connectivity airplane-mode disable"
Write-Host ""
Write-Host "When all six steps pass, Phase 0's fresh-install manual smoke test is complete."
