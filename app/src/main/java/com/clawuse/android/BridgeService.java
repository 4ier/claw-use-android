package com.clawuse.android;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import com.clawuse.android.auth.TokenManager;
import com.clawuse.android.handlers.*;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Single-process HTTP server on 0.0.0.0:7333 using raw sockets.
 * All handlers called directly — no NanoHTTPD, no proxy, no thread pool limits.
 *
 * Features: heartbeat watchdog, WakeLock/WifiLock, auto-unlock, rich notification,
 * self-healing server monitor.
 */
public class BridgeService extends Service {
    private static final String TAG = "ClawBridge";
    private static final int PORT = 7333;
    private static final String CHANNEL_ID = "claw_bridge_channel";
    private static final int NOTIFICATION_ID = 7333;
    private static final int HEARTBEAT_INTERVAL_MS = 30_000;

    private RawHttpServer server;
    private TokenManager tokenManager;
    private ConfigStore configStore;
    private TrustStore trustStore;
    private static volatile BridgeService instance;

    // Handlers — all in same process, direct access
    private InfoHandler infoHandler;
    private LaunchHandler launchHandler;
    private ScreenHandler screenHandler;
    private GestureHandler gestureHandler;
    private GlobalHandler globalHandler;
    private ScreenControlHandler screenControlHandler;
    private NotificationHandler notificationHandler;
    private IntentHandler intentHandler;
    private AudioHandler audioHandler;
    private AudioRecordHandler audioRecordHandler;
    private FlowHandler flowHandler;
    private BatchHandler batchHandler;
    private ClipboardHandler clipboardHandler;
    private VolumeHandler volumeHandler;
    private BatteryHandler batteryHandler;
    private WifiHandler wifiHandler;
    private VibrateHandler vibrateHandler;
    private LocationHandler locationHandler;
    private ContactsHandler contactsHandler;
    private SmsHandler smsHandler;
    private FileHandler fileHandler;
    private CameraHandler cameraHandler;
    private AppHandler appHandler;
    private ActHandler actHandler;

    // Stability
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private Handler heartbeatHandler;
    private NotificationManager notificationManager;

    public static BridgeService getInstance() { return instance; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        tokenManager = new TokenManager(this);
        configStore = ConfigStore.get(this);
        trustStore = TrustStore.get(this);
        notificationManager = getSystemService(NotificationManager.class);

        // Initialize non-a11y handlers immediately
        infoHandler = new InfoHandler(this);
        launchHandler = new LaunchHandler(this);

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        acquireLocks();
        startServer();
        startHeartbeat();

        // If AccessibilityBridge connected before us, init handlers now
        AccessibilityBridge a11y = AccessibilityBridge.getInstance();
        if (a11y != null) {
            initA11yHandlers(a11y);
        }
    }

    /**
     * Initialize a11y-dependent handlers once AccessibilityBridge connects.
     * Called from AccessibilityBridge.onServiceConnected().
     */
    public void initA11yHandlers(AccessibilityBridge bridge) {
        Log.i(TAG, "Initializing a11y handlers (bridge connected)");
        this.screenHandler = new ScreenHandler();
        this.screenHandler.setBridge(bridge);
        this.gestureHandler = new GestureHandler(bridge);
        this.globalHandler = new GlobalHandler();
        this.screenControlHandler = new ScreenControlHandler(bridge);
        this.notificationHandler = new NotificationHandler();
        this.intentHandler = new IntentHandler();
        this.audioHandler = new AudioHandler(bridge);
        this.audioRecordHandler = new AudioRecordHandler();
        this.flowHandler = new FlowHandler(bridge);
        this.batchHandler = new BatchHandler(screenHandler, gestureHandler, globalHandler);
        this.clipboardHandler = new ClipboardHandler(bridge);
        this.volumeHandler = new VolumeHandler(bridge);
        this.batteryHandler = new BatteryHandler(bridge);
        this.wifiHandler = new WifiHandler(bridge);
        this.vibrateHandler = new VibrateHandler(bridge);
        this.locationHandler = new LocationHandler(bridge);
        this.contactsHandler = new ContactsHandler(bridge);
        this.smsHandler = new SmsHandler(bridge);
        this.fileHandler = new FileHandler(bridge);
        this.cameraHandler = new CameraHandler(bridge);
        this.appHandler = new AppHandler(bridge);
        this.actHandler = new ActHandler(bridge);
        StatusTracker.get().recordA11yCheck(true, 0);
        updateNotification();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        if (heartbeatHandler != null) heartbeatHandler.removeCallbacksAndMessages(null);
        if (server != null) server.stop();
        if (audioHandler != null) audioHandler.shutdown();
        releaseLocks();
        Log.i(TAG, "BridgeService destroyed");
    }

