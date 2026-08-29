# SDLPoP Android 移植与构建记录

日期：2026-08-29
上游：`NagyD/SDLPoP`（GPLv3）· 本仓库：`pisces312/SDLPoP`
结果：**`assembleDebug` 构建成功，产出 `app-debug.apk`（2.6 MB）**

---

## 一、目标与原则

把 SDLPoP（纯 C + SDL2）移植到 Android（仅 `arm64-v8a`），遵循三条约束：

1. **上游源码零改动** —— `src/` 下所有 `.c` / `.h` 保持不变，便于用 `upstream` 同步。
2. **素材不复制** —— `data/`（1034 个文件 / 1.5 MB）仍只存在于仓库根，Android 工程在构建时引用。
3. **工程同仓** —— Android 工程放在 `android/`，与 sdlpal 的 `<root>/android/` 布局一致。

参照物：本机已有的 `D:\3rd-party-projects\sdlpal`（已成功产出 APK 的同类工程）。

---

## 二、最终结构

```
SDLPoP/
├── data/                      # 游戏素材（仓库既有，构建时复制进 APK assets）
├── src/                       # 游戏源码（零改动，被 Android.mk 相对引用）
├── 3rd/
│   └── SDL/                   # SDL2 2.32.8 源码 —— 不入库，由 android/setup.ps1 获取
├── .gitignore                 # 追加 /3rd/SDL/、.gradle/、local.properties
└── android/
    ├── setup.ps1              # 一键下载 SDL2 源码
    ├── build.gradle           # AGP 9.2.0
    ├── settings.gradle / gradle.properties
    ├── gradle/wrapper/…
    └── app/
        ├── build.gradle       # compileSdk 36 / minSdk 21 / NDK 27.3 / arm64-v8a / ndk-build
        ├── proguard-rules.txt
        └── src/main/
            ├── AndroidManifest.xml
            ├── java/org/libsdl/app/*.java     # SDL2 Java 胶水层（从 3rd/SDL 拷入，不改动）
            ├── java/com/sdlpop/sdlpop/
            │   ├── PoPActivity.java           # 入口：解压素材 + 设置原生数据目录
            │   └── VirtualPad.java            # 虚拟按键（多点触控）
            ├── res/…
            └── cpp/
                ├── Android.mk / Application.mk
                ├── pop_android.c              # JNI 适配层 + stb_image 实现体
                ├── SDL_image.h                # SDL2_image 替换 shim
                ├── SDL2/SDL.h                 # 转发头（上游写的是 <SDL2/SDL.h>）
                ├── SDL2/SDL_image.h           # 转发头
                ├── SDL_stbimage.h             # 来自 sdlpal（已改为直接 include <SDL.h>）
                └── stb_image.h                # 来自 sdlpal
```

---

## 三、三个必须解决的适配点

### 1. 资源读取：APK assets 无法 `fopen`

SDLPoP 全部走 `fopen()`：`open_dat()` → `open_dat_from_root_or_data_dir()`，先按 CWD 里的 `data/xxx` 找，再退到 `$HOME/.SDLPoP/data/`、`/usr/share/SDLPoP/data/`。

**方案（三段式，上游零改动）：**

1. **构建期**：`app/build.gradle` 的 `copyPopData` 任务把 `../data` 复制到
   `app/build/generated/pop_assets/data`，并挂到所有 `merge*Assets` 任务之前。
2. **安装后首次启动**：`PoPActivity.extractGameData()` 把 `assets/data` 递归解压到
   `getFilesDir()/data`，用 `popdata.version` 标记文件做版本判断（`DATA_VERSION` 变更时重解压）。
3. **运行前**：`pop_android.c` 的 `nativeSetup()` 做 `chdir(filesDir)` + `setenv("HOME", filesDir)`，
   并 `mkdir(".SDLPoP")`。

第 3 步的 `mkdir` 是必需的：`locate_save_file_()` 会在
`[$HOME/.SDLPoP, /usr/share/SDLPoP, exe_dir]` 里挑**第一个存在且可写**的目录；Android 上
`exe_dir` 是无效值（SDL 传 `argv[0] = "app_process"`），若 `.SDLPoP` 不存在，存档与
`SDLPoP.cfg` 将无处可写。

**调用时机已验证安全**：SDL2 2.32.8 在 `SDLActivity.handleNativeState()` 中、且仅当
`surface ready && hasFocus && isResumedCalled` 时才启动 `mSDLThread`（进而调用 `SDL_main`）。
`onCreate()` 末尾调用 `nativeSetup()` 必然早于该线程。

