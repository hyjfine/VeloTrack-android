# UI 规格书

本规格从 `VeloTrack-h5/src/App.tsx` 反抽而来，作为 Flutter 端 1:1 还原的依据。所有未明示的视觉细节请回 h5 源码核对，**以 h5 为准**。

## 1. 视图状态机

```
ViewState = recording | history | detail
```

- `recording`：默认视图，打开 App 即在此视图
- 底部导航两 Tab：`Dash`（→ recording） / `Log`（→ history / detail）
- `history → detail`：点击列表项进入；`detail → history`：顶部左上返回键
- 切换动画：
  - `recording` ↔ 其他：opacity 淡入淡出
  - `history` → `detail`：进场 `x: +20 → 0, opacity: 0 → 1`
  - `detail` → `history`：退场 `x: 0 → -20, opacity: 1 → 0`

## 2. 设计常量（详见 `design-tokens.json`）

| 名称 | 值 |
|---|---|
| 背景 | `#F4F4F7` |
| 前景 | `#1A1A1A` |
| 强调（neon yellow） | `#E2FF3B` |
| 暂停警示 | `#F97316` orange-500 |
| 危险 | `#EF4444` red-500 |
| 卡片圆角（小） | 24 / 32 |
| 卡片圆角（大） | 40 / 48 |
| 按钮圆角（胶囊） | 完全圆 |
| 字体主 | Inter 400/500/600/700 |
| 字体数字 | Inter + `fontFeatures: [tabularFigures]` |
| Mono | JetBrains Mono |

## 3. Recording 视图

### 3.1 布局
- 全屏地图作底，深色 Carto 瓦片；`opacity: 0.9`
- 顶部悬浮两张卡（左状态、右距离），距 safe-area-top 48px
- 底部中央一张主仪表卡（速度 | 主按钮 | 海拔），距底部 128px（bottom-32）

### 3.2 顶部左卡（Status）
```
┌─────────────────────┐
│ ● TRACKING          │  ← 圆点 8px，录制时 red-500 + pulse，否则 gray-300
│ 00:14:32            │  ← 2xl bold tabular-nums
└─────────────────────┘
padding: 16   radius: 16   bg: white/95   backdrop-blur-md   border: gray-100
阴影: shadow-lg
enter: y:-20, opacity:0 → 0,1
```

标签文案：
- 录制中：`TRACKING`
- 未录制：`GPS IDLE`

### 3.3 顶部右卡（Distance）
```
┌───────────┐
│ DISTANCE  │
│ 12.34km   │
└───────────┘
同样式。enter 同左卡，delay: 0.1s
```

### 3.4 主仪表卡（Gauge Card）
```
┌─────────────────────────────────────────┐
│   SPEED          [ BTN ]       ALTITUDE │
│   32.4 KPH                      156 M   │
└─────────────────────────────────────────┘
radius: 40   padding: 32   bg: white   border: gray-100
阴影: shadow-2xl
长按时：底部 1.5px 进度条（#E2FF3B）从 0 → 100% 匀速
```

数字特征：
- Speed 大号：5xl bold tabular-nums，小标 `KPH` 10px black opacity-20
- Altitude 中号：3xl bold tabular-nums，小标 `M`
- 暂停态 Speed 强制显示 `0.0`（即使原生还在推位置）

### 3.5 主按钮（State Machine）

| isRecording | isPaused | 颜色 | 图标 | 点击行为 |
|---|---|---|---|---|
| false | — | bg `#E2FF3B` / text black | Play | `startRecording()` |
| true | false | bg `#1A1A1A` / text `#E2FF3B` | Pause | 短按 → toggle pause；长按 1.5s → stop |
| true | true | bg orange-500 / text white | Play | 同上，短按恢复 |

- 尺寸：80×80，圆，4px 边框色 `#F4F4F7`
- 反馈：`active:scale-90`
- 录制中按钮下方 8px 显示 `HOLD TO STOP`（10px bold uppercase tracking-widest opacity-30）
- 长按进度条见 3.4

### 3.6 地图 Polyline
- 颜色：`#E2FF3B`
- weight: 5，opacity: 0.9
- 录制中实时追加；`points.length > 1` 才画
- 中心：跟随 `currentPos`，zoom=16

## 4. History 视图

### 4.1 顶部
```
RIDE LOG                      ▬▬▬
12 TRIPS SAVED
```
- 标题：4xl bold tracking-tight
- 副标：10px bold gray-400 uppercase tracking-widest
- 右侧装饰条：48×6 bg-black rounded-full

### 4.2 空态
```
       ╱╲
      ╱  ╲   Navigation icon 64px opacity-5
     ╱____╲
  WAITING FOR YOUR FIRST RIDE
```
- 卡片：radius 40、bg-white、`py-32`、text-center

### 4.3 列表项（Ride Card）
```
┌──────────────────────────────────────┐
│ [icon] Ride on 2026-04-22            │
│        12.34km   01:02:03   [🗑] [›] │
└──────────────────────────────────────┘
padding: 24   radius: 32   bg: white   border: gray-100/50
enter: y:20,opacity:0 → 0,1 ; delay: idx * 0.05
active: scale-[0.97]
```

- 左侧图标块：56×56，bg `#F4F4F7`，radius 24，按下变 `bg-black text-[#E2FF3B]`
- 距离/时长：10px bold uppercase tracking-widest，默认 gray-900（opacity-30 小字）
- 删除触发：`showDeleteModal(rideId)`，不直接删除

## 5. Detail 视图

