# Claw Use Android — Phone Control for AI Agents

Give your AI agent eyes, hands, and a voice on a real Android phone.

`claw-use-android` is an Android app + CLI (`cua`) that exposes HTTP endpoints for full phone control. No ADB, no root, no PC.

## Setup

```bash
# Install the APK on your Android phone, enable Accessibility Service
# Then register the device:
cua add redmi 192.168.0.105 <token>
cua ping
```

## New in v2.0.0: Unified API

Three new endpoints replace the scattered old endpoints for AI agent workflows:

### GET /screen — Semantic UI Tree
Returns elements with stable integer `ref` IDs, semantic `zone`, and `role` annotations.

```bash
cua screen              # full semantic UI tree (JSON)
cua screen -c           # compact: only interactive/text elements
```

Response:
```json
{
  "package": "com.android.settings",
  "elements": [
    {"ref": 1, "text": "设置", "zone": "header"},
    {"ref": 2, "text": "搜索", "zone": "header", "role": "button", "click": true},
    {"ref": 3, "text": "WLAN", "zone": "content"}
  ]
}
```

### GET /snapshot — JPEG Screenshot
Returns a base64-encoded JPEG screenshot.

```bash
cua snapshot              # save screenshot, print path
cua snapshot 50 720 out.jpg  # quality, maxWidth, output
```

### POST /act — Unified Action Endpoint
All operations through a single entry point, using `ref` IDs from `/screen`.

```bash
cua act '{"click": 3}'              # click ref 3
cua act '{"click": "OK"}'           # click by text (fallback)
cua act '{"click": [1, 2, 3]}'      # click refs in sequence
cua act '{"tap": {"x": 540, "y": 960}}'
cua act '{"type": "hello"}'          # type into focused field
cua act '{"type": {"ref": 3, "text": "hello"}}'  # focus ref then type
cua act '{"swipe": "up"}'            # directional swipe
cua act '{"scroll": "down"}'         # scroll nearest scrollable
cua act '{"back": true}'
cua act '{"home": true}'
cua act '{"recents": true}'
cua act '{"longpress": 3}'           # long press ref
cua act '{"launch": "com.duolingo"}'

# Multiple actions in one request:
cua act '{"home": true, "back": true}'
```

### Agent Workflow Pattern (screen → act loop)
```bash
# 1. Observe
cua screen -c          # get refs
# 2. Act
cua act '{"click": 5}' # click ref 5
# 3. Observe again
cua screen -c          # see result
```

### Flow-First Principle

**执行手机操作前，先读 `flows.md`（与本文件同目录）。**

- 如果有匹配的 flow → 直接用 `/flow` 或批量脚本执行，跳过逐步推理
- 如果 flow 中有 `{"screen":true}` 断点 → 在该步读屏后由 agent 决策，然后继续
- 如果没有匹配 flow → 走 screen→act 循环，完成后**沉淀新 flow 到 `flows.md`**
- 如果 flow 执行失败（超时、元素未找到等）→ **回退到 screen→act 循环**继续完成任务，事后修正 flows.md

**主动沉淀（必须执行）：** 完成任何多步操作后，立即审视刚才的步骤序列。如果发现可复用的模式（哪怕只是部分步骤），当场追加到 `flows.md`。不要等用户提醒。沉淀是 agent 的责任，不是用户的。

这样做的好处：
1. **快**：`/flow` 在设备端 100ms 轮询执行，不经过 LLM
2. **省 token**：一个 flow 替代 5-10 轮 agent 推理
3. **可积累**：每次新场景都沉淀，agent 越用越快

## Legacy CLI Reference (`cua`)

All legacy endpoints remain supported alongside the new unified API.

### Device Management
```bash
cua add <name> <ip> <token>    # register device with alias
cua devices                     # list all (with live status)
cua use <name>                  # switch default device
cua rm <name>                   # remove device
cua -d <name> <command>         # target specific device
cua discover                    # scan LAN for devices (192.168.x.x:7333)
```

