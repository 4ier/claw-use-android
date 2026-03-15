# Claw Use Android — Full Device API Spec

## Vision
One APK turns any Android phone into a fully programmable device for AI agents.
**No root. No ADB. No PC.** Install → enable → connect. Pure HTTP API.

## Architecture

```
AI Agent (any framework)
    │ HTTP :7333
    ▼
┌──────────────────────────────────┐
│       Claw Use Android APK       │
│                                  │
│  AccessibilityService            │
│    → UI tree, gestures, global   │
│    → notification listener       │
│    → lock/unlock screen          │
│                                  │
│  ForegroundService               │
│    → HTTP server (NanoHTTPD)     │
│    → sensor polling              │
│    → keeps alive in background   │
│                                  │
│  MainActivity (thin)             │
│    → permission requests         │
│    → service enable guide        │
│    → status dashboard            │
│    → connection QR code          │
│                                  │
│  CameraManager / MediaRecorder   │
│  LocationManager / SensorManager │
│  TTS / SpeechRecognizer          │
│  ClipboardManager / etc.         │
└──────────────────────────────────┘
```

## Zero-ADB Setup

The entire setup happens on-device, no computer needed:

1. **Install**: Download APK from website or GitHub Releases
2. **Enable Accessibility Service**: App shows one-tap button → jumps to Settings
3. **Enable Notification Listener** (optional): Same one-tap flow
4. **Connect**: App displays WiFi IP + auth token + QR code

No `adb forward`, no `adb shell settings put`, no USB cable.

## Connectivity

### Binding Modes

| Mode | Bind Address | Use Case |
|------|-------------|----------|
| **Local only** | `127.0.0.1:7333` | AI runs on same device (LilClaw) |
| **LAN** (default) | `0.0.0.0:7333` | AI runs on computer in same network |

### Authentication

Every request must include `X-Bridge-Token: <token>` header (except `/ping`).

- Token auto-generated on first launch (UUID v4)
- Displayed in app UI, copyable, QR-scannable
- Can be regenerated from app settings
- `/ping` is unauthenticated (returns only `{"status":"ok"}`, no device info)

### Discovery (optional)

mDNS/Bonjour: `_claw-use._tcp.local` — AI agents can auto-discover devices on LAN.

## Build System
Gradle, standard Android project. Java (no Kotlin — minimal dependencies, small APK).
Target SDK 34, Min SDK 26 (Android 8.0+, covers 95%+ active devices).

## API Endpoints

### 1. System
| Endpoint | Method | Description |
|----------|--------|-------------|
| `/ping` | GET | Health check (unauthenticated) |
| `/info` | GET | Device model, OS version, screen size, battery, storage |
| `/permissions` | GET | List granted/denied permissions with request instructions |

### 2. UI — Screen Reading
| Endpoint | Method | Body | Description |
|----------|--------|------|-------------|
| `/screen` | GET | — | Full UI tree JSON. `?compact` for interactive-only |
| `/screenshot` | GET | — | PNG screenshot (AccessibilityService.takeScreenshot API 30+, fallback to MediaProjection) |

### 3. UI — Interaction
| Endpoint | Method | Body | Description |
|----------|--------|------|-------------|
| `/click` | POST | `{"text":"OK"}` / `{"id":"..."}` / `{"desc":"..."}` | Click element by text, resource ID, or content description |
| `/tap` | POST | `{"x":540,"y":960}` | Tap at coordinates |
| `/longpress` | POST | `{"text":"..."}` or `{"x":540,"y":960,"durationMs":1000}` | Long press element or coordinates |
| `/swipe` | POST | `{"x1":540,"y1":1800,"x2":540,"y2":600,"durationMs":300}` | Swipe gesture |
| `/type` | POST | `{"text":"hello"}` | Input text into focused field (via clipboard paste for reliability + IME support) |
| `/scroll` | POST | `{"direction":"down"}` / `{"text":"ListView","direction":"up"}` | Scroll container (finds nearest scrollable) |
| `/global` | POST | `{"action":"back"}` / `"home"` / `"recents"` / `"notifications"` / `"quick_settings"` / `"power_dialog"` | Global system actions |