### 5.1 顶栏
- Sticky，`pt-12`，`px-6 py-8`，bg `white/80 backdrop-blur-xl`
- 左：返回按钮 40×40 bg `#F4F4F7` radius 16，图标 ChevronLeft（旋转 180°）
- 中：`SESSION DETAILS` 小写 uppercase bold
- 右：占位（设置按钮 opacity-0）

### 5.2 地图预览
- 高 320，上到下白→背景色渐变遮罩
- 中心为 `points[0]`，zoom 14，Polyline 同 recording

### 5.3 Stats Grid（2×2）
四张卡：
1. Total Distance
2. Average Speed（`kph` 小写）
3. Moving Time
4. Max Speed

```
┌─────────────────────┐
│ TOTAL DISTANCE      │  ← 9px bold gray-400 uppercase tracking-widest
│ 12.34km             │  ← 2xl bold tracking-tight
└─────────────────────┘
padding: 24   radius: 32   bg: white
enter: y:20,opacity:0 → 0,1 ; delay: idx * 0.1
```

### 5.4 Performance Chart
- 容器：padding 32，radius 40，白卡
- 标题：`PERFORMANCE` 10px black uppercase tracking-[0.25em] gray-300，左侧小竖条 4×16 黑色
- 图表高度 192，`fl_chart` `LineChartData`：
  - X：point index
  - Y：`(speed * 3.6)` km/h
  - 线：黑色 stroke 3
  - 无 dot，hover dot：r=6，边 `#E2FF3B` 3px
  - 网格：水平 only，色 `#f0f0f0`，dash `[3,3]`

### 5.5 AI Coaching 卡
- 黑底 radius 48，padding 40，`shadow-2xl`
- 右上角 Brain icon 200px，opacity-5，rotate-12
- 头像：12×12 bg `#E2FF3B` radius 16 + 外发光 `rgba(226,255,59,0.3)`
- 标题：`AI Analysis` 18px white bold，副标 `Coach Velo v3.0` 9px `#E2FF3B` uppercase
- 未分析：按钮 `ANALYZE MY PERFORMANCE`（白底黑字，hover `#E2FF3B`），右箭头 hover `translate-x-1`
- 分析中：三条骨架 `bg-white/10` pulse，宽度 100% / 80% / 83%
- 结果：`italic` 20px white/90 medium tracking-tight，两侧引号包裹

### 5.6 AI Prompt 模板（供原生端实现）

```
Analyze this cycling ride and provide professional coaching advice.
Distance: {formatDistance(totalDistance)}
Duration: {formatDuration(endTime - startTime)}
Avg Speed: {formatSpeed(avgSpeed)} km/h
Max Speed: {formatSpeed(maxSpeed)} km/h
Data samples: {points.length}
Please give a concise analysis in Chinese (suitable for mobile display), including:
1. Performance summary
2. One area for improvement
3. A motivational remark.
```
model: `gemini-3-flash-preview`

## 6. 底部导航（常驻）
- 固定底部，`rounded-t-[32px]`
- `pb-8 pt-4`，白色 95% + backdrop-blur-md，上阴影 `0 -10px 30px rgba(0,0,0,0.03)`
- 两项：Dash（Activity icon）/ Log（History icon）
- 激活：`text-black scale-110 strokeWidth:2.5`
- 未激活：`text-gray-300 strokeWidth:2`
- 文案：10px bold uppercase tracking-widest

## 7. 删除确认 Modal
- 全屏半透明 `bg-black/40 backdrop-blur-sm`
- 卡片：白底 radius 40、`p-10`、max-w-xs
- 顶部红色警示圆 64×64 bg red-50 text red-500 radius 16 + AlertCircle 32px
- 标题 `Delete Ride?` 20px bold
- 描述 14px gray-500
- 主按钮：黑底白字，`CONFIRM DELETE` 14px bold uppercase tracking-widest
- 次按钮：gray-400 文字 12px bold uppercase tracking-widest
- enter：`scale:0.9, y:20 → 1,0` 300ms spring
- 背景 fade 200ms

## 8. 关键交互规范

| 交互 | 触发 | 行为 | 动效 |
|---|---|---|---|
| 开始录制 | 主按钮点击（非录制态） | 原生 start + WakeLock + 计时 | 按钮色切换 |
| 暂停/恢复 | 主按钮短按（录制态） | 切 `isPaused`；暂停时 speed 显示 0 | 色切 + 震动反馈 |
| 停止录制 | 主按钮长按 1500ms | 停止、落库、跳 detail | 底部进度条 linear 1.5s |
| 删除 ride | 列表项垃圾桶 | 弹 Modal 二次确认 | Modal spring |
| 切换 Tab | 底部导航点击 | 视图切换 | opacity/x 联动 |
| 返回详情 | 详情左上返回 | 回 history 且保留滚动位置 | x:-20 退出 |

## 9. 字体与数字细节

所有展示型数字（时间、速度、距离、海拔）必须启用 `FontFeature.tabularFigures()`，保持等宽对齐避免跳动。英文大写文案使用 `letterSpacing: 0.15em+`（对应 tailwind `tracking-widest`）。

## 10. 错误态文案映射（对应 architecture.md 错误码）

| code | 位置 | 文案 |
|---|---|---|
| `GPS_PERMISSION_DENIED` | 启动录制时弹出 | `需要定位权限才能记录骑行` + 跳系统设置 |
| `GPS_SIGNAL_LOST` | 顶部状态卡 | 圆点灰色 + `SIGNAL LOST` |
| `DB_WRITE_FAILED` | 停止录制后 toast | `保存失败，请重试` |
| `AI_PROXY_FAILED` | Detail AI 卡替换 | `分析失败，请稍后重试` |
| `UNKNOWN` | toast | `出现未知错误` |