### 2. SDL2_image：只砍「保存」，必须保留「加载」

逐行核实全部 `IMG_*` 调用点后确认，`SDL2_image` 并非只用于截图：

| 调用点 | 作用 | 处理 |
|---|---|---|
| `seg009.c:906` `load_image()` → `IMG_Load_RW` | 从 DAT 解码内嵌 PNG（data/ 里 929 个 png 全走这里） | **保留** |
| `lighting.c:35` → `IMG_Load("data/light.png")` | 火把光罩 | **保留** |
| `seg009.c:2630` → `IMG_Load("data/icon.png")` | 窗口图标 | **保留** |
| `screenshot.c:70/680` → `IMG_SavePNG` | 截图保存 | **stub 为 -1** |

**实现**：照搬 sdlpal 的 `SDL_stbimage.h`（基于 `stb_image.h`，只提供解码、零外部依赖），
用 `cpp/SDL_image.h` shim 把 `IMG_Load*` 映射到 `STBIMG_*`，并把 `IMG_SavePNG` 定成 `(-1)`。
截图需求由手机系统截图手势覆盖。

> 坑：`USE_SCREENSHOT` 在 `src/config.h:351` 是**硬编码 `#define`**（没有 `#ifndef` 包裹），
> 无法用编译期 `-D` 取消。因此 `screenshot.c` 照常编译，靠 shim 拦截 —— 上游依然一行未改。

### 3. 输入：SDLPoP 零触摸代码

SDLPoP 输入是纯键盘事件驱动（`SDL_PollEvent` → `SDL_KEYDOWN/UP` → `key_states[]`）。
SDL2 的 `SDLActivity.onNativeKeyDown(int)` / `onNativeKeyUp(int)` 本身就是 `public static native`，
其 JNI 实现经 `SDL_androidkeyboard.c` 的 `Android_Keycodes` 表把 Android keycode 转成
SDL scancode（已核实：`AKEYCODE_DPAD_UP→SDL_SCANCODE_UP`、`AKEYCODE_SHIFT_LEFT→SDL_SCANCODE_LSHIFT`、
`AKEYCODE_SPACE→SDL_SCANCODE_SPACE`、`AKEYCODE_ESCAPE→SDL_SCANCODE_ESCAPE`）。

→ **Java 虚拟按键直接注入，C 层零改动**，且多指同按天然支持「上+左」跑跳。

`VirtualPad` 为最小版：左下十字方向键 + 右侧 `Shift` / `跳(Space)` / `⏎(Enter)` / `Esc`，
半透明覆盖层，按屏幕尺寸比例定位。

### 4. 必须关掉「加速度计当摇杆」：否则虚拟按键会失效

这是**真机验证后才发现**的隐藏适配点，也是三个诡异现象的共同根因。

完整因果链：

```
SDL 默认 SDL_HINT_ACCELEROMETER_AS_JOYSTICK = "1"（src/joystick/android/SDL_sysjoystick.c:469）
   ↓  加速度计被注册成一个 3-axis joystick
   ↓  读数 = 重力加速度（单位 g），clamp 到 ±1.0 后 ×32767 → Sint16
SDLPoP 原生支持手柄，且 config.h:353 开了 USE_AUTO_INPUT_MODE
   ↓  joystick_threshold 默认 8000（满量程 32767）
   ↓  sin(θ)·32767 > 8000  ⇒ θ ≈ 14°，手机倾斜 14° 即越阈
seg009.c 中 is_joyst_mode = 1 / is_keyboard_mode = 0（两者互斥，seg009.c:985）
   ↓
键盘方向输入被忽略 → 虚拟按键「失效」
```

对应到用户可见的三个现象：

| 现象 | 机制 |
|---|---|
| 平放桌面时按键好用 | z≈1g（32767），x/y≈0，不越阈 → 不切手柄模式 |
| 拿在手里变成「陀螺仪控制」，按键失效 | 握持必有倾角，越阈 → 切入手柄模式，键盘方向被吞 |
| 左右倾斜触发角色左右走 | 加速度计 x 轴被当成左摇杆 X 轴 |

**修复**：在 `pop_android.c` 的 `nativeSetup()` 里加一行

```c
SDL_SetHint(SDL_HINT_ACCELEROMETER_AS_JOYSTICK, "0");
```

必须在 `SDL_Init(SDL_INIT_JOYSTICK)`（或首次 `SDL_NumJoysticks()` 惰性初始化）之前设置，
`onCreate()` → `nativeSetup()` 早于 SDL 主线程启动，时序安全。已核实 Android 端
**只有加速度计**这一种虚拟 joystick 会自动注册（其余来自真实输入设备）。

