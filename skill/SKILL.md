# Claw Use Android — Phone Control for AI Agents

Give your AI agent eyes, hands, and a voice on a real Android phone.

`claw-use-android` is an Android app + CLI (`cua`) that exposes 25 HTTP endpoints for full phone control. No ADB, no root, no PC.

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
cua config pin 123456   # set PIN for remote unlock
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

### Multi-device
```bash
cua add phone1 192.168.0.101 <token>
cua add phone2 192.168.0.102 <token>
cua -d phone1 say "hello from phone 1"
cua -d phone2 screenshot
```

## Tips

- **`cua screen -c`** is the primary perception tool — compact filters noise
- **`cua click`** by text is more reliable than `cua tap` when text is visible
- **`cua screenshot`** for visual context (layout, colors, images)
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
