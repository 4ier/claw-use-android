# Overlay & Takeover — 设计文档

## 概述

当 AI Agent 远程控制手机时，用户需要：
1. **知情权** — 清楚知道谁在控制、在做什么
2. **控制权** — 随时一键终止
3. **授权权** — 首次操作需明确同意

本设计将 overlay 从单次 action 指示器升级为 **session 级人机协作界面**。

---

## 1. 授权弹窗 (Permission Gate)

### 触发时机
- Agent 首次发起设备操作（任何非 GET 的 a11y 请求）
- 已记忆授权的 Agent 跳过弹窗

### UI
```
┌─────────────────────────────────┐
│                                 │
│   🤖 AI Agent 请求控制你的手机    │
│                                 │
│   来源: 192.168.0.50:3000       │
│   Agent: Fourier                │
│                                 │
│   ☐ 信任此 Agent，后续自动允许    │
│                                 │
│   [拒绝]              [允许]     │
│                                 │
└─────────────────────────────────┘
```

### 行为
- 弹窗期间，所有 a11y 操作排队等待（HTTP 请求 pending，不返回 403）
- 用户点「允许」→ 队列中请求依次执行，session 开始
- 用户点「拒绝」→ 队列中请求返回 `403 Forbidden`，body: `{"error": "user_denied"}`
- 勾选「信任」→ agent 标识写入 `ConfigStore`（EncryptedSharedPreferences），后续同一 agent 自动放行
- 弹窗超时 30s → 等同拒绝

### Agent 标识
- 通过新 header `X-Agent-Name` 传入（可选，默认 "Unknown Agent"）
- 信任列表按 `(token, agent_name)` 组合存储

---

## 2. Session 模型

### 核心概念
当前 API 是无状态的单次 action。新增 session 层，将多次 action 归属到一次完整的推理过程。

### API

#### 开始 Session
```
POST /session/start
X-Bridge-Token: <token>
X-Agent-Name: Fourier

{
  "goal": "打开微信发送消息给张三",  // 可选，展示在 overlay
  "timeoutMs": 60000               // 可选，默认 60s
}

→ 200 {"sessionId": "abc123", "status": "active"}
```

#### 结束 Session
```
POST /session/end
X-Bridge-Token: <token>

{"sessionId": "abc123", "result": "success"}

→ 200 {"status": "ended", "durationMs": 12345, "actions": 8}
```

#### Session 内操作
现有所有 action 端点（/tap, /type, /swipe 等）增加可选 header:
```
X-Session-Id: abc123
```
- 有 session → action 记录归入该 session，overlay 实时显示
- 无 session → 向后兼容，行为不变（单次 action，overlay 短暂闪烁）

### Session 生命周期
```
[Agent 发起 /session/start]
        │
        ▼
[授权弹窗] ──拒绝──→ 403 user_denied
        │
      允许
        │
        ▼
[Session Active] ◄──── /tap, /type, /swipe ...
        │                  (overlay 实时显示)
        │
   超时 or /session/end or 用户接管
        │
        ▼
[Session Ended]
```

### 超时
- 默认 60s 无 action → 自动结束 session
- 每次 action 重置计时器
- 最大 session 时长: 5min（可配置）

---

## 3. Overlay UI

### 布局
```
手机屏幕
┌──────────────────────────┐
│                    ┌────┐│
│                    │ 🔴 ││  ← 接管按钮 (右上角)
│                    ├────┤│
│                    │ ▸  ││  ← 折叠/展开
│                    │    ││
│                    │ AI ││  ← overlay 主体
│                    │ 控制││
│                    │ 中..││
│                    │    ││
│                    └────┘│
│                          │
│     (手机正常内容)         │
│                          │
└──────────────────────────┘
```

### 展开状态
```
┌──────────────────┐
│ 🔴 接管  Fourier │
├──────────────────┤
│ 目标: 发微信给张三 │
│ ─────────────── │
│ > 读取屏幕       │
│ > 启动 微信      │
│ > 点击 "通讯录"  │
│ > 点击 "张三"    │
│ > 输入 "你好"    │
│ > 点击 发送 ✓    │
│                  │
│ 6 步 · 4.2s     │
└──────────────────┘
```

### 折叠状态
```
┌──────────┐
│ 🔴 AI·6步 │
└──────────┘
```