### Perception — read the phone
```bash
cua screen              # full UI tree (JSON)
cua screen -c           # compact: only interactive/text elements
cua screenshot          # save screenshot, print path
cua screenshot 50 720 out.jpg  # quality, maxWidth, output
cua notifications       # list all notifications
cua status              # health dashboard
cua info                # device model, screen size, permissions
```

### Action — control the phone
```bash
cua tap <x> <y>         # tap coordinates
cua click <text>        # tap element by visible text
cua longpress <x> <y>   # long press
cua swipe up|down|left|right
cua scroll up|down|left|right
cua type "text"         # type text (CJK supported)
cua back                # system back
cua home                # go home
cua launch <package>    # launch app
cua launch              # list all apps
cua open <url>          # open URL
cua call <number>       # phone call
cua intent '<json>'     # fire Android Intent
```

### Audio
```bash
cua tts "hello"         # speak through phone speaker
cua say "你好"          # alias
```

### Device I/O (v1.7.0+)
```bash
cua clipboard           # read clipboard
cua clipboard "text"    # write to clipboard
cua camera [front|back] [quality] [output.jpg]  # take photo
cua volume              # read all volumes
cua volume media 10     # set media volume
cua volume media up     # adjust volume
cua battery             # battery status
cua wifi                # WiFi info
cua location            # GPS/network location
cua vibrate [ms]        # vibrate (default 200ms)
cua contacts [search]   # list/search contacts
cua sms list [limit]    # read SMS
cua sms send <number> <message>  # send SMS
cua file list [path]    # list directory
cua file read <path>    # read file
cua file write <path> <content>  # write file
cua file delete <path>  # delete file
```

### Device State
```bash
cua wake                # wake screen
cua lock / cua unlock   # lock/unlock (PIN required)
cua config pin 123456   # remember lock screen PIN for auto-unlock
cua config pattern 256398  # EXPERIMENTAL: pattern unlock (not yet verified)
```

### Flow Engine — phone-side scripted automation
```bash
cua flow '{
  "steps": [
    {"wait": "继续安装", "then": "tap", "timeout": 10000},
    {"wait": "继续更新", "then": "tap", "timeout": 10000},
    {"wait": "完成",     "then": "tap", "timeout": 60000, "optional": true}
  ]
}'
```

Flow runs entirely on the phone with zero LLM calls. The device polls its accessibility tree at 100ms intervals and reacts instantly when the target element appears.

**Step fields:**
- `wait` — text to find (case-insensitive partial match)
- `waitId` — resource ID to find
- `waitDesc` — content description to find  
- `waitGone` — wait for text to DISAPPEAR
- `then` — action: `tap`, `click`, `longpress`, `back`, `home`, `none`
- `timeout` — per-step timeout in ms (default 10000)
- `optional` — if true, timeout doesn't fail the flow
- `pauseMs` — pause after action before next step (default 500)

### Click with Retry
```bash
# Atomic find-and-tap: retries until element appears
curl -X POST /click -d '{"text":"继续安装","retry":3,"retryMs":2000}'
```

---

## Device Onboarding (New Device Setup)

Complete recipe for adding a new Android device from zero to fully operational.

### Prerequisites (human must do once)
1. Install APK on the device (download from GitHub Releases or LAN HTTP)
2. Enable Accessibility Service: Settings → Accessibility → Claw Use → ON
3. Note the auth token from the app notification or main screen

### Step 1: Discover & Register
```bash
# Scan LAN for devices
cua discover

# Register with a friendly name
cua add <name> <ip> <token>

# Verify connectivity
cua -d <name> ping
cua -d <name> info
```

### Step 2: Configure Auto-Unlock
```bash
# PIN unlock (recommended — proven reliable via a11y button tapping)
cua -d <name> config pin <PIN>

# Verify: lock then unlock
cua -d <name> lock
sleep 3
cua -d <name> unlock
# Should show {"unlocked":true}
```

