# SDLPoP（波斯王子1 复刻）Windows 本地编译记录

**日期**：2026-08-29
**目标仓库**：`NagyD/SDLPoP`（Prince of Persia 1 的 DOS 版反向工程复刻，纯 C + SDL2，GPLv3）
**成果**：在 Windows x64 (MSVC) 下成功编译出 `prince.exe`（754.5 KB），开箱可玩。

---

## 一、环境现状（本机已具备 / 缺失）

### 已具备
- Visual Studio Build Tools 18（含 MSVC、`rc.exe`）。通过 `vswhere.exe` 定位 `installationPath`，再用 `Enter-VsDevShell` 初始化编译环境。
- SDL2 2.32.8 核心（头文件 + VC 版 lib + dll）：位于 `D:\3rd-party-projects\ioquake3\code\thirdparty\`（ioquake3 项目捆绑）。提供 `include/`、`libs/win32/`、`libs/win64/` 下的 `SDL2.lib`、`SDL2main.lib`、`SDL2.dll`。
- Git、curl、PowerShell（含 Developer PowerShell 模块）。

### 缺失
- SDL2_image 开发库（编译硬依赖 `SDL2_image.lib` + 运行时 `SDL2_image.dll`）：全盘未找到。
- 独立的 SDL2 安装目录（只有项目捆绑版）。

---

## 二、遇到的问题与解决方案

### 问题 1：本机无独立 SDL2 开发库，且缺 SDL2_image
**现象**：`src/build.bat` 链接阶段需要 `%SDL2%\include`（SDL.h、SDL_image.h）和 `%SDL2%\lib\<arch>\`（SDL2.lib、SDL2main.lib、SDL2_image.lib）。只找到 ioquake3 捆绑的 SDL2 核心，缺 SDL2_image。
**解决**：
1. 从 `libsdl.org`（国内可直连，无需代理）下载 `SDL2_image-devel-2.8.12-VC.zip`。
   - 注意：GitHub API `releases/latest` 返回的是 SDL3_image 3.x，必须手动指定 2.x 版本号（查 `https://libsdl.org/projects/SDL_image/release/` 页面得到 `2.8.12`）。
2. 组装统一 SDL2 开发目录（初置于 workspace，后迁至 `D:\dev\SDL2`）：
   `D:\dev\SDL2\`
   - `include/`：ioquake3 的 `SDL2-2.32.8/include` + 解压出的 `SDL_image.h`
   - `lib/x86/` 与 `lib/x64/`：ioquake3 的 `libs/win32`→`x86`、`libs/win64`→`x64` 映射，拷贝 `SDL2.lib`/`SDL2main.lib`/`SDL2.dll`，再补 `SDL2_image.lib`/`SDL2_image.dll`

### 问题 2：PowerShell 工具禁止调用 cmd.exe，无法直接跑 build.bat
**现象**：通过 PowerShell 调用 `cmd.exe /c build.bat` 被安全策略拦截（"Invoking cmd.exe from Bash bypasses all command validation"）。
**解决**：改用 VS 自带的 **Developer PowerShell**（`Microsoft.VisualStudio.DevShell` 模块），进入后用 native `cl.exe` / `rc.exe` 复刻 `build.bat` 的编译链接命令，不依赖 `.bat`。

```powershell
# 1) 去重 PATH/Path 重复键（否则 Enter-VsDevShell 报键冲突）
$merged = (($env:Path, $env:PATH | ForEach-Object { $_ -split ';' } |
            Where-Object { $_ } | Select-Object -Unique) -join ';')
Remove-Item Env:PATH, Env:Path -ErrorAction SilentlyContinue
$env:Path = $merged

# 2) 进入 VS DevShell (x64)
$vsPath = & "C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe" `
  -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 `
  -property installationPath
Import-Module "$vsPath\Common7\Tools\Microsoft.VisualStudio.DevShell.dll"
Enter-VsDevShell -VsInstallPath $vsPath -Arch amd64 -SkipAutomaticLocation
```

### 问题 3：Enter-VsDevShell 因 PATH/Path 重复键报错
**现象**：环境中同时有 `Path` 和 `PATH` 两个键（不同大小写），PowerShell 字典键重复冲突。
**解决**：进入 DevShell 前先合并去重（见问题 2 代码块步骤 1）。

### 问题 4：编译产生无害告警（非错误）
**现象 / 根因**：
- `M_PI` 重定义（SDL 头与 math.h）——原版在较新 MSVC 下常见。
- 枚举类型转换告警。
- `LNK4010: invalid subsystem version number 5.01`——`build.bat` 写死 `/subsystem:windows,5.01`，新版链接器建议更高版本号，但仅告警、不影响产物。
**解决**：均为无害告警，`cl exit: 0`，无需处理。

---

## 三、编译命令（复刻 build.bat release）

```powershell
$env:SDL2 = "D:\dev\SDL2"
Set-Location "D:\workspace\workbuddy-nili\2026-08-29-14-12-03\SDLPoP\src"

