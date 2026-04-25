# Design Tokens

`design-tokens.json` 是机器可读的单一真相源，三端均应从它生成各自的常量：

- `VeloTrack-h5`：Tailwind v4 `@theme` 读取（手工同步或写脚本生成 CSS 变量）
- `VeloTrack-flutter`：`build_runner` 生成 `VeloTokens` 的 `ThemeExtension`
- 原生：只涉及极少数颜色（状态栏、通知图标），可手工同步

## 字段语义

### `color`

| key | 用途 | h5 引用位置 |
|---|---|---|
| `background` | 全局背景 | `body` / `#F4F4F7` |
| `foreground` | 主文字、主按钮底 | `text-[#1A1A1A]` |
| `accent` | 强调/主按钮激活 | `#E2FF3B` |
| `accentGlow` | accent 外发光 | AI 卡头像 shadow |
| `warn` | 暂停态按钮背景 | `bg-orange-500` |
| `danger` | 删除、危险操作 | `text-red-500` |
| `mapBg` | 地图底色 | `.leaflet-container` |
| `polyline` | 轨迹线 | 同 accent |
| `mutedText` | 次级文字 | `opacity-30` 近似，用 `#B4B4BA` |
| `divider` | 卡片边框 | `gray-100` → `#F0F0F2` |

### `radius`

| key | 用途 |
|---|---|
| `sm` (16) | 小图标容器、顶栏返回键 |
| `md` (24) | 列表左侧图标块、顶部卡片 |
| `lg` (32) | 列表卡片、Stats 卡 |
| `xl` (40) | 主仪表卡、Detail 地图卡、删除 Modal |
| `xxl` (48) | AI 卡 |

### `spacing`

以 4px 为基，复用 tailwind；仅在需要与 h5 对齐的大值处列出（如 `pb-40` = 160、`bottom-32` = 128）。

### `font`

| key | 字体 | 特性 |
|---|---|---|
| `sans` | Inter | 400/500/600/700 |
| `mono` | JetBrains Mono | — |
| `numeric` | Inter + `tabularFigures` | 所有数字展示 |

### `motion`

| key | 值 | 用途 |
|---|---|---|
| `enterFade` | `{ opacity: [0,1], duration: 300 }` | recording / history |
| `enterSlide` | `{ x: [20,0], opacity: [0,1], duration: 300 }` | detail 进入 |
| `exitSlide` | `{ x: [0,-20], opacity: [1,0], duration: 300 }` | detail 退出 |
| `stagger` | `0.05s` 或 `0.1s` | 列表项 / stats 级联 |
| `longPressMs` | `1500` | 停止按钮 |
| `pressScale` | `0.9` / `0.97` | 按钮 / 卡片按下 |

### `typography`

| key | size(px) | weight | letterSpacing | 用途 |
|---|---|---|---|---|
| `displayL` | 56 | 700 | -0.02em | 主按钮速度数字 |
| `displayM` | 36 | 700 | -0.02em | 详情大数字、海拔 |
| `title` | 36 | 700 | -0.01em | `RIDE LOG` |
| `cardTitle` | 18 | 700 | -0.01em | 列表项标题、AI 头 |
| `stat` | 24 | 700 | -0.01em | Stats 卡数字 |
| `duration` | 24 | 700 | 0 | 顶部时间 |
| `labelS` | 9 | 900 | 0.22em | 小标、UPPERCASE |
| `labelM` | 10 | 700 | 0.18em | 标签、Tab 文案 |
| `body` | 14 | 400 | 0 | Modal 描述 |

## 生成脚本（建议落在 `VeloTrack-flutter/tool/`）

- `build_tokens.dart`：读 `design-tokens.json`，产出 `lib/src/theme/velo_tokens.g.dart`
- `build_tokens.ts`（h5 侧）：读同一 JSON，产出 `src/tokens.ts`（被 Tailwind config 与 index.css 读取）

CI 校验：比对 h5 / Flutter 生成产物与 `design-tokens.json` 的哈希，不一致直接失败。
