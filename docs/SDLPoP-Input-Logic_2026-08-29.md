# SDLPoP 输入与操作机制（源码笔记）

日期：2026-08-29
适用版本：upstream `NagyD/SDLPoP` master + 本 fork 的 Android 移植（`android/`）
目的：记录游戏底层输入管线的真实行为，作为虚拟按键设计、后续调优与排查的依据。
所有结论均对照本仓 `src/` 源码验证，行号以当前版本为准。

---

## 一、输入管线：事件 → 轮询 → 控制量

```
SDL 事件队列 (SDL_KEYDOWN / SDL_KEYUP)
        │  do_sdl_events()  分发
        ▼
key_states[SDL_SCANCODE_x] = KEYSTATE_HELD / RELEASED   (seg009.c, data.h)
        │  read_keyb_control()  每帧轮询   (seg000.c:1775)
        ▼
control_forward / backward / up / down / shift / shift2 = CONTROL_HELD | CONTROL_RELEASED | CONTROL_IGNORE
        │  control_pressed()  每帧消费     (seg005.c:215 起)
        ▼
具体动作（跑 / 走一步 / 跳 / 蹲 / 爬 / 出刀 ...）
```

要点：

1. **纯轮询模型**：游戏不响应"按键沿"，只看每帧的 `KEYSTATE_HELD`。虚拟按键只要把 SDL 键盘状态置位即可，与物理键盘完全等价。
2. **三态控制量**：`CONTROL_HELD`（有效输入）、`CONTROL_RELEASED`（无输入）、`CONTROL_IGNORE`（抑制自动重复的粘性屏蔽，见 §四）。
3. **键位可配置**（`data.h` 中 `key_up`/`key_down`/`key_left`/`key_right`/`key_action` 等，默认为方向键 + `RSHIFT`）。
   **Shift 判定同时接受 LSHIFT 和 RSHIFT**（`seg000.c:1804`），因此虚拟按键注入 `KEYCODE_SHIFT_LEFT` 即有效。
4. **Space 键无效**：`read_keyb_control()` 完全不读 Space——原版 PoP1 的跳跃就是"方向 + Up"，不是 Space。

---

## 二、移动：没有"走/跑"两档

`forward_pressed()`（`seg005.c:566` 起）在开阔地按方向键**直接进入起跑序列** `seq_1_start_run`：

| 场景 | 行为 |
|---|---|
| 开阔地按住方向 | 起跑并持续奔跑 |
| 离边缘/障碍 < 8 像素（`char 3 ahead` 检测） | 自动降级为迈一步，贴边精确停住 |
| `Shift` + 方向（`control_x == HELD_FORWARD`） | `safe_step()`：精确迈一步（`seg005.c:382`） |

**结论**：想要"精确一步"，游戏的原生机制就是 **Shift + 方向**（safe_step）。不存在独立"走"的速度档，也无法通过短按/长按区分——按住方向必然起跑。这就是 Android 虚拟按键把 Shift 做成独立按钮、方向键保持"按住即跑"的原因。

---

## 三、`CONTROL_IGNORE` 粘性（易踩坑）

`read_user_control()`（`seg006.c:1557`）规定：**方向键持续按住期间，被置为 IGNORE 的控制量不会恢复为 HELD**。

推论（实测原版行为一致）：

- `safe_step()` 后若继续按住方向，`control_forward` 卡在 IGNORE——此时**中途补按 Shift 不会连续迈步，也不会起跑**。
- 想从"迈一步"转入奔跑，必须把方向键**松开再按下**（完整的 RELEASED→HELD 变化），起跑才会触发。
- `release_arrows()`（`seg006.c:1524` 附近）在方向键物理松开时复位各控制量，这就是"必须重按"的底层原因。

任何想在虚拟按键层实现"先走后跑"的时序方案，都必须利用这个"释放→重按"间隙（本移植最终选择了不模拟，直接用 Shift 按钮实现，见 §七）。

---

## 四、跳跃（Up 键的完整语义）

