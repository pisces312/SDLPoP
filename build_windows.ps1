# build_windows.ps1 - Build SDLPoP (Prince of Persia 1) on Windows with MSVC
#
# Prerequisites:
#   - Visual Studio Build Tools (MSVC, cl/rc) reachable via vswhere
#   - Unified SDL2 dev SDK (SDL2 core + SDL2_image) at $SDL2 below
#
# Usage:
#   pwsh build_windows.ps1            # x64 Release (default)
#   pwsh build_windows.ps1 -Arch x86  # 32-bit
#
# Note: this script uses the VS Developer PowerShell (Enter-VsDevShell) instead
# of calling build.bat through cmd.exe, so it works in environments where
# cmd.exe is unavailable.

param(
    [ValidateSet('x64','x86')] [string] $Arch = 'x64'
)

$ErrorActionPreference = 'Stop'

# ---------- 1) SDL2 unified dev SDK path (edit here if relocated) ----------
$SDL2 = 'D:\dev\SDL2'
if (-not (Test-Path "$SDL2\include\SDL.h")) {
    throw "SDL2 dev SDK not found at $SDL2. Update the `$SDL2 variable."
}

# ---------- 2) Resolve project directories relative to this script ----------
$root = Split-Path -Parent $MyInvocation.MyCommand.Definition   # SDLPoP/
$src  = Join-Path $root 'src'

# ---------- 3) Enter VS Developer PowerShell (no cmd.exe required) ----------
# De-dup PATH/Path duplicate keys that break Enter-VsDevShell on some systems.
try {
    $p = @()
    if ($env:Path) { $p += ($env:Path -split ';') }
    if ($env:PATH) { $p += ($env:PATH -split ';') }
    $merged = (($p | Where-Object { $_ } | Select-Object -Unique) -join ';')
    Remove-Item Env:PATH -ErrorAction SilentlyContinue
    Remove-Item Env:Path -ErrorAction SilentlyContinue
    $env:Path = $merged
} catch {}

$vswhere = 'C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe'
$vsPath = & $vswhere -latest -products * `
    -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 `
    -property installationPath 2>$null
if (-not $vsPath) { throw 'Visual Studio / MSVC not found via vswhere.' }
Import-Module (Join-Path $vsPath 'Common7\Tools\Microsoft.VisualStudio.DevShell.dll')
Enter-VsDevShell -VsInstallPath $vsPath -Arch $Arch -SkipAutomaticLocation

# ---------- 4) Compile + link ----------
Set-Location $src
$env:SDL2 = $SDL2

$srcs = @(
    'main.c','data.c',
    'seg000.c','seg001.c','seg002.c','seg003.c','seg004.c',
    'seg005.c','seg006.c','seg007.c','seg008.c','seg009.c',
    'seqtbl.c','replay.c','options.c','lighting.c',
    'screenshot.c','menu.c','midi.c','opl3.c','stb_vorbis.c'
)

Write-Host "=== rc (icon) ==="
rc.exe /nologo /fo icon.res icon.rc
if ($LASTEXITCODE -ne 0) { throw "rc failed (exit $LASTEXITCODE)" }

$cf = @('/nologo','/MP','/fp:fast','/GR-','/wd4048','/MT','/O2',"/I$SDL2\include")
$lf = @("/subsystem:windows,5.01","/libpath:$SDL2\lib\$Arch",
        'SDL2main.lib','SDL2.lib','SDL2_image.lib','Shell32.lib',
        'icon.res',"/out:..\prince.exe")

Write-Host "=== cl compile+link ($Arch) ==="
cl.exe @cf @srcs /link @lf
if ($LASTEXITCODE -ne 0) { throw "cl failed (exit $LASTEXITCODE)" }

# ---------- 5) Copy runtime DLLs next to the exe ----------
Copy-Item "$SDL2\lib\$Arch\SDL2.dll","$SDL2\lib\$Arch\SDL2_image.dll" $root -Force

# ---------- 6) Report ----------
$e = Get-Item (Join-Path $root 'prince.exe')
Write-Host "BUILD OK -> $($e.FullName) ($([math]::Round($e.Length/1KB,1)) KB)"
Write-Host "Runtime DLLs: SDL2.dll, SDL2_image.dll (copied to $(Split-Path $e.FullName))"
