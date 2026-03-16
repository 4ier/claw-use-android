# Claw Use Android — Stability & Polish Overhaul

## Context
This is an Android app that exposes a pure HTTP API (no ADB, no root) for AI agents to control a phone via AccessibilityService. It runs on MIUI/HyperOS which aggressively kills background services.

**Architecture**: Dual-process — BridgeService (:http process, NanoHTTPD on 0.0.0.0:7333) proxies a11y requests to A11yInternalServer (main process, 127.0.0.1:7334).

## Tasks (in priority order)

### 1. StatusTracker — Global metrics singleton
Create `app/src/main/java/com/clawuse/android/StatusTracker.java`:
- Singleton, thread-safe
- Track: `startTimeMs`, `requestCount` (AtomicLong), `lastRequestTimeMs`, `lastRequestPath`, `a11yAlive` (AtomicBoolean), `a11yLastCheckMs`
- Method `recordRequest(String path)` — increment count, update last request time/path
- Method `toJson()` → JSONObject with all stats + computed `uptimeSeconds`

### 2. Rich Foreground Notification
Modify `BridgeService.java`:
- Replace static notification with a **live-updating** one
- Schedule a Handler to call `updateNotification()` every 30s
- Notification content: `"🟢 Active | {IP}:{PORT} | Reqs: {N} | Up: {uptime}"` when healthy, `"🔴 A11y Down | ..."` when a11y unreachable
- Add **notification actions**: "Copy URL" and "Copy Token" using PendingIntent → BroadcastReceiver
- Create `NotificationActionReceiver.java` to handle copy actions

### 3. Heartbeat Watchdog
In `BridgeService.java`:
- Every 30s, HTTP GET `http://127.0.0.1:7334/a11y/ping` with 2s timeout
- Update `StatusTracker.a11yAlive` based on result
- If 3 consecutive failures: update notification to red, log warning
- If recovery detected: update notification to green, log info

### 4. WakeLock + WifiLock
In `BridgeService.onCreate()`:
- Acquire `PowerManager.PARTIAL_WAKE_LOCK` (tag: "clawuse:bridge") — keeps CPU alive
- Acquire `WifiManager.WifiLock(WIFI_MODE_FULL_HIGH_PERF)` (tag: "clawuse:wifi") — keeps WiFi alive during sleep
- Release both in `onDestroy()`
- Add permission `android.permission.CHANGE_WIFI_STATE` to AndroidManifest.xml

### 5. Per-Request WakeLock
In `BridgeHttpServer.serve()`:
- Before handling any authenticated request, acquire a short PARTIAL_WAKE_LOCK (5s timeout)
- Release after response is sent
- This prevents MIUI from sleeping the CPU mid-request

### 6. ConfigStore — Encrypted PIN + Settings
Create `app/src/main/java/com/clawuse/android/ConfigStore.java`:
- Uses `EncryptedSharedPreferences` (AndroidX Security) for PIN storage
- Methods: `setPin(String)`, `getPin()`, `hasPin()`, `setScreenTimeout(int)`, `getScreenTimeout()`
- Add `implementation("androidx.security:security-crypto:1.1.0-alpha06")` to build.gradle.kts

### 7. Auto-Unlock Middleware
In `BridgeService.proxyToA11y()`:
- Before proxying, check screen state via `PowerManager.isInteractive()` and `KeyguardManager.isKeyguardLocked()`
- If locked AND ConfigStore has PIN: proxy to `/a11y/screen/unlock` with stored PIN first, wait up to 5s, then proceed with original request
- If locked AND no PIN stored: return error JSON `{"error":"device locked","hint":"set PIN via POST /config or unlock manually"}`
- This makes ALL a11y endpoints work transparently even when screen is locked

### 8. POST /config Endpoint
Add to BridgeService as a local handler (no a11y dependency):
- `POST /config` with body `{"pin":"910825"}` → stores PIN in ConfigStore
- `GET /config` → returns `{"hasPin":true,"screenTimeout":30}` (never expose PIN value)
- `DELETE /config` → clears stored PIN

### 9. GET /status Endpoint
Add to BridgeService as a local handler:
- Returns comprehensive health JSON:
```json
{
  "status": "ok",
  "uptime": 3600,
  "requests": 47,
  "lastRequest": {"path": "/screen", "timeAgo": 3},
  "accessibility": {"running": true, "latencyMs": 12},
  "device": {"screenOn": true, "locked": false, "battery": 61, "charging": false},
  "network": {"ip": "192.168.0.105", "port": 7333},
  "version": "1.1.0"
}
```
- Probe a11y server with timing to get latency
- Use StatusTracker for request stats

### 10. Setup Wizard Improvements (MainActivity.java)
- Group permissions into numbered steps with clear ✅/❌ status
- Add MIUI-specific buttons:
  - "自启动设置" → `Intent("miui.intent.action.OP_AUTO_START")` or fallback to app info
  - "省电策略" → battery optimization settings
- Add a "一键检测" button that re-checks all statuses
- Show big green "READY ✅" banner when everything is configured
- Show QR code more prominently when ready

### 11. Version Bump
- Update version in InfoHandler ping response: "1.0.0" → "1.1.0"
- Add `versionName "1.1.0"` and `versionCode 2` in build.gradle.kts

## Important Notes
- NanoHTTPD 2.3.1's `parseBody()` uses ISO-8859-1 — we already fixed this by reading raw InputStream as UTF-8. Don't revert this.
- The proxy from :http→:7334 uses `HttpURLConnection` with UTF-8 encoding. Don't change this.
- All response Content-Type should be `"application/json; charset=UTF-8"`
- The `HashMap` import in A11yInternalServer.java was intentionally removed (replaced by raw InputStream reading). Don't add it back.
- `GlobalHandler.java` still uses `AccessibilityBridge.getInstance()` directly — that's fine, it runs in the main process.
- EncryptedSharedPreferences needs `minSdk 23` (we're at API 36, so fine).
- BridgeService runs in `:http` process — it cannot access AccessibilityBridge.getInstance() directly. Cross-process communication is via HTTP proxy to :7334 only.
- For KeyguardManager/PowerManager in BridgeService: these system services work in any process.
