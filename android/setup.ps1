<#
.SYNOPSIS
    Fetches the SDL2 source tree required by the Android build.

.DESCRIPTION
    The Android port compiles SDL2 from source (SDL2 ships no prebuilt Android
    library). That source tree is ~80 MB, so it is deliberately kept out of git
    - see /3rd/SDL/ in the repository's .gitignore.

    Run this once after cloning, before building with Gradle:

        pwsh android/setup.ps1

.PARAMETER Version
    SDL2 release to fetch. Must match the version the port was validated
    against if you want a reproducible build.
#>

param(
    [string] $Version = "2.32.8"
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot  = Split-Path -Parent $scriptDir
$thirdDir  = Join-Path $repoRoot "3rd"
$target    = Join-Path $thirdDir "SDL"

$url     = "https://libsdl.org/release/SDL2-$Version.tar.gz"
$archive = Join-Path $env:TEMP "SDL2-$Version.tar.gz"

Write-Host "SDLPoP Android setup" -ForegroundColor Cyan
Write-Host "  repo root : $repoRoot"
Write-Host "  SDL2      : $Version"
Write-Host "  target    : $target"

if (Test-Path (Join-Path $target "Android.mk")) {
    Write-Host ""
    Write-Host "SDL2 $Version is already present at $target" -ForegroundColor Yellow
    $answer = Read-Host "Re-download and overwrite? [y/N]"
    if ($answer -notmatch "^[Yy]") {
        Write-Host "Nothing to do."
        exit 0
    }
}

Write-Host ""
Write-Host "Downloading $url ..." -ForegroundColor Cyan
Invoke-WebRequest -Uri $url -OutFile $archive -UseBasicParsing
Write-Host "  saved: $archive ($([math]::Round((Get-Item $archive).Length / 1MB, 1)) MB)"

Write-Host "Extracting ..." -ForegroundColor Cyan
New-Item -ItemType Directory -Force -Path $thirdDir | Out-Null
& tar -xzf $archive -C $thirdDir
if ($LASTEXITCODE -ne 0) {
    throw "tar failed with exit code $LASTEXITCODE"
}

$extracted = Join-Path $thirdDir "SDL2-$Version"
if (-not (Test-Path $extracted)) {
    throw "Expected directory not found after extraction: $extracted"
}

if (Test-Path $target) {
    Remove-Item $target -Recurse -Force
}
Move-Item $extracted $target

# The template project is not needed; the real one lives in android/.
$template = Join-Path $target "android-project"
if (Test-Path $template) {
    Remove-Item $template -Recurse -Force
}

Remove-Item $archive -Force -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "Verifying ..." -ForegroundColor Cyan
$required = @(
    "Android.mk",
    "include/SDL.h",
    "src/core/android/SDL_android.c",
    "src/video/android/SDL_androidkeyboard.c"
)
$missing = @()
foreach ($item in $required) {
    $path = Join-Path $target $item
    if (Test-Path $path) {
        Write-Host "  OK       $item"
    } else {
        Write-Host "  MISSING  $item" -ForegroundColor Red
        $missing += $item
    }
}

if ($missing.Count -gt 0) {
    throw "SDL2 setup incomplete, $($missing.Count) required file(s) missing."
}

Write-Host ""
Write-Host "Done. Build with:" -ForegroundColor Green
Write-Host "  cd `"$scriptDir`""
Write-Host "  .\gradlew assembleDebug"