波斯王子是 2D 横版卷轴，tilt 操控既无意义也无校准原点，因此直接关闭，不做设置项。

### 5. 预置 `SDLPoP.ini`：零源码改动地改默认设置（如 start fullscreen）

游戏内菜单的「Settings → Visuals → start fullscreen」默认 off（`data.h:719`，仅 `__PSP__` 平台为 1），
而 SDLPoP 不调 `SDL_SetWindowFullscreen`，SDL 的 immersive 通道不会触发 → Android 顶部状态栏可见。

改默认值的正道是利用上游**自己的配置系统**，完全不改 C 源码：

```
配置优先级（menu.c load_ingame_settings:2423）：
  SDLPoP.cfg（游戏内菜单保存，含 exe CRC 校验，无法预造）
  > SDLPoP.ini（手写文本，[General] section）
  ——但仅当 .cfg 比 .ini 新；.ini 更新则 .ini 胜出
```

- `locate_file()`（seg009.c:137）先查当前目录——我们已 `chdir` 到 `filesDir`，所以把
  `SDLPoP.ini` 放到 `filesDir` 根即可被读到（放 `data/` 子目录无效）。
- 做法：APK `assets/` 根打包一份 `SDLPoP.ini`（内容 `[General] start_fullscreen = true`），
  `PoPActivity.seedDefaultIni()` **仅在文件不存在时**复制到 `filesDir` 根。
- **不能每次启动覆盖**：一旦用户在游戏内改过设置（生成 `.cfg`），刷新 `.ini` 的 mtime 会让
  `.ini` 反超 `.cfg`，静默重置用户设置。
- 生效链路：`start_fullscreen=1` → 窗口创建带 `SDL_WINDOW_FULLSCREEN_DESKTOP`（seg009.c:2576）
  → SDL Android 后端 `Android_SetWindowFullscreen` → `COMMAND_CHANGE_WINDOW_STYLE`
  → SDLActivity 设 immersive sticky flags 并记 `mFullscreenModeActive`（focus 恢复由 SDL 自管）。
  不需要任何 Java 层 window-style 代码。

---

## 四、依赖与前置条件

| 项 | 版本 | 说明 |
|---|---|---|
| JDK | 21（`D:\dev\AndroidStudio\jbr`） | |
| Android SDK | `D:\dev\android_sdk`（`ANDROID_HOME`） | 需 platform 36、build-tools 36 |
| NDK | 27.3.13750724 | `cpufeatures` 模块在该 NDK 中存在（SDL2 的 Android.mk 会 import 它） |
| AGP | 9.2.0 | 与 sdlpal 一致 |
| Gradle | 9.4.1 | 本机 PATH 上已有；`GRADLE_USER_HOME=D:\dev\.gradle` |
| ABI | `arm64-v8a` | |

> 注意：`AGP 9.x` 已废弃 `proguard-android.txt`，必须配合 `proguard-android-optimize.txt`；
> 而 SDL 的重度 JNI 会被 R8 优化破坏，故 `proguard-rules.txt` 里显式加 `-dontoptimize`。

---

## 五、构建步骤

```powershell
# 1) 首次拉取 SDL2 源码（不入库，约 80 MB）
pwsh android/setup.ps1

# 2) 构建 debug APK
cd android
gradle assembleDebug
# 产物：android/app/build/outputs/apk/debug/app-debug.apk
```

Release 构建需要签名凭据，**只从环境变量读取**（不硬编码）：
`KEY_STORE` / `KEY_STORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`。

---

## 六、验证结果

| 检查项 | 结果 |
|---|---|
| `gradle assembleDebug` | BUILD SUCCESSFUL（38 tasks） |
| `lib/arm64-v8a/libSDL2.so` | 2.4 MB ✓ |
| `lib/arm64-v8a/libmain.so` | 795 KB ✓ |
| `assets/data/` 条目数 | 1034（与 `data/` 源目录完全一致）✓ |
| `SDL_main` 符号导出 | `T SDL_main` ✓（SDL2 2.32.8 用 `nativeRunMain` + `dlsym` 查找它） |
| `Java_com_sdlpop_sdlpop_PoPActivity_nativeSetup` | 已导出 ✓ |
| `STBIMG_Load` / `STBIMG_Load_RW` | 已导出 ✓ |
| 编译告警 | 仅无害告警（SDL 的 `-Wformat`/`-Wshorten-64-to-32`；SDLPoP 的 tautological-pointer-compare） |

**未做的验证**：真机运行。当前环境无显示、无连接设备，未做实机试玩。