### 4. Screen Lock
| Endpoint | Method | Body | Description |
|----------|--------|------|-------------|
| `/screen/lock` | POST | — | Lock screen (GLOBAL_ACTION_LOCK_SCREEN, API 28+) |
| `/screen/unlock` | POST | `{"pin":"1234"}` | Wake → swipe up → enter PIN via UI automation |
| `/screen/wake` | POST | — | Wake screen without unlocking |
| `/screen/state` | GET | — | Screen on/off, locked/unlocked status |

### 5. Camera
| Endpoint | Method | Body | Description |
|----------|--------|------|-------------|
| `/camera/photo` | POST | `{"facing":"back","width":1920,"quality":85}` | Take photo → JPEG base64 |
| `/camera/list` | GET | — | List cameras with specs (resolution, facing, capabilities) |

Permission: `CAMERA`

### 6. Audio
| Endpoint | Method | Body | Description |
|----------|--------|------|-------------|
| `/audio/record` | POST | `{"durationMs":5000}` | Record audio clip → base64 |
| `/tts/speak` | POST | `{"text":"你好","lang":"zh"}` | Text-to-speech through speaker |
| `/volume` | GET | — | Current volume levels (all streams) |
| `/volume` | POST | `{"stream":"music","level":8}` | Set volume |

Permission: `RECORD_AUDIO`

### 7. Location
| Endpoint | Method | Body | Description |
|----------|--------|------|-------------|
| `/location` | GET | `?freshness=30000` | Last known or fresh location (lat, lng, accuracy, speed, altitude, provider) |

Permission: `ACCESS_FINE_LOCATION`

### 8. Sensors
| Endpoint | Method | Body | Description |
|----------|--------|------|-------------|
| `/sensors` | GET | — | List available sensors with type, vendor, range |
| `/sensors/read` | GET | `?type=accelerometer` | Latest reading from sensor |

No permission needed.

### 9. Notifications
| Endpoint | Method | Body | Description |
|----------|--------|------|-------------|
| `/notifications` | GET | `?limit=50` | Active notifications (all apps): title, text, package, time, actions |
| `/notifications/action` | POST | `{"key":"...","actionIndex":0}` | Perform notification action button |
| `/notifications/dismiss` | POST | `{"key":"..."}` | Dismiss notification |
| `/notifications/dismiss-all` | POST | — | Dismiss all |

Requires: NotificationListenerService enabled by user.

### 10. Clipboard
| Endpoint | Method | Body | Description |
|----------|--------|------|-------------|
| `/clipboard` | GET | — | Read clipboard text |
| `/clipboard` | POST | `{"text":"..."}` | Write to clipboard |

### 11. Apps & Intents
| Endpoint | Method | Body | Description |
|----------|--------|------|-------------|
| `/apps` | GET | `?launchable=true` | List installed apps (package, label, version, icon base64) |
| `/apps/launch` | POST | `{"package":"com.bilibili.app"}` | Launch app main activity |
| `/apps/info` | GET | `?package=com.example` | Detailed app info (permissions, size, activities) |
| `/intent` | POST | `{"action":"VIEW","data":"https://...","type":"text/plain"}` | Fire arbitrary intent |

### 12. Phone & SMS
| Endpoint | Method | Body | Description |
|----------|--------|------|-------------|
| `/phone/call` | POST | `{"number":"10086"}` | Initiate phone call |
| `/sms/send` | POST | `{"to":"10086","text":"hello"}` | Send SMS |
| `/sms/list` | GET | `?limit=20&after=timestamp` | Read SMS inbox |

Permission: `CALL_PHONE`, `SEND_SMS`, `READ_SMS`

### 13. Contacts & Calendar
| Endpoint | Method | Body | Description |
|----------|--------|------|-------------|
| `/contacts` | GET | `?query=张&limit=10` | Search contacts (name, phone, email) |
| `/calendar/events` | GET | `?days=7` | Upcoming calendar events |

Permission: `READ_CONTACTS`, `READ_CALENDAR`

### 14. Files
| Endpoint | Method | Body | Description |
|----------|--------|------|-------------|
| `/files/list` | GET | `?path=/sdcard/DCIM` | List directory contents |
| `/files/read` | GET | `?path=/sdcard/file.txt` | Read file (text or base64 for binary) |
| `/files/write` | POST | `{"path":"...","content":"...","encoding":"utf8"}` | Write file |
| `/files/media` | GET | `?type=images&limit=10` | Recent media via MediaStore |

Permission: `READ_MEDIA_IMAGES` etc. (Android 13+) or `READ_EXTERNAL_STORAGE`