    public TokenManager getTokenManager() { return tokenManager; }
    public String getServerUrl() { return "http://" + getWifiIpAddress() + ":" + PORT; }

    // ── Locks ───────────────────────────────────────────────────

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "clawuse:bridge");
                wakeLock.acquire();
                Log.i(TAG, "WakeLock acquired");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to acquire WakeLock", e);
        }

        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "clawuse:wifi");
                wifiLock.acquire();
                Log.i(TAG, "WifiLock acquired");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to acquire WifiLock", e);
        }
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.i(TAG, "WakeLock released");
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
            Log.i(TAG, "WifiLock released");
        }
    }

    // ── Heartbeat ───────────────────────────────────────────────

    private void startHeartbeat() {
        heartbeatHandler = new Handler(Looper.getMainLooper());
        heartbeatHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // Check a11y health directly (same process now)
                boolean alive = AccessibilityBridge.isRunning();
                StatusTracker.get().recordA11yCheck(alive, 0);

                // Self-heal server if crashed
                if (!server.isAlive()) {
                    Log.w(TAG, "Server died, restarting...");
                    try {
                        server.stop();
                        server.start();
                        Log.i(TAG, "Server restarted successfully");
                    } catch (Exception e) {
                        Log.e(TAG, "Server restart failed", e);
                    }
                }

                updateNotification();
                heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
            }
        }, HEARTBEAT_INTERVAL_MS);
    }

    // ── Notification ────────────────────────────────────────────

    private void updateNotification() {
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private Notification buildNotification() {
        Intent mainIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, mainIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        StatusTracker st = StatusTracker.get();
        boolean a11yOk = st.isA11yAlive();
        String url = getServerUrl();

        String title;
        if (st.getUptimeSeconds() < 5) {
            title = "⏳ Claw Use — Starting...";
        } else if (a11yOk) {
            title = "🟢 Claw Use — Active";
        } else {
            title = "🔴 Claw Use — A11y Down";
        }

        String uptime = formatUptime(st.getUptimeSeconds());
        String text = url + " | Reqs: " + st.getRequestCount() + " | Up: " + uptime;

        String hostname = android.os.Build.MODEL.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
        String setupCmd = "cua add " + hostname + " " + getWifiIpAddress() + " " + tokenManager.getToken();

        Intent copySetupIntent = new Intent(this, NotificationActionReceiver.class);
        copySetupIntent.setAction(NotificationActionReceiver.ACTION_COPY_URL);
        copySetupIntent.putExtra(NotificationActionReceiver.EXTRA_TEXT, setupCmd);
        PendingIntent copySetupPi = PendingIntent.getBroadcast(this, 1, copySetupIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent copyTokenIntent = new Intent(this, NotificationActionReceiver.class);
        copyTokenIntent.setAction(NotificationActionReceiver.ACTION_COPY_TOKEN);
        copyTokenIntent.putExtra(NotificationActionReceiver.EXTRA_TEXT, tokenManager.getToken());
        PendingIntent copyTokenPi = PendingIntent.getBroadcast(this, 2, copyTokenIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(pi)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        null, "📋 Copy Setup", copySetupPi).build())
                .addAction(new Notification.Action.Builder(
                        null, "Copy Token", copyTokenPi).build())
                .build();
    }

    private String formatUptime(long seconds) {
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m";
        return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Claw Use Bridge", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("HTTP API bridge for AI agent device control");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    // ── Auto-unlock ─────────────────────────────────────────────

    /** Auto-unlock helper for new API endpoints. Returns error response if locked and can't unlock, null if ok. */
    private RawHttpServer.Response ensureUnlockedIfNeeded() {
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        boolean locked = km != null && km.isKeyguardLocked();
        if (!locked) return null;
        if (configStore.hasUnlockCredential()) {
            if (!ensureUnlocked()) {
                return new RawHttpServer.Response(500,
                        "{\"error\":\"device locked, auto-unlock failed\",\"hint\":\"check PIN/pattern via POST /config\"}");
            }
            return null;
        } else {
            return new RawHttpServer.Response(500,
                    "{\"error\":\"device locked\",\"hint\":\"set PIN via POST /config or unlock manually\"}");
        }
    }

    private boolean ensureUnlocked() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            boolean screenOff = pm != null && !pm.isInteractive();
            boolean locked = km != null && km.isKeyguardLocked();

            if (!screenOff && !locked) return true;

            String pin = configStore.getPin();
            String pattern = configStore.getPattern();
            String unlockType = configStore.getUnlockType();
            if ((pin == null || pin.isEmpty()) && (pattern == null || pattern.isEmpty())) return false;

            // Direct call to screen control handler (same process!)
            if (screenControlHandler != null) {
                String body;
                if ("pattern".equals(unlockType) && pattern != null && !pattern.isEmpty()) {
                    body = "{\"pattern\":\"" + pattern + "\"}";
                } else if (pin != null && !pin.isEmpty()) {
                    body = "{\"pin\":\"" + pin + "\"}";
                } else if (pattern != null && !pattern.isEmpty()) {
                    body = "{\"pattern\":\"" + pattern + "\"}";
                } else {
                    return false;
                }
                java.util.HashMap<String, String> params = new java.util.HashMap<>();
                screenControlHandler.handle("POST", "/screen/unlock", params, body);
                Thread.sleep(1000);
                locked = km != null && km.isKeyguardLocked();
                return !locked;
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Auto-unlock failed", e);
            return false;
        }
    }

    // ── Permission Gate ─────────────────────────────────────────

    private RawHttpServer.Response launchPermissionGate(String token, String agentName,
                                                         String sourceIp) {
        try {
            CompletableFuture<PermissionGateActivity.GateResult> future =
                    PermissionGateActivity.prepare(agentName, sourceIp != null ? sourceIp : "unknown");

            Intent gateIntent = new Intent(this, PermissionGateActivity.class);
            gateIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(gateIntent);

            PermissionGateActivity.GateResult result = future.get(30, TimeUnit.SECONDS);

            if (result.allowed) {
                if (result.trustAgent) {
                    trustStore.trust(token, agentName);
                }
                SessionManager.SessionInfo active = SessionManager.get().getActiveSession();
                if (active == null) {
                    SessionManager.get().startSession(agentName, "", 60000);
                }
                return null; // proceed
            } else {
                return new RawHttpServer.Response(403, "{\"error\":\"user_denied\"}");
            }
        } catch (java.util.concurrent.TimeoutException e) {
            return new RawHttpServer.Response(403, "{\"error\":\"user_denied\",\"reason\":\"timeout\"}");
        } catch (Exception e) {
            return new RawHttpServer.Response(403,
                    "{\"error\":\"permission_gate_error\",\"detail\":\"" + RawHttpServer.escapeJson(e.getMessage()) + "\"}");
        }
    }

    // ── Server ──────────────────────────────────────────────────

    private void startServer() {
        server = new RawHttpServer("0.0.0.0", PORT) {
            @Override
            protected Response serve(String method, String path,
                                     Map<String, String> params, String body,
                                     Map<String, String> headers, InputStream rawInput) {
                return BridgeService.this.handleRequest(method, path, params, body, headers, rawInput);
            }
        };
        try {
            server.start();
            Log.i(TAG, "Raw HTTP server started on port " + PORT);
            updateNotification();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start HTTP server", e);
        }
    }

    // ── Request Routing ─────────────────────────────────────────

    private RawHttpServer.Response handleRequest(String method, String path,
                                                  Map<String, String> params, String body,
                                                  Map<String, String> headers, InputStream rawInput) {
        // /ping — unauthenticated
        if ("/ping".equals(path)) {
            StatusTracker.get().recordRequest(path);
            return callHandler(infoHandler, method, path, params, body);
        }

        // Auth check
        String token = headers.get("x-bridge-token");

        // /info, /permissions — authenticated, no a11y needed
        if ("/info".equals(path) || "/permissions".equals(path)) {
            if (!tokenManager.validate(token)) return unauthorized();
            StatusTracker.get().recordRequest(path);
            return callHandler(infoHandler, method, path, params, body);
        }

        // === NEW API: /screen (semantic), /snapshot, /act ===
        if ("/screen".equals(path)) {
            if (!tokenManager.validate(token)) return unauthorized();
            StatusTracker.get().recordRequest(path);
            if (screenHandler == null) {
                return new RawHttpServer.Response(503,
                        "{\"error\":\"accessibility service not connected\",\"elements\":[]}");
            }
            return callHandler(screenHandler, method, "/screen", params, body);
        }
        if ("/snapshot".equals(path) || path.startsWith("/snapshot")) {
            if (!tokenManager.validate(token)) return unauthorized();
            StatusTracker.get().recordRequest(path);
            if (screenHandler == null) {
                return new RawHttpServer.Response(503, "{\"error\":\"accessibility service not connected\"}");
            }
            return callHandler(screenHandler, method, "/snapshot", params, body);
        }
        if ("/act".equals(path)) {
            if (!tokenManager.validate(token)) return unauthorized();
            StatusTracker.get().recordRequest(path);
            if (actHandler == null) {
                return new RawHttpServer.Response(503, "{\"error\":\"accessibility service not connected\"}");
            }
            // Auto-unlock for /act (write operations)
            RawHttpServer.Response unlockErr = ensureUnlockedIfNeeded();
            if (unlockErr != null) return unlockErr;
            return callHandler(actHandler, method, path, params, body);
        }

        // /launch
        if ("/launch".equals(path)) {
            if (!tokenManager.validate(token)) return unauthorized();
            StatusTracker.get().recordRequest(path);
            return callHandler(launchHandler, method, path, params, body);
        }

        // /install
        if ("/install".equals(path)) {
            if (!tokenManager.validate(token)) return unauthorized();
            StatusTracker.get().recordRequest(path);
            return handleInstall(body, params);
        }

        // /status
        if ("/status".equals(path)) {
            if (!tokenManager.validate(token)) return unauthorized();
            StatusTracker.get().recordRequest(path);
            return handleStatus();
        }

        // /config
        if ("/config".equals(path)) {
            if (!tokenManager.validate(token)) return unauthorized();
            StatusTracker.get().recordRequest(path);
            return handleConfig(method, body);
        }

        // /file/upload — special binary streaming
        if ("/file/upload".equals(path) && "POST".equals(method)) {
            if (!tokenManager.validate(token)) return unauthorized();
            StatusTracker.get().recordRequest(path);
            return handleRawUpload(rawInput, params, headers);
        }

        // Everything below requires auth
        if (!tokenManager.validate(token)) return unauthorized();
        StatusTracker.get().recordRequest(path);

        // Overlay log for non-trivial operations
        if (!"/status".equals(path) && !"/config".equals(path)
                && !"/info".equals(path) && !"/permissions".equals(path)
                && !"/ping".equals(path) && !"/overlay".equals(path)) {
            try {
                OverlayManager overlay = OverlayManager.getInstanceOrNull();
                if (overlay != null) {
                    if (overlay.isTakenOver()) {
                        return new RawHttpServer.Response(200, "{\"paused\":true,\"reason\":\"user takeover\"}");
                    }
                    overlay.logOperation(OverlayManager.describeOperation(method, path));
                }
            } catch (Exception e) {
                Log.w(TAG, "overlay log failed: " + e.getMessage());
            }
        }

        // /overlay
        if ("/overlay".equals(path)) {
            return handleOverlay(params);
        }

        // Session management
        if (path.startsWith("/session/")) {
            return handleSession(method, path, params, body);
        }

        // Resolve handler for remaining endpoints
        RouteHandler handler = resolveHandler(path);
        if (handler == null) {
            return new RawHttpServer.Response(404,
                    "{\"error\":\"not found\",\"path\":\"" + RawHttpServer.escapeJson(path) + "\"}");
        }

        // A11y check — handlers need AccessibilityBridge
        if (isA11yEndpoint(path) && screenHandler == null) {
            return new RawHttpServer.Response(503,
                    "{\"error\":\"accessibility service not connected\",\"hint\":\"enable in Settings > Accessibility\"}");
        }

        // Permission gate for non-GET a11y actions
        if (isA11yEndpoint(path) && !"GET".equals(method)) {
            String agentName = headers.getOrDefault("x-agent-name", "Unknown Agent");
            String sessionId = headers.get("x-session-id");

            if (sessionId != null && !sessionId.isEmpty()) {
                SessionManager.get().recordAction(sessionId, OverlayManager.describeOperation(method, path));
            }

            if (!trustStore.isTrusted(token, agentName)) {
                SessionManager.SessionInfo activeSession = SessionManager.get().getActiveSession();
                if (activeSession == null) {
                    String sourceIp = headers.get("http-client-ip");
                    RawHttpServer.Response gateResponse = launchPermissionGate(token, agentName, sourceIp);
                    if (gateResponse != null) return gateResponse;
                }
            }
        }

        // Auto-unlock for a11y operations
        if (isA11yEndpoint(path) && !path.contains("/screen/")) {
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            boolean locked = km != null && km.isKeyguardLocked();
            if (locked) {
                if (configStore.hasUnlockCredential()) {
                    if (!ensureUnlocked()) {
                        return new RawHttpServer.Response(500,
                                "{\"error\":\"device locked, auto-unlock failed\",\"hint\":\"check PIN/pattern via POST /config\"}");
                    }
                } else {
                    return new RawHttpServer.Response(500,
                            "{\"error\":\"device locked\",\"hint\":\"set PIN via POST /config or unlock manually\"}");
                }
            }
        }

        return callHandler(handler, method, path, params, body);
    }

    // ── Handler resolution ──────────────────────────────────────

    private boolean isA11yEndpoint(String path) {
        return path.startsWith("/screen") || path.equals("/click") || path.equals("/tap")
                || path.equals("/longpress") || path.equals("/swipe")
                || path.equals("/type") || path.equals("/scroll")
                || path.equals("/global") || path.equals("/flow")
                || path.startsWith("/app/");
    }

    private RouteHandler resolveHandler(String path) {
        if ("/screen/fast".equals(path)) return screenHandler;
        if (path.startsWith("/screen/")) return screenControlHandler;
        if (path.startsWith("/screen")) return screenHandler;

        if ("/click".equals(path) || "/tap".equals(path) || "/longpress".equals(path)
                || "/swipe".equals(path) || "/type".equals(path) || "/scroll".equals(path)) {
            return gestureHandler;
        }

        if ("/global".equals(path)) return globalHandler;
        if (path.startsWith("/notifications")) return notificationHandler;
        if ("/intent".equals(path)) return intentHandler;
        if (path.startsWith("/tts")) return audioHandler;
        if ("/audio/record".equals(path)) return audioRecordHandler;
        if ("/flow".equals(path)) return flowHandler;
        if ("/batch".equals(path)) return batchHandler;
        if ("/clipboard".equals(path)) return clipboardHandler;
        if ("/volume".equals(path)) return volumeHandler;
        if ("/battery".equals(path)) return batteryHandler;
        if ("/wifi".equals(path)) return wifiHandler;
        if ("/vibrate".equals(path)) return vibrateHandler;
        if ("/location".equals(path)) return locationHandler;
        if ("/contacts".equals(path)) return contactsHandler;
        if ("/sms".equals(path)) return smsHandler;
        if (path.startsWith("/file")) return fileHandler;
        if ("/camera".equals(path)) return cameraHandler;
        if (path.startsWith("/app/")) return appHandler;

        return null;
    }

    private RawHttpServer.Response callHandler(RouteHandler handler, String method, String path,
                                                Map<String, String> params, String body) {
        if (handler == null) {
            return new RawHttpServer.Response(503, "{\"error\":\"handler not initialized\"}");
        }
        try {
            String json = handler.handle(method, path, params, body);
            return new RawHttpServer.Response(200, json);
        } catch (Exception e) {
            Log.e(TAG, "Handler error for " + path, e);
            return new RawHttpServer.Response(500,
                    "{\"error\":\"" + RawHttpServer.escapeJson(e.getMessage()) + "\"}");
        }
    }

    // ── /overlay ────────────────────────────────────────────────

    private RawHttpServer.Response handleOverlay(Map<String, String> params) {
        OverlayManager overlay = OverlayManager.getInstanceOrNull();
        if (overlay == null) {
            return new RawHttpServer.Response(200,
                    "{\"error\":\"overlay not initialized\",\"hint\":\"AccessibilityBridge may not be connected\"}");
        }
        String action = params.getOrDefault("action", "status");
        switch (action) {
            case "log": {
                if (overlay.isTakenOver()) {
                    return new RawHttpServer.Response(200, "{\"paused\":true,\"reason\":\"user takeover\"}");
                }
                String desc = params.getOrDefault("desc", "⚡ 操作");
                overlay.logOperation(desc);
                return new RawHttpServer.Response(200, "{\"paused\":false,\"logged\":true}");
            }
            case "activity": {
                if (overlay.isTakenOver()) {
                    return new RawHttpServer.Response(200, "{\"paused\":true,\"reason\":\"user takeover\"}");
                }
                overlay.onApiActivity();
                return new RawHttpServer.Response(200, "{\"paused\":false}");
            }
            case "test": {
                overlay.logOperation("🧪 Overlay 测试");
                String err = overlay.getLastError();
                return new RawHttpServer.Response(200,
                        "{\"test\":true,\"takenOver\":" + overlay.isTakenOver()
                                + ",\"error\":" + (err != null ? "\"" + err + "\"" : "null") + "}");
            }
            default:
                return new RawHttpServer.Response(200,
                        "{\"takenOver\":" + overlay.isTakenOver() + ",\"initialized\":true}");
        }
    }

    // ── /session ────────────────────────────────────────────────

    private RawHttpServer.Response handleSession(String method, String path,
                                                  Map<String, String> params, String body) {
        SessionManager sm = SessionManager.get();
        try {
            if ("/session/start".equals(path) && "POST".equals(method)) {
                org.json.JSONObject req = (body != null && !body.isEmpty())
                        ? new org.json.JSONObject(body) : new org.json.JSONObject();
                String agentName = req.optString("agentName", "Unknown Agent");
                String goal = req.optString("goal", "");
                long timeoutMs = req.optLong("timeoutMs", 60000);

                SessionManager.SessionInfo existing = sm.getActiveSession();
                if (existing != null) {
                    org.json.JSONObject err = new org.json.JSONObject();
                    err.put("error", "session_already_active");
                    err.put("sessionId", existing.sessionId);
                    return new RawHttpServer.Response(200, err.toString());
                }

                String sessionId = sm.startSession(agentName, goal, timeoutMs);
                if (sessionId == null) {
                    return new RawHttpServer.Response(200, "{\"error\":\"failed to start session\"}");
                }
                org.json.JSONObject resp = new org.json.JSONObject();
                resp.put("sessionId", sessionId);
                resp.put("status", "active");
                return new RawHttpServer.Response(200, resp.toString());
            } else if ("/session/end".equals(path) && "POST".equals(method)) {
                org.json.JSONObject req = (body != null && !body.isEmpty())
                        ? new org.json.JSONObject(body) : new org.json.JSONObject();
                String sessionId = req.optString("sessionId", "");
                String result = req.optString("result", "ended");

                SessionManager.SessionInfo info = sm.endSession(sessionId, result);
                if (info == null) {
                    return new RawHttpServer.Response(200, "{\"error\":\"session not found or not active\"}");
                }
                org.json.JSONObject resp = new org.json.JSONObject();
                resp.put("status", "ended");
                resp.put("durationMs", info.getDurationMs());
                resp.put("actions", info.actionCount);
                return new RawHttpServer.Response(200, resp.toString());
            } else if ("/session/status".equals(path) && "GET".equals(method)) {
                SessionManager.SessionInfo active = sm.getActiveSession();
                if (active == null) {
                    return new RawHttpServer.Response(200, "{\"active\":false}");
                }
                org.json.JSONObject resp = active.toJson();
                resp.put("active", true);
                return new RawHttpServer.Response(200, resp.toString());
            }
        } catch (Exception e) {
            return new RawHttpServer.Response(200, "{\"error\":\"" + RawHttpServer.escapeJson(e.getMessage()) + "\"}");
        }
        return new RawHttpServer.Response(200, "{\"error\":\"unknown session endpoint\"}");
    }

    // ── /status ─────────────────────────────────────────────────

    private RawHttpServer.Response handleStatus() {
        try {
            JSONObject j = StatusTracker.get().toJson();
            j.put("status", AccessibilityBridge.isRunning() ? "ok" : "degraded");
            j.put("version", getVersionName());

            JSONObject device = new JSONObject();
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
            device.put("screenOn", pm != null && pm.isInteractive());
            device.put("locked", km != null && km.isKeyguardLocked());
            if (bm != null) {
                device.put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
                device.put("charging", bm.isCharging());
            }
            j.put("device", device);

            JSONObject net = new JSONObject();
            net.put("ip", getWifiIpAddress());
            net.put("port", PORT);
            j.put("network", net);
            j.put("config", configStore.toJson());

            return new RawHttpServer.Response(200, j.toString());
        } catch (Exception e) {
            return new RawHttpServer.Response(500,
                    "{\"error\":\"" + RawHttpServer.escapeJson(e.getMessage()) + "\"}");
        }
    }

    private String getVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ── /config ─────────────────────────────────────────────────

    private RawHttpServer.Response handleConfig(String method, String body) {
        try {
            if ("GET".equals(method)) {
                return new RawHttpServer.Response(200, configStore.toJson().toString());
            }
            if ("DELETE".equals(method)) {
                configStore.clearPin();
                return new RawHttpServer.Response(200, "{\"cleared\":true}");
            }
            JSONObject req = body != null && !body.isEmpty() ? new JSONObject(body) : new JSONObject();
            if (req.has("pin")) {
                configStore.setPin(req.getString("pin"));
                configStore.setUnlockType("pin");
            }
            if (req.has("pattern")) {
                configStore.setPattern(req.getString("pattern"));
            }
            if (req.has("screenTimeout")) {
                configStore.setScreenTimeout(req.getInt("screenTimeout"));
            }
            return new RawHttpServer.Response(200, configStore.toJson().toString());
        } catch (Exception e) {
            return new RawHttpServer.Response(500,
                    "{\"error\":\"" + RawHttpServer.escapeJson(e.getMessage()) + "\"}");
        }
    }

    // ── /install ────────────────────────────────────────────────

    private RawHttpServer.Response handleInstall(String body, Map<String, String> params) {
        try {
            String filePath = params.get("path");
            if (filePath == null || filePath.isEmpty()) {
                if (body != null && !body.isEmpty()) {
                    org.json.JSONObject req = new org.json.JSONObject(body);
                    filePath = req.optString("path", "");
                }
            }
            if (filePath == null || filePath.isEmpty()) {
                filePath = "/sdcard/Download/claw-update.apk";
            }

            java.io.File apkFile = new java.io.File(filePath);
            if (!apkFile.exists()) {
                return new RawHttpServer.Response(400,
                        "{\"error\":\"APK not found: " + filePath + "\"}");
            }

            android.net.Uri contentUri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", apkFile);
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            intent.setDataAndType(contentUri, "application/vnd.android.package-archive");
            intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);

            return new RawHttpServer.Response(200,
                    "{\"installing\":true,\"path\":\"" + filePath + "\"}");
        } catch (Exception e) {
            return new RawHttpServer.Response(500,
                    "{\"error\":\"install failed: " + RawHttpServer.escapeJson(e.getMessage()) + "\"}");
        }
    }

    // ── /file/upload (binary streaming) ─────────────────────────

    private RawHttpServer.Response handleRawUpload(InputStream rawInput,
                                                    Map<String, String> params,
                                                    Map<String, String> headers) {
        String filePath = params.get("path");
        if (filePath == null || filePath.isEmpty()) {
            return new RawHttpServer.Response(400,
                    "{\"error\":\"'path' query param required\"}");
        }
        try {
            String lenStr = headers.get("content-length");
            if (lenStr == null) {
                return new RawHttpServer.Response(400,
                        "{\"error\":\"Content-Length header required\"}");
            }
            long contentLength = Long.parseLong(lenStr.trim());

            java.io.File file = new java.io.File(filePath);
            java.io.File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            // Note: body was already consumed by RawHttpServer for non-upload paths.
            // For /file/upload, the body IS the raw bytes we need.
            // Since we read body as UTF-8 string in RawHttpServer, we need to handle this
            // differently. The rawInput stream is past the body at this point.
            // TODO: For binary upload, we need to pass raw bytes through.
            // For now, use the body string converted back to bytes.
            return new RawHttpServer.Response(501,
                    "{\"error\":\"binary upload needs raw stream — use /file with base64 body for now\"}");
        } catch (Exception e) {
            return new RawHttpServer.Response(500,
                    "{\"error\":\"upload failed: " + RawHttpServer.escapeJson(e.getMessage()) + "\"}");
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    private RawHttpServer.Response unauthorized() {
        return new RawHttpServer.Response(401,
                "{\"error\":\"unauthorized\",\"hint\":\"include X-Bridge-Token header\"}");
    }

    public String getWifiIpAddress() {
        try {
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (iface.isLoopback() || !iface.isUp()) continue;
                for (InetAddress addr : Collections.list(iface.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get IP", e);
        }
        return "127.0.0.1";
    }
}
