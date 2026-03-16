# Claw Use Android — Phone Control for AI Agents

Give your AI agent eyes, hands, and a voice on a real Android phone.

`claw-use-android` is an Android app + CLI (`cua`) that exposes 26 HTTP endpoints for full phone control. No ADB, no root, no PC.

## Setup

```bash
# Install the APK on your Android phone, enable Accessibility Service
# Then register the device:
cua add redmi 192.168.0.105 <token>
cua ping
```

## CLI Reference (`cua`)

### Device Management
```bash
cua add <name> <ip> <token>    # register device with alias
cua devices                     # list all (with live status)
cua use <name>                  # switch default device
cua -d <name> <command>         # target specific device
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

### Device State
```bash
cua wake                # wake screen
cua lock / cua unlock   # lock/unlock (PIN required)
cua config pin 123456   # remember the device's lock screen PIN for remote unlock
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

## Workflow Patterns

### Navigate and interact
```bash
cua launch org.telegram.messenger
cua screen -c
cua click "Search Chats"
cua type "John"
cua click "John"
```

### Visual + semantic perception
```bash
cua screen -c                          # what elements exist
cua screenshot 50 720 /tmp/look.jpg   # what it looks like
```

### Handle locked device
Automatic — any command auto-unlocks if PIN is configured.

### MIUI APK Install (via /flow)
```bash
# After tapping an APK file in Telegram to trigger installer:
cua flow '{
  "steps": [
    {"wait": "继续安装", "then": "tap", "timeout": 15000},
    {"wait": "继续更新", "then": "tap", "timeout": 10000},
    {"waitGone": "正在安装", "timeout": 60000, "optional": true}
  ]
}'
```

### Multi-device
```bash
cua add phone1 192.168.0.101 <token>
cua add phone2 192.168.0.102 <token>
cua -d phone1 say "hello from phone 1"
cua -d phone2 screenshot
```

## Operational Recipes (learned the hard way)

### Telegram Navigation
- **DO**: Use intent deep links for navigation
  ```bash
  cua intent '{"action":"android.intent.action.VIEW","uri":"https://t.me/c/{group_id}/{topic_id}/{msg_id}"}'
  ```
- **DON'T**: Try to manually tap through Telegram's topic list — it opens profile pages instead of chats
- **Telegram forum topic chat IDs**: strip the `-100` prefix from the group ID for t.me links
- **File download in Telegram**: First tap downloads, second tap opens. Use `/click` with `retry` to handle the two-tap pattern.

### Screenshot Coordinate Mapping
- **DON'T** use screenshot pixel coordinates directly — `screenshot?maxWidth=720` is scaled down from actual screen resolution
- **DO** use `screen` (a11y tree) bounds which are in actual screen coordinates
- **DO** use `click` by text instead of `tap` by coordinates whenever text is visible

### MIUI Quirks
- MIUI package installer always asks "继续安装" then "继续更新" — use `/flow` to automate
- MIUI blocks background app launches — intent-based navigation is more reliable
- Battery optimization whitelist: app auto-requests on first launch, but user should also manually: Settings → Battery → App Battery Saver → Claw Use → No restrictions

### General Tips
- **`cua screen -c`** is the primary perception tool — compact filters noise
- **`cua click`** by text is more reliable than `cua tap` when text is visible
- **`cua screenshot`** for visual context (layout, colors, images not in a11y tree)
- **`/flow`** for any multi-step mechanical sequence — saves tokens and time
- Auto-unlock is transparent: locked phone auto-unlocks before any command
- Add [Tailscale](https://tailscale.com) for remote access from anywhere

## Family

| Platform | Package | CLI | Status |
|----------|---------|-----|--------|
| Android | claw-use-android | `cua` | ✅ Available |
| iOS | claw-use-ios | `cui` | 🔮 Planned |
| Windows | claw-use-windows | `cuw` | 🔮 Planned |
| Linux | claw-use-linux | `cul` | 🔮 Planned |
| macOS | claw-use-mac | `cum` | 🔮 Planned |
