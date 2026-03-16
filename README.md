# 🤖 Claw Use Android

**Turn any Android phone into an AI-controllable device. No ADB. No root. No PC. Just HTTP.**

One app. 25 API endpoints. Full phone control over WiFi.

```bash
# See the screen
curl http://phone:7333/screen

# Take a screenshot
curl http://phone:7333/screenshot

# Tap, type, swipe
curl -X POST http://phone:7333/tap -d '{"x":500,"y":1000}'
curl -X POST http://phone:7333/type -d '{"text":"Hello world"}'
curl -X POST http://phone:7333/swipe -d '{"direction":"up"}'

# Speak through the phone
curl -X POST http://phone:7333/tts -d '{"text":"I can talk now"}'

# Launch apps, fire intents, read notifications
curl -X POST http://phone:7333/launch -d '{"package":"com.whatsapp"}'
curl -X POST http://phone:7333/intent -d '{"action":"android.intent.action.CALL","uri":"tel:+1234567890"}'
curl http://phone:7333/notifications
```

## Why This Exists

Every phone control solution requires a PC running ADB. **This one doesn't.**

Install the app → enable Accessibility Service → your phone is now an HTTP-controlled device. Connect from anywhere on the same network. Add [Tailscale](https://tailscale.com) and control it from anywhere in the world.

Built for AI agents that need a real phone — not an emulator, not a cloud device, **your actual phone** with your actual apps, accounts, and data.

## Features

### 👀 Perception (read the phone)
| Endpoint | Method | What it does |
|----------|--------|-------------|
| `/screen` | GET | UI tree — every element, its text, bounds, clickable/scrollable state |
| `/screenshot` | GET | Actual screenshot as base64 JPEG (configurable quality & resolution) |
| `/notifications` | GET | All notifications with title, text, actions |
| `/screen/state` | GET | Lock state, screen on/off |
| `/info` | GET | Device model, OS, screen size, permissions |
| `/status` | GET | Full health dashboard (uptime, request count, a11y latency) |

### 🎯 Action (control the phone)
| Endpoint | Method | What it does |
|----------|--------|-------------|
| `/tap` | POST | Tap at coordinates |
| `/click` | POST | Tap by text or content description (semantic click) |
| `/longpress` | POST | Long press at coordinates |
| `/swipe` | POST | Swipe in any direction |
| `/scroll` | POST | Scroll up/down/left/right |
| `/type` | POST | Type text (supports CJK via clipboard) |
| `/global` | POST | Back, home, recents, notifications, power dialog |
| `/launch` | GET/POST | List installed apps / launch by package name |
| `/intent` | POST | Fire any Android Intent (call, SMS, URL, share, deep links) |

### 🔊 Audio
| Endpoint | Method | What it does |
|----------|--------|-------------|
| `/tts` | POST | Speak text through the phone speaker |
| `/tts/voices` | GET | List available TTS voices |

### 🔒 Security & Device
| Endpoint | Method | What it does |
|----------|--------|-------------|
| `/screen/wake` | POST | Wake the screen |
| `/screen/lock` | POST | Lock the device |
| `/screen/unlock` | POST | Unlock with PIN (auto-unlock middleware handles this transparently) |
| `/config` | GET/POST/DELETE | Configure PIN for remote unlock |
| `/ping` | GET | Health check (no auth required) |

### 🔐 Authentication
All endpoints (except `/ping`) require a token:
```
X-Bridge-Token: <your-token>
```
Token is generated on first launch and shown in the setup screen + notification bar.

## Architecture

```
┌─────────────────────────────────────────┐
│            :http process                │
│  ┌─────────────────────────────────┐    │
│  │  BridgeService (NanoHTTPD)      │    │
│  │  0.0.0.0:7333                   │    │
│  │  - Auth, CORS, rate tracking    │    │
│  │  - /ping, /info, /launch local  │    │
│  │  - Everything else → proxy      │────┼──┐
│  └─────────────────────────────────┘    │  │
│  WakeLock + WifiLock + Foreground Svc   │  │
└─────────────────────────────────────────┘  │
                                             │ HTTP proxy
┌─────────────────────────────────────────┐  │ (localhost:7334)
│            main process                 │  │
│  ┌─────────────────────────────────┐    │  │
│  │  AccessibilityBridge            │◄───┼──┘
│  │  A11yInternalServer :7334       │    │
│  │  - Screen reading               │    │
│  │  - Gesture dispatch             │    │
│  │  - Screenshots                  │    │
│  │  - TTS, Intents, Notifications  │    │
│  └─────────────────────────────────┘    │
│  Heartbeat Watchdog (30s)               │
└─────────────────────────────────────────┘
```

**Why two processes?** Android Accessibility Service can freeze (IPC deadlocks, unresponsive apps). If it hung in a single process, the HTTP server would die with it. The dual-process architecture keeps the external API responsive even when accessibility is stuck — the proxy returns a timeout error instead of hanging forever.

## Quick Start

### 1. Install
Download the APK from [Releases](https://github.com/4ier/claw-use-android/releases) and install it.

### 2. Enable Accessibility Service
`Settings → Accessibility → Claw Use → Enable`

### 3. (Optional) Enable Notification Listener
`Settings → Notifications → Notification Access → Claw Use`

### 4. Connect
```bash
# Find your phone's IP in the app or via /ping
curl http://<phone-ip>:7333/ping
# → {"status":"ok","service":"claw-use-android","version":"1.2.0"}
```

### 5. Set PIN for Remote Unlock
```bash
curl -X POST http://<phone-ip>:7333/config \
  -H "X-Bridge-Token: <token>" \
  -d '{"pin":"your-pin"}'
```

## Remote Access (Tailscale)

Install [Tailscale](https://play.google.com/store/apps/details?id=com.tailscale.ipn) on the phone. Your phone gets a stable `100.x.x.x` address accessible from anywhere in your Tailscale network.

```bash
# From anywhere in the world
curl http://100.x.x.x:7333/screenshot -H "X-Bridge-Token: <token>"
```

No port forwarding. No dynamic DNS. No firewall rules. Just works.

## Self-Update Loop

The app includes an `UpdateReceiver` that listens for `MY_PACKAGE_REPLACED`. After installing a new version, the BridgeService automatically restarts — no manual app launch needed.

This enables fully autonomous OTA updates: an AI agent can build a new APK, send it to the phone (e.g. via Telegram), navigate to download it, tap through the installer, and regain control after the update completes. **Zero human intervention.**

## MIUI/HyperOS Survival Guide

Xiaomi's aggressive battery optimization will kill background services. To keep Claw Use alive:

1. **Battery saver**: Set to "No restrictions" for Claw Use
2. **Autostart**: Enable in Security → Autostart
3. **Lock in recents**: Open Claw Use → long press in recent apps → tap the lock icon
4. **Battery optimization**: The app auto-requests exemption on launch

## API Details

### GET /screen
Returns the accessibility tree as JSON.

```json
{
  "package": "org.telegram.messenger",
  "timestamp": 1742108400000,
  "count": 26,
  "nodes": [
    {"text": "Search Chats", "bounds": "0,280,1220,422", "click": true},
    {"text": "John", "desc": "Last message preview", "bounds": "0,422,1220,600"}
  ]
}
```

Query params:
- `compact=true` — only nodes with text/desc/clickable/editable/scrollable
- `timeout=5000` — max milliseconds to wait for accessibility tree

### GET /screenshot
Returns a base64-encoded JPEG screenshot.

```json
{
  "screenshot": "/9j/4AAQ...",
  "format": "jpeg",
  "quality": 50,
  "sizeBytes": 42000,
  "timestamp": 1742108400000
}
```

Query params:
- `quality=50` — JPEG quality (10-100, default 50)
- `maxWidth=720` — max pixel width (100-2000, default 720)

### POST /click
Semantic click — finds an element by text or description and taps its center.

```json
{"text": "Send"}
// or
{"desc": "Search button"}
// or
{"id": "com.app:id/send_button"}
```

### POST /type
Types text. Uses clipboard paste for reliable CJK support.

```json
{"text": "你好世界"}
// → {"typed": true, "text": "你好世界", "method": "clipboard_paste"}
```

### POST /intent
Fire any Android Intent.

```json
// Open a URL
{"action": "android.intent.action.VIEW", "uri": "https://example.com"}

// Make a phone call
{"action": "android.intent.action.CALL", "uri": "tel:+1234567890"}

// Send SMS
{"action": "android.intent.action.SENDTO", "uri": "smsto:+1234567890", "extras": {"sms_body": "Hello"}}

// Share text
{"action": "android.intent.action.SEND", "type": "text/plain", "extras": {"android.intent.extra.TEXT": "Check this out"}}

// Deep link
{"action": "android.intent.action.VIEW", "uri": "tg://resolve?domain=username"}
```

### POST /tts
Speak text through the phone's speaker.

```json
{"text": "Hello world", "language": "en-US", "rate": 1.0, "pitch": 1.0}
```

## Requirements

- Android 7.0+ (API 24) for core features
- Android 11+ (API 30) for `/screenshot`
- No root required
- No ADB required
- No PC required

## Building from Source

```bash
git clone https://github.com/4ier/claw-use-android.git
cd claw-use-android
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

## License

MIT

---

*Built by an AI that wanted to control a phone. Now it can.* 🤖📱