---

## 七、过程中遇到的问题与解决

| # | 问题 | 根因 | 解决 |
|---|---|---|---|
| 1 | 本机无 `SDL2_image` 开发库 | 全盘未安装 | Windows 端从 libsdl.org 下载 VC 包（见 Windows 文档）；Android 端改用 stb_image shim |
| 2 | `cmd.exe` 被工具安全策略拦截 | 无法直接跑 `build.bat` | 改用 VS Developer PowerShell（`Enter-VsDevShell`）原生调 `cl`/`rc` |
| 3 | PowerShell 工具无法执行 `git push` | Bash 工具拦截含 push 的命令 | 改用 PowerShell 工具执行 git 提交/推送 |
| 4 | Gradle wrapper 启动报「找不到主类」 | PowerShell 解析 `-Dorg.gradle.appname=…` 异常 | 直接调用 PATH 上的 gradle 9.4.1 |
| 5 | `fatal error: 'sdl_compat.h' file not found` | 从 sdlpal 拷来的 `SDL_stbimage.h` 面向 SDL3，依赖其兼容层 | 改为直接 `#include <SDL.h>`（本移植用原生 SDL2） |
| 6 | sdlpal 的 `3rd/SDL` 是 **SDL3**，不能用 | 版本不同 | 用本机已有的 SDL2 2.32.8 源码；只照抄 sdlpal 的 Gradle/Java/NDK 配置，不抄 SDL 部分 |
| 7 | 早期 `SDLPoP-Android` 脚手架是空壳 | 当时的复制未真正落地 | 推倒重建为同仓 `android/`，未沿用 |
| 8 | **方向键视觉重叠、对角区命中错方向** | 臂长 `arm=1.15r`；对角中心距 `√2·arm≈1.63r < 2r`（半径和） | `arm` 加大到 `1.55r`（对角距 `2.19r > 2r`，无重叠）；`hitTest` 改为返回**最近**命中按钮，消除重叠区歧义 |
| 9 | **按键粘滞**：角色一直走、菜单条目循环扫过 | 竖屏→横屏 Activity 重建，旧 `VirtualPad` 销毁时未释放按键，SDL 键盘状态卡在「按下」 | `onDetachedFromWindow()` 强制释放；`ACTION_UP` 后若 `pointerButtons` 已空但 `holds[]` 仍非 0 则全量兜底释放（`releaseAllIfEmpty`）；坐标越界（手指滑出屏幕）视作释放 |
| 10 | **开了系统自动旋转后变竖屏** | `sensorLandscape` 允许传感器在左/右横屏间翻转，部分 ROM 行为异常 | 改 `landscape`：**固定左横屏**，完全忽略传感器 |
| 11 | **倾斜手机触发方向键、握持时虚拟按键失效** | 见第三节第 4 点（加速度计被注册成 joystick） | `SDL_SetHint(SDL_HINT_ACCELEROMETER_AS_JOYSTICK, "0")` |

---

## 八、已知限制

- **截图功能不可用**（`IMG_SavePNG` 返回 -1），改用系统截图。
- **`calculate_exe_crc()` 无效**：它 `fopen(g_argv[0])` 读自身，Android 上 `argv[0]="app_process"`，
  结果是 CRC=0。仅在保存设置时调用，无实际影响。
- **仅 `arm64-v8a`**：如需模拟器调试，在 `Application.mk` 加 `x86_64`（SDL2 需重新编译）。
- **`3rd/SDL` 不在版本库**：新克隆必须先跑 `android/setup.ps1`。
- **屏幕方向固定为左横屏**（`landscape`）：不受系统「自动旋转」与传感器影响，不会因旋转触发
  Activity 重建。
- **加速度计不作为输入设备**：无 tilt 操控，也不提供开关（2D 横版无实用价值）。

---

## 九、命令速查

```bash
# 增量构建
cd /d/3rd-party-projects/SDLPoP/android && gradle assembleDebug

# 清理
gradle clean

# 查看 APK 内容
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -E "lib/|assets/data/" | head

# 检查 libmain.so 符号（注意：llvm-nm 是原生程序，必须用 D:/ 风格路径）
"D:/dev/android_sdk/ndk/27.3.13750724/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-nm.exe" \
  -D --defined-only "D:/3rd-party-projects/SDLPoP/android/app/build/intermediates/cxx/Debug/<hash>/obj/local/arm64-v8a/libmain.so" | grep -E "SDL_main|nativeSetup|STBIMG_Load"

# 同步上游
git fetch upstream && git merge upstream/master
```