**Important**: Only PIN unlock is verified to work. Pattern unlock is experimental and unreliable — the accessibility gesture dispatch doesn't consistently hit the correct grid coordinates across different devices and screen sizes. If the device uses pattern lock, change it to PIN.

### Step 3: MIUI/HyperOS Permissions (automated)
```bash
cua -d <name> setup-perms
```

This automates granting all 9 app permissions on MIUI devices:
位置, 相机, 麦克风, 照片和视频, 音乐和音频, 短信, 电话, 联系人, 日历

The command navigates through Settings → Apps → Claw Use → Permissions and clicks through each permission grant dialog.

**If `setup-perms` fails** (common on tablets with dual-pane layout), grant manually:
1. Open Settings → Apps → Manage Apps → search "Claw Use"
2. Tap "App permissions" (应用权限)
3. Enable each permission: prefer "始终允许" > "仅在使用中允许" > "允许"

### Step 4: Background Survival (MIUI)
These settings prevent MIUI from killing the service:

```bash
# Navigate to app settings
cua -d <name> intent '{"action":"android.settings.APPLICATION_DETAILS_SETTINGS","uri":"package:com.clawuse.android"}'
```

Then via a11y or manually ensure:
- **自启动 (Autostart)**: ON
- **省电策略 (Battery saver)**: 无限制 (No restrictions)
- **通知 (Notifications)**: 允许 (Allow)
- **WLAN联网 (WiFi access)**: ON (if available)

### Step 5: Verify Everything
```bash
cua -d <name> status    # check a11y health, uptime, request count
cua -d <name> screen -c # verify a11y tree works
cua -d <name> screenshot 50 720 /tmp/verify.jpg  # verify screenshot

# Test auto-unlock end-to-end
cua -d <name> lock
sleep 3
cua -d <name> screen -c  # should auto-unlock then return tree
```

### Known Device-Specific Issues

**MIUI Tablets (Xiaomi Pad 5, etc.)**:
- Settings uses dual-pane layout — left panel items NOT visible in a11y tree
- Must navigate through full Settings → Apps path instead of direct Intent
- `APPLICATION_DETAILS_SETTINGS` intent opens app LIST, not specific app
- `setup-perms` may need manual fallback for tablet layout

**MIUI Phones (Redmi K60 Ultra, etc.)**:
- ICP 备案 dialog may appear during APK install — click "继续安装"
- "仍然下载" confirmation in Chrome for HTTP downloads
- Chrome downloads don't auto-open APK — go to Downloads → tap the file icon (left side)

**General Android**:
- Notification Listener requires manual enable: Settings → 通知 → 设备和应用通知 → Claw Use
- `takeScreenshot()` returns black image on lock screen (Android security)
- Lock screen a11y tree requires `flagRetrieveInteractiveWindows` (added in v1.6.2)

---

## Self-Update (OTA via LAN)

Update a device to a new APK version without ADB:

```bash
# Serve APK on LAN (from the machine with the APK)
cd /path/to/apk && python3 -m http.server 9090 &

# On the device, open browser to download
cua -d <name> intent '{"action":"android.intent.action.VIEW","uri":"http://<lan-ip>:9090/app.apk"}'

# Or via browser navigation for MIUI browser:
cua -d <name> click "浏览器"
cua -d <name> click "搜索或输入网址"
cua -d <name> type "http://<lan-ip>:9090/app.apk"
# ... then handle download + install prompts

# MIUI install flow (after APK opens in installer)
cua -d <name> flow '{
  "steps": [
    {"wait": "继续安装", "then": "tap", "timeout": 15000},
    {"wait": "已了解此应用未经安全检测", "then": "tap", "timeout": 10000, "optional": true},
    {"wait": "继续更新", "then": "tap", "timeout": 15000}
  ]
}'

# Verify new version after service restart (~30s)
sleep 30
cua -d <name> ping
```