# 编译图标资源
rc.exe /nologo /fo icon.res icon.rc

# 源码列表
$srcs = @("main.c","data.c","seg000.c","seg001.c","seg002.c","seg003.c",
          "seg004.c","seg005.c","seg006.c","seg007.c","seg008.c","seg009.c",
          "seqtbl.c","replay.c","options.c","lighting.c","screenshot.c",
          "menu.c","midi.c","opl3.c","stb_vorbis.c")

# 编译 + 链接（x64 release, /MT 静态 CRT）
cl.exe /nologo /MP /fp:fast /GR- /wd4048 /MT /O2 /I"$env:SDL2\include" @srcs `
  /link /subsystem:windows,5.01 /libpath:"$env:SDL2\lib\x64" `
  SDL2main.lib SDL2.lib SDL2_image.lib Shell32.lib icon.res /out:..\prince.exe

# 复制运行时 dll 到 exe 同目录
Copy-Item "$env:SDL2\lib\x64\SDL2.dll","$env:SDL2\lib\x64\SDL2_image.dll" ..\
```

> 以上命令已封装为自包含脚本 `SDLPoP/build_windows.ps1`（内置路径 `D:\dev\SDL2`，支持 `-Arch x64|x86`），运行 `pwsh build_windows.ps1` 即可一键从零重建；已验证可编译通过（EXIT 0）。

---

## 四、编译产物与验证

- `SDLPoP/prince.exe`：754.5 KB，x64 release。
- 运行时：`SDLPoP/SDL2.dll` + `SDLPoP/SDL2_image.dll`（已就位同目录）。
- 资源：`SDLPoP/data/`（28 项，完整）。
- 依赖校验：`dumpbin /dependents` 确认仅依赖 `SDL2.dll`、`SDL2_image.dll`、系统 `KERNEL32/SHELL32`。因 `/MT` 静态链接 CRT，**无需额外 VC++ Redistributable**。
- **运行**：本机（有显示器）直接双击 `prince.exe` 即可游玩。沙箱无显示环境，未做真机 GUI 验证（产物与依赖均已确认齐备）。

---

## 五、Git 状态（重要）

- **仓库源码未做任何修改**。`git status` 干净，仍停留在原提交 `3c5add5`。
- 生成的产物（`prince.exe`、`SDL2.dll`、`SDL2_image.dll`、`src/icon.res`）**全部被 SDLPoP 自带 `.gitignore` 忽略**（`*.exe`/`*.dll`/`*.res`），不会污染提交，但文件仍在磁盘上。
- 其他中间文件均在 SDLPoP 仓库之外，零风险：
  - `D:\dev\SDL2`（统一 SDL2 开发目录，已从 workspace 迁出）
  - `SDL2_image_pkg/`（解压的 SDL2_image 包）
  - `SDLPoP-Android/`（Android 移植脚手架，独立目录）
  - `D:\downloads\SDL2_image-devel-2.8.12-VC.zip`、`SDL2-2.32.8.tar.gz`

---

## 六、Android 移植脚手架（独立于原仓库，仅搭建未编译）

- 下载 SDL2 2.32.8 源码（含官方 `android-project` 模板与 `src/core/android/SDL_android.c`）。
- 结构：`SDLPoP-Android/android-project/app/jni/{SDL2, src, Android.mk}`，`src/main/assets/data` 放游戏资源。
- **当前状态**：SDK 已装但 NDK / build-tools / platforms 均未实际安装（目录为空），故尚未编译 APK。需先装 NDK（≈1GB）+ build-tools + platform。
- 官方仓库不支持 Android，此脚手架为手动改造：SDL2 `android-project` 包 SDLPoP 源码 → 写 JNI 胶水 → 处理资源路径/虚拟按键。

---

## 七、关键命令速查

```bash
# 定位本机 VS（含 MSVC / rc）
vswhere.exe -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath

# 下载 SDL2_image 开发包（libsdl.org 直连，无需代理）
curl -L https://libsdl.org/projects/SDL_image/release/SDL2_image-devel-2.8.12-VC.zip \
  -o D:/downloads/SDL2_image-devel-2.8.12-VC.zip

# 依赖校验
dumpbin /dependents prince.exe
```