### 15. Device Control
| Endpoint | Method | Body | Description |
|----------|--------|------|-------------|
| `/vibrate` | POST | `{"pattern":[0,200,100,200]}` | Vibrate with pattern |
| `/brightness` | GET/POST | `{"level":128}` | Screen brightness (0-255) |
| `/torch` | POST | `{"on":true}` | Flashlight on/off |

## Security

### Network
- Default: binds `0.0.0.0:7333` (LAN accessible)
- All endpoints except `/ping` require `X-Bridge-Token` header
- Token displayed in app, never transmitted — user copies to agent config

### Sensitive Operations
High-risk endpoints require **confirmation on first use** (one-time popup on phone):
- `/screen/unlock` — "Allow AI to unlock your screen?"
- `/sms/send` — "Allow AI to send SMS?"
- `/phone/call` — "Allow AI to make phone calls?"
- `/files/write` — "Allow AI to write files?"
After confirmation, the permission persists until revoked in app settings.

### Data
- No data leaves the device except via HTTP responses to authenticated requests
- No analytics, no cloud, no telemetry
- Token can be regenerated to revoke all access

## Permission Strategy

Permissions requested **on demand**, not at install:
1. App starts with zero permissions — only AccessibilityService needed
2. First API call to `/camera/*` triggers runtime permission dialog on phone
3. `GET /permissions` tells the agent what's available and what needs granting
4. Agent can guide user: "Please tap 'Allow' on your phone screen"

## MainActivity UI

Minimal, functional:

```
┌─────────────────────────────────┐
│  Claw Use Android        v1.0  │
│─────────────────────────────────│
│                                 │
│  ● Accessibility Service   [ON] │
│  ○ Notification Listener  [OFF] │
│                                 │
│  ─────────────────────────────  │
│  Bridge: http://192.168.1.42:7333│
│  Token: a3f8c9...   [Copy][QR] │
│  ─────────────────────────────  │
│                                 │
│  Permissions:                   │
│  ✅ Camera  ✅ Location         │
│  ❌ SMS     ❌ Contacts         │
│  ❌ Files   ❌ Microphone       │
│                                 │
│  Recent: 142 requests today     │
│  ─────────────────────────────  │
│  [Reset Token]  [Revoke Access] │
└─────────────────────────────────┘
```

## Project Structure

```
app/
  src/main/
    java/com/clawuse/android/
      MainActivity.java              — Setup UI, permissions, QR code
      BridgeService.java             — ForegroundService + NanoHTTPD HTTP server
      AccessibilityBridge.java       — AccessibilityService (UI + gestures + global + lock/unlock)
      NotificationBridge.java        — NotificationListenerService
      auth/
        TokenManager.java            — Token generation, validation, storage
      handlers/
        ScreenHandler.java           — /screen, /screenshot
        GestureHandler.java          — /click, /tap, /swipe, /longpress, /scroll, /type
        GlobalHandler.java           — /global, /screen/lock, /screen/unlock, /screen/wake, /screen/state
        CameraHandler.java           — /camera/*
        AudioHandler.java            — /audio/*, /tts/*, /volume
        LocationHandler.java         — /location
        SensorHandler.java           — /sensors/*
        NotificationHandler.java     — /notifications/*
        ClipboardHandler.java        — /clipboard
        AppHandler.java              — /apps/*, /intent
        PhoneHandler.java            — /phone/*, /sms/*
        ContactHandler.java          — /contacts, /calendar/*
        FileHandler.java             — /files/*
        DeviceHandler.java           — /vibrate, /brightness, /torch
        InfoHandler.java             — /ping, /info, /permissions
    res/
      xml/accessibility_config.xml
      layout/activity_main.xml
      values/strings.xml
    AndroidManifest.xml
  build.gradle.kts
build.gradle.kts
settings.gradle.kts
gradle.properties
```

## Non-Goals (v1)
- No built-in AI / LLM — this is infrastructure, not an agent
- No Device Owner / enterprise features — no factory reset needed
- No cloud relay — use OpenClaw device-pair for remote access
- No Kotlin — Java keeps deps minimal and APK small
- No complex UI — one screen, functional

## Inherited from a11y-bridge
- Core AccessibilityService architecture (UI tree reading, click by text/id/desc, gesture dispatch)
- localhost HTTP server pattern
- JSON request/response format
- MIT license