**UpdateReceiver**: The app listens for `MY_PACKAGE_REPLACED` broadcast and auto-restarts the service after update. No manual intervention needed after install completes.

---

## Workflow Patterns

### Navigate and interact (v2.0+ recommended)
```bash
cua act '{"launch": "org.telegram.messenger"}'
cua screen -c
cua act '{"click": "Search Chats"}'
cua act '{"type": "John"}'
cua act '{"click": "John"}'
```

### Navigate and interact (legacy)
```bash
cua launch org.telegram.messenger
cua screen -c
cua click "Search Chats"
cua type "John"
cua click "John"
```

### Visual + semantic perception
```bash
cua screen -c                          # what elements exist (structured, with refs)
cua snapshot 50 720 /tmp/look.jpg      # what it looks like (visual)
```

**Prefer `screen -c` over `snapshot`** for decision-making. Structured a11y data is faster to process, has exact coordinates, and provides ref IDs for `/act`. Use snapshot only when visual context matters (images, colors, layout).

### Handle locked device
Automatic — any command auto-unlocks if PIN is configured. No special handling needed.

### MIUI APK Install (via /flow)
```bash
cua flow '{
  "steps": [
    {"wait": "继续安装", "then": "tap", "timeout": 15000},
    {"wait": "已了解此应用未经安全检测", "then": "tap", "timeout": 10000, "optional": true},
    {"wait": "继续更新", "then": "tap", "timeout": 10000}
  ]
}'
```

### Multi-device
```bash
cua add phone1 192.168.0.101 <token>
cua add tablet 192.168.0.102 <token>
cua -d phone1 say "hello from phone 1"
cua -d tablet screenshot
```

## Operational Lessons

### DO
- **Use `click` by text** instead of `tap` by coordinates whenever text is visible
- **Use `screen -c`** as the primary perception tool — compact filters noise
- **Use `/flow`** for multi-step mechanical sequences — saves tokens, 100x faster than LLM-per-step
- **Use `intent` deep links** for app navigation (e.g., `https://t.me/c/{id}/{topic}/{msg}`)
- **Use PIN unlock** — proven 100% reliable via a11y button tapping

### DON'T
- **Don't use screenshot coordinates for tapping** — `screenshot?maxWidth=720` is scaled, `screen` bounds are actual pixels
- **Don't try pattern unlock** — coordinates vary by device/OS, no reliable way to locate the grid
- **Don't rely on `tap` when `click` can work** — text-based is resolution-independent
- **Don't manually navigate app UIs when deep links exist** — error-prone and slow
- **Don't rapid-fire requests** — allow 0.5-1s between actions for UI to settle

## Architecture

```
┌─────────────────────────────────────────────┐
│              Android Device                  │
│                                              │
│  :http process          main process         │
│  ┌──────────────┐      ┌──────────────────┐ │
│  │ BridgeService│ HTTP │ AccessibilityBridge│ │
│  │ NanoHTTPD    │─────→│ A11yInternalServer│ │
│  │ 0.0.0.0:7333│proxy │ 127.0.0.1:7334   │ │
│  └──────────────┘      └──────────────────┘ │
│    ↑ auth+CORS           ↑ a11y service      │
│    ↑ auto-unlock         ↑ gesture dispatch  │
│    ↑ config/status       ↑ tree traversal    │
└────────────────────────────────────────────── ┘
         ↑ HTTP
    ┌────────────┐
    │  Agent/CLI │  cua commands / curl
    └────────────┘
```

## Family

| Platform | Package | CLI | Status |
|----------|---------|-----|--------|
| Android | claw-use-android | `cua` | ✅ Available |
| iOS | claw-use-ios | `cui` | 🔮 Planned |
| Windows | claw-use-windows | `cuw` | 🔮 Planned |
| Linux | claw-use-linux | `cul` | 🔮 Planned |
| macOS | claw-use-mac | `cum` | 🔮 Planned |
