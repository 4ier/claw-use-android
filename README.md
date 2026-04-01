# 🤖 Claw Use Android

**The Android implementation of [Claw Use](https://github.com/claw-use/claw-use-android#claw-use-protocol) — a protocol for AI agents to control real devices.**

One app. Three core endpoints. Full phone control over HTTP. No ADB. No root. No PC.

```bash
# 1. See the screen — semantic UI tree with refs
curl http://phone:7333/screen -H "X-Bridge-Token: $TOKEN"
# → {"package":"com.whatsapp","elements":[{"ref":1,"text":"Search","role":"button","click":true}, ...]}

# 2. Act on what you see
curl -X POST http://phone:7333/act -H "X-Bridge-Token: $TOKEN" \
  -d '{"click": 1}'       # click ref 1
# → {"ref":1,"ok":true,"x":610,"y":280,"text":"Search"}

# 3. Observe the result
curl http://phone:7333/screen -H "X-Bridge-Token: $TOKEN"
# → new UI tree with new refs
```

That's the core loop: **screen → act → screen**. No coordinate guessing, no pixel parsing.

## Flow-First: Learned Automation

Agents get faster over time. The pattern:

```
1. Check flows.md — a library of learned UI sequences
2. Match found? → Run via /flow (device-side, 100ms polling, zero LLM cost)
3. Flow fails or no match? → Fall back to screen → act loop
4. Task done? → Save the new sequence to flows.md for next time
```

Example: installing an APK on MIUI takes 5+ dialogs. First time, the agent navigates each one via screen→act (slow, ~40s). After that, it's a single `/flow` call:

```bash
curl -X POST http://phone:7333/flow -H "X-Bridge-Token: $TOKEN" \
  -d '{"steps":[
    {"wait":"继续安装","then":"tap","timeout":15000},
    {"wait":"已了解此应用未经安全检测","then":"tap","timeout":10000,"optional":true},
    {"wait":"继续更新","then":"tap","timeout":15000}
  ]}'
# Runs entirely on-device. No LLM calls. Completes in seconds.
```

`/flow` executes on the phone itself — polling the accessibility tree at 100ms intervals and reacting instantly when target elements appear. The agent skill includes a `flows.md` file that accumulates these patterns over time.

## What's New in v2.0.0

Three unified endpoints replace the old scattered API for agent workflows:

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/screen` | GET | Semantic UI tree with stable `ref` IDs, `zone`, `role` |
| `/snapshot` | GET | JPEG screenshot (base64) |
| `/act` | POST | Unified action: click ref/text, tap, type, swipe, scroll, back, home, launch |

All legacy endpoints (`/click`, `/tap`, `/swipe`, `/type`, `/scroll`, `/global`, `/screenshot`, etc.) remain supported.

### POST /act — All Actions in One

```bash
# Click by ref (preferred — fast, precise)
{"click": 3}

# Click by text (fallback — searches UI tree)
{"click": "Send"}

# Click multiple refs in sequence
{"click": [1, 2, 3]}

# Tap coordinates
{"tap": {"x": 540, "y": 960}}

# Type text (into focused field, or focus ref first)
{"type": "Hello world"}
{"type": {"ref": 5, "text": "Hello world"}}

# Swipe / scroll
{"swipe": "up"}
{"scroll": "down"}

# Navigation
{"back": true}
{"home": true}
{"recents": true}

# Launch app
{"launch": "com.whatsapp"}

# Long press
{"longpress": 3}

# Multiple actions in one request
{"home": true, "back": true}
```

### GET /screen — Semantic UI Tree

```json
{
  "package": "com.android.settings",
  "elements": [
    {"ref": 1, "text": "Settings", "zone": "header"},
    {"ref": 2, "text": "Search", "zone": "header", "role": "button", "click": true},
    {"ref": 3, "text": "WLAN", "zone": "content"},
    {"ref": 4, "text": "Bluetooth", "zone": "content"}
  ]
}
```

Query params:
- `compact=true` — only interactive/text elements
- `timeout=5000` — max ms to wait for accessibility tree

## Use Cases

- **AI agent with a real phone**: Your agent can send messages, check apps, take screenshots, and speak — on a real device with real accounts
- **Revive broken phones**: USB port dead? Screen cracked? If WiFi works, Claw Use gives the phone a second life
- **Remote phone access**: Add Tailscale and control your phone from anywhere in the world
- **Spare phone automation**: Turn that old phone in your drawer into a dedicated AI worker
- **Testing & QA**: Automate real-device testing without emulators

## Why This Exists

Every phone control solution requires a PC running ADB. **This one doesn't.**

Install the app → enable Accessibility Service → your phone is now an HTTP-controlled device. Connect from anywhere on the same network. Add [Tailscale](https://tailscale.com) and control it from anywhere in the world.

Built for AI agents that need a real phone — not an emulator, not a cloud device, **your actual phone** with your actual apps, accounts, and data.

## Full Endpoint Reference

### 👀 Perception
| Endpoint | Method | What it does |
|----------|--------|-------------|
| `/screen` | GET | **Semantic UI tree** — elements with ref IDs, zone, role (v2.0) |
| `/snapshot` | GET | **JPEG screenshot** as base64 (v2.0) |
| `/screenshot` | GET | Screenshot (legacy, same format) |
| `/notifications` | GET | All notifications with title, text, actions |
| `/info` | GET | Device model, OS, screen size, permissions |
| `/status` | GET | Full health dashboard (uptime, request count, a11y latency) |

### 🎯 Action
| Endpoint | Method | What it does |
|----------|--------|-------------|
| `/act` | POST | **Unified action** — click/tap/type/swipe/scroll/nav/launch (v2.0) |
| `/tap` | POST | Tap at coordinates (legacy) |
| `/click` | POST | Tap by text/desc/id (legacy) |
| `/longpress` | POST | Long press (legacy) |
| `/swipe` | POST | Swipe direction (legacy) |
| `/scroll` | POST | Scroll direction (legacy) |
| `/type` | POST | Type text (legacy) |
| `/global` | POST | Back, home, recents, notifications, power dialog |
| `/launch` | GET/POST | List installed apps / launch by package name |
| `/intent` | POST | Fire any Android Intent |

### 🔊 Audio
| Endpoint | Method | What it does |
|----------|--------|-------------|
| `/tts` | POST | Speak text through the phone speaker |
| `/tts/voices` | GET | List available TTS voices |
| `/audio/record` | POST | Record audio from microphone |

### 📱 Device I/O
| Endpoint | Method | What it does |
|----------|--------|-------------|
| `/clipboard` | GET/POST | Read or write clipboard text |
| `/camera` | POST | Capture photo (front/back) |
| `/volume` | GET/POST | Read/set volume |
| `/battery` | GET | Battery level, charging, temperature |
| `/wifi` | GET | WiFi info (SSID, IP, signal) |
| `/location` | GET | GPS/network location |
| `/vibrate` | POST | Vibrate (one-shot or pattern) |
| `/contacts` | GET | Search and list contacts |
| `/sms` | GET/POST | Read/send SMS |
| `/file` | GET/POST/DELETE | File operations |

### ⚡ Batch & Flow
| Endpoint | Method | What it does |
|----------|--------|-------------|
| `/batch` | POST | Multiple operations in one request |
| `/flow` | POST | Multi-step automation with conditions |

### 🔒 Security & Device
| Endpoint | Method | What it does |
|----------|--------|-------------|
| `/screen/wake` | POST | Wake the screen |
| `/screen/lock` | POST | Lock the device |
| `/screen/unlock` | POST | Unlock with PIN |
| `/config` | GET/POST/DELETE | Configure PIN for remote unlock |
| `/ping` | GET | Health check (no auth required) |

### 🔐 Authentication
All endpoints (except `/ping`) require a token:
```
X-Bridge-Token: <your-token>
```
Token is generated on first launch and shown in the setup screen + notification bar.

## CLI (`cua`)

```bash
# Device management
cua add redmi 192.168.0.105 <token>
cua devices
cua discover                    # scan local network

# New unified commands (v2.0)
cua screen                      # semantic UI tree
cua screen -c                   # compact (text/interactive only)
cua snapshot                    # save screenshot
cua act '{"click": 3}'         # click ref 3
cua act '{"type": "hello"}'    # type text
cua act '{"swipe": "up"}'      # swipe

# Legacy commands (still supported)
cua tap 500 1000
cua click "Send"
cua swipe up
cua type "Hello"
cua screenshot

# Full setup
cua onboard                     # discover → register → PIN → perms → verify
cua setup-perms                 # grant MIUI permissions
```

## Quick Start

### 1. Install
Download the APK from [Releases](https://github.com/claw-use/claw-use-android/releases) and install it.

### 2. Enable Accessibility Service
`Settings → Accessibility → Claw Use → Enable`

### 3. (Optional) Enable Notification Listener
`Settings → Notifications → Notification Access → Claw Use`

### 4. Connect
```bash
curl http://<phone-ip>:7333/ping
# → {"status":"ok","service":"claw-use-android","version":"2.0.0"}
```

### 5. Tell It Your PIN (for Remote Unlock)
```bash
curl -X POST http://<phone-ip>:7333/config \
  -H "X-Bridge-Token: <token>" \
  -d '{"pin":"your-existing-pin"}'
```

## Remote Access (Tailscale)

Install [Tailscale](https://play.google.com/store/apps/details?id=com.tailscale.ipn) on the phone. Your phone gets a stable `100.x.x.x` address accessible from anywhere.

```bash
curl http://100.x.x.x:7333/screen -H "X-Bridge-Token: <token>"
```

No port forwarding. No dynamic DNS. Just works.

## Self-Update Loop

The app includes an `UpdateReceiver` that listens for `MY_PACKAGE_REPLACED`. After installing a new version, the BridgeService automatically restarts — no manual app launch needed.

This enables fully autonomous OTA updates: an AI agent can build a new APK, send it to the phone, navigate to download it, tap through the installer, and regain control after the update completes. **Zero human intervention.**

## MIUI/HyperOS Survival Guide

Xiaomi's aggressive battery optimization will kill background services. To keep Claw Use alive:

1. **Battery saver**: Set to "No restrictions" for Claw Use
2. **Autostart**: Enable in Security → Autostart
3. **Lock in recents**: Open Claw Use → long press in recent apps → tap the lock icon
4. **Battery optimization**: The app auto-requests exemption on launch

## Architecture

```
┌──────────────────────────────────────┐
│         BridgeService                │
│         0.0.0.0:7333                 │
│                                      │
│  Auth · CORS · Auto-unlock · Routing │
│                                      │
│  ┌────────────┐  ┌───────────────┐   │
│  │ScreenHandler│  │  ActHandler   │   │
│  │ /screen     │  │  /act         │   │
│  │ /snapshot   │  │  unified ops  │   │
│  └─────┬──────┘  └──────┬────────┘   │
│        │                │            │
│  ┌─────▼────────────────▼────────┐   │
│  │    AccessibilityBridge        │   │
│  │    UI tree · Gestures · TTS   │   │
│  └───────────────────────────────┘   │
│  WakeLock + WifiLock + Foreground    │
└──────────────────────────────────────┘
```

## Requirements

- Android 7.0+ (API 24) for core features
- Android 11+ (API 30) for `/snapshot` and `/screenshot`
- No root required
- No ADB required
- No PC required

## Building from Source

```bash
git clone https://github.com/claw-use/claw-use-android.git
cd claw-use-android
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

## Claw Use Protocol

Claw Use Android is the first implementation of the **Claw Use protocol** — a standard HTTP API for AI agents to control physical devices.

The protocol defines a common set of endpoints that any device can implement. The same `cua` CLI and agent skills work across all compliant devices:

```bash
cua add redmi 192.168.0.105 <token>     # Android phone
cua add ipad 100.80.1.10 <token>        # future: iOS
cua add laptop 100.80.1.20 <token>      # future: desktop
cua -d redmi screen                      # same command, any device
```

## License

MIT

---

*Built for agents that need a real phone.*