### 视觉规范
- 背景: `#1a1a1a` 90% 不透明
- 文字: `#00ff88` (终端绿) 等宽字体 (monospace)
- 接管按钮: `#ff4444` 圆形，脉冲动画
- 圆角: 12dp
- 阴影: elevation 8dp
- 宽度: 屏幕宽度 40%（展开），自适应（折叠）
- 最大高度: 屏幕高度 50%，超出滚动
- 位置: 右上角，距顶 48dp，距右 12dp
- 可拖动

### Action 日志格式
每条 action 在 overlay 中显示为一行：
| Action | 显示 |
|--------|------|
| /screen | `> 读取屏幕` |
| /screenshot | `> 截图` |
| /tap | `> 点击 (x, y)` |
| /click | `> 点击 "文本"` |
| /type | `> 输入 "内容"` |
| /swipe | `> 滑动 ↑/↓/←/→` |
| /scroll | `> 滚动 ↑/↓` |
| /launch | `> 启动 包名` |
| /global | `> 返回/主页/...` |
| /tts | `> 朗读 "内容"` |

成功: 行尾 `✓`  失败: 行尾 `✗ (原因)`

---

## 4. 接管 (Takeover)

### 触发
- 用户点击 🔴 接管按钮
- 或：用户在 overlay 区域外的屏幕上执行任何触摸操作（可配置）

### 行为
1. 立即终止当前 session
2. 正在执行的 action 中断
3. Overlay 显示 "已接管" 后 2s 消失
4. 后续来自该 session 的请求返回 `409 Conflict`:
   ```json
   {"error": "session_terminated", "reason": "user_takeover"}
   ```
5. Agent 需发起新的 /session/start 重新请求授权

---

## 5. 多语言 (i18n)

### 策略
- `Locale.getDefault()` 检测系统语言
- 初期: `zh-CN`, `en` (fallback)
- 后续: `ja`, `ko`, `es`, `pt`

### 字符串清单

| Key | zh-CN | en |
|-----|-------|-----|
| `permission_title` | AI Agent 请求控制你的手机 | AI Agent requests phone control |
| `permission_source` | 来源 | Source |
| `permission_agent` | Agent | Agent |
| `permission_trust` | 信任此 Agent，后续自动允许 | Trust this Agent, auto-allow later |
| `permission_deny` | 拒绝 | Deny |
| `permission_allow` | 允许 | Allow |
| `overlay_takeover` | 接管 | Take Over |
| `overlay_goal` | 目标 | Goal |
| `overlay_steps` | 步 | steps |
| `overlay_taken_over` | 已接管 | Taken Over |
| `action_read_screen` | 读取屏幕 | Reading screen |
| `action_screenshot` | 截图 | Screenshot |
| `action_tap` | 点击 | Tap |
| `action_click` | 点击 | Click |
| `action_type` | 输入 | Type |
| `action_swipe` | 滑动 | Swipe |
| `action_scroll` | 滚动 | Scroll |
| `action_launch` | 启动 | Launch |
| `action_back` | 返回 | Back |
| `action_home` | 主页 | Home |
| `action_speak` | 朗读 | Speak |
| `session_timeout` | 会话超时 | Session timed out |

### 实现
- Android 标准 `res/values/strings.xml` + `res/values-zh/strings.xml`
- Overlay 文本全部从 strings 资源读取，不硬编码

---

## 6. 实现计划

### Phase 1: Session API + 授权弹窗
- [ ] `SessionManager.java` — session 创建/销毁/超时管理
- [ ] `PermissionGateActivity.java` — 全屏授权弹窗 (Dialog theme)
- [ ] `TrustStore.java` — 基于 EncryptedSharedPreferences 的信任列表
- [ ] 路由层: /session/start, /session/end
- [ ] 中间件: 未授权 agent 的请求触发弹窗排队

### Phase 2: Overlay 重构
- [ ] `OverlayService.java` 重写 — session 级 overlay 替代单次 action overlay
- [ ] 终端风格 UI (自定义 View)
- [ ] 折叠/展开/拖动
- [ ] Action 日志实时滚动
- [ ] 接管按钮 + 脉冲动画

### Phase 3: i18n
- [ ] 抽取所有硬编码字符串到 strings.xml
- [ ] 添加 values-zh/strings.xml
- [ ] 授权弹窗 / Overlay / 通知栏 全部走 i18n

### Phase 4: 测试
- [ ] 授权弹窗流程 (允许/拒绝/信任/超时)
- [ ] Session 生命周期 (正常结束/超时/接管)
- [ ] Overlay 显示 (展开/折叠/拖动/日志滚动)
- [ ] 多语言切换
- [ ] 向后兼容 (无 session 的旧 API 调用)