| 输入 | 效果 | 源码 |
|---|---|---|
| Up 单按（站立、无方向） | 竖直起跳（`up_pressed()` 内 `standing_jump()` 分支） | `seg005.c:393, 675, 682` |
| 方向 + Up（键盘模式，两者同时 HELD） | 原地向前跳（standing jump） | `seg005.c:386, 394` |
| 奔跑中按 Up | 跑跳 | `up_pressed()` 内部 |
| 悬挂/攀爬状态下按 Up | 爬上平台 | `up_pressed()` 爬升分支 |

`standing_jump()`（`seg005.c:687`）会把 `control_up`/`control_forward` 置 IGNORE 防止自动重复跳，松开 Up 后由 `release_arrows()` 复位。

---

## 五、蹲 / 挂 / 爬（Down 与 Shift 的组合）

`down_pressed()`（由 Down 或 Shift+Down 进入，`seg005.c:380, 399`）按角色当前状态分发：

| 状态 | 效果 |
|---|---|
| 站立 | 蹲下 |
| 站在平台边缘 | 翻下悬挂（不坠落） |
| 悬挂中 | 松手下落（Shift 可在下落中抓边） |

`up_pressed()`（Up 或 Shift+Up 进入）：悬挂中 → 爬上；下落中按住 Shift → 抓住边缘。

---

## 六、Shift 的全部语义（`seg005.c:375` 主分发）

```
control_shift == HELD 时：
    Shift + 后退        → back_pressed()   转身
    Shift + Up          → up_pressed()     爬上 / 抓边
    Shift + Down        → down_pressed()   蹲 / 挂 / 下爬
    Shift + 前方 + 方向 → safe_step()      精确一步
战斗中（面前有敌人）：
    Shift               → draw_sword() 出刀/收刀 (seg005.c:363)
    Shift + 后退        → 格挡
    Shift + 前进        → 攻击
```

注意 `seg005.c:375` 的分发顺序：**Shift 按住时优先匹配 Shift 组合**，不匹配才走纯方向分支。所以"Shift + 方向"在站立时永远被解释为 safe_step。

---

## 七、Android 虚拟按键设计（基于以上机制）

布局（`VirtualPad.java`，横屏）：

| 位置 | 按钮 | 键码 | 对应机制 |
|---|---|---|---|
| 左侧（左拇指） | ◀ / ▶ | `DPAD_LEFT/RIGHT` | 按住即跑（§二）；+Shift = 精确一步 |
| 右侧（右拇指） | ▲ | `DPAD_UP` | §四 全部跳跃/爬升；菜单上移 |
| 右侧（右拇指） | ▼ | `DPAD_DOWN` | §五 蹲/挂/下爬；菜单下移 |
| 右侧（右拇指） | Shift | `SHIFT_LEFT` | §六 全部 Shift 语义 |
| 右侧（右拇指） | ⏎ | `ENTER` | 菜单确认 |
| 右上角 | Esc | `ESCAPE` | 暂停/主菜单 |

设计决策记录：

1. **不做短按/长按区分**（已评估后放弃）：需要模拟"safe_step → 释放 → 重按 → 起跑"的键序，受 §三 IGNORE 粘性约束时序脆弱，且长按起跑前必先迈一步。改为**显式 Shift 按钮**——与原版键盘操作完全一致，零模拟、零时序风险。
2. **删除 Space 按钮**：游戏不读 Space（§一.4），旧布局的"跳(Space)"是无效按键。
3. **多指同按天然支持组合**：Shift+方向、方向+跳 均为两指按住，无需额外逻辑。
4. 菜单导航沿用方向键（▲/▼ = 上下条目，◀/▶ = 调整选项），Enter 确认，Esc 呼出。
5. **上/下按钮标签用箭头而非 Jump/Duck**：这两个键是全语义的 Up/Down（跳、爬上、菜单上移 /
   蹲、挂、菜单下移），箭头与键位语义完全一致，且与左侧 ◀/▶ 风格统一。

相关文档：
- 构建流程与 Android 适配点：`docs/SDLPoP-Android-Build-Notes_2026-08-29.md`（含加速度计劫持输入模式的坑）
