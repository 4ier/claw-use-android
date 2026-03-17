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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD;

/**
 * External HTTP server on 0.0.0.0:7333, running in the :http process.
 *
 * Non-a11y endpoints handled locally. A11y endpoints proxied to 127.0.0.1:7334.
 * Features: heartbeat watchdog, WakeLock/WifiLock, auto-unlock, rich notification.
 */
public class BridgeService extends Service {
    private static final String TAG = "ClawBridge";
    private static final int PORT = 7333;
    private static final int A11Y_PORT = 7334;
    private static final int PROXY_CONNECT_TIMEOUT = 1000;
    private static final int PROXY_READ_TIMEOUT = 5000;
    private static final String CHANNEL_ID = "claw_bridge_channel";
    private static final int NOTIFICATION_ID = 7333;
    private static final int HEARTBEAT_INTERVAL_MS = 30_000;

    private BridgeHttpServer server;
    private TokenManager tokenManager;
    private ConfigStore configStore;
    private TrustStore trustStore;
    private static volatile BridgeService instance;

    // Local handlers
    private InfoHandler infoHandler;
    private LaunchHandler launchHandler;

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
        infoHandler = new InfoHandler(this);
        launchHandler = new LaunchHandler(this);
        notificationManager = getSystemService(NotificationManager.class);

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        acquireLocks();
        startServer();
        startHeartbeat();
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
        releaseLocks();
        Log.i(TAG, "BridgeService destroyed");
    }

    public TokenManager getTokenManager() { return tokenManager; }
    public String getServerUrl() { return "http://" + getWifiIpAddress() + ":" + PORT; }

    // ── Locks (Task 4) ─────────────────────────────────────────

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

    // ── Heartbeat (Task 3) ──────────────────────────────────────

    private void startHeartbeat() {
        heartbeatHandler = new Handler(Looper.getMainLooper());
        heartbeatHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkA11yHealth();
                updateNotification();
                heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
            }
        }, HEARTBEAT_INTERVAL_MS);
    }

    private void checkA11yHealth() {
        // Run in background to avoid blocking main looper
        new Thread(() -> {
            long start = System.currentTimeMillis();
            boolean alive = false;
            try {
                HttpURLConnection conn = (HttpURLConnection)
                        new URL("http://127.0.0.1:" + A11Y_PORT + "/a11y/ping").openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                int code = conn.getResponseCode();
                if (code == 200) {
                    java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(conn.getInputStream()));
                    String line = r.readLine();
                    r.close();
                    alive = line != null && line.contains("\"a11y\":true");
                }
                conn.disconnect();
            } catch (Exception e) {
                // not alive
            }
            long latency = System.currentTimeMillis() - start;
            StatusTracker.get().recordA11yCheck(alive, latency);
        }).start();
    }

    // ── Notification (Task 2) ───────────────────────────────────

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

        // Status line
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

        // Build one-line setup command: cua add <hostname> <ip> <token>
        String hostname = android.os.Build.MODEL.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
        String setupCmd = "cua add " + hostname + " " + getWifiIpAddress() + " " + tokenManager.getToken();

        // Copy Setup Command action
        Intent copySetupIntent = new Intent(this, NotificationActionReceiver.class);
        copySetupIntent.setAction(NotificationActionReceiver.ACTION_COPY_URL);
        copySetupIntent.putExtra(NotificationActionReceiver.EXTRA_TEXT, setupCmd);
        PendingIntent copySetupPi = PendingIntent.getBroadcast(this, 1, copySetupIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // Copy Token action (keep for power users)
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

    // ── Server ──────────────────────────────────────────────────

    private void startServer() {
        try {
            server = new BridgeHttpServer(PORT);
            server.start(5000, false);
            Log.i(TAG, "HTTP server started on port " + PORT);
            // Initial a11y check
            checkA11yHealth();
            updateNotification();
        } catch (IOException e) {
            Log.e(TAG, "Failed to start HTTP server", e);
        }
    }

    // ── Auto-unlock helper (Task 7) ─────────────────────────────

    /**
     * If device is locked and PIN is configured, proxy an unlock request first.
     * Returns true if device is now unlocked (or was already unlocked).
     */
    private boolean ensureUnlocked() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            boolean screenOff = pm != null && !pm.isInteractive();
            boolean locked = km != null && km.isKeyguardLocked();

            if (!screenOff && !locked) return true; // already unlocked

            String pin = configStore.getPin();
            String pattern = configStore.getPattern();
            String unlockType = configStore.getUnlockType();
            if ((pin == null || pin.isEmpty()) && (pattern == null || pattern.isEmpty())) return false;

            // Proxy unlock request to a11y server
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection)
                    new URL("http://127.0.0.1:" + A11Y_PORT + "/a11y/screen/unlock").openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(10000); // unlock takes time
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            // Respect unlockType preference; default to PIN (proven reliable)
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
            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.flush();
            os.close();

            int code = conn.getResponseCode();

            // Wait for unlock to take effect
            Thread.sleep(1000);

            // Re-check
            locked = km != null && km.isKeyguardLocked();
            return !locked;
            } finally {
                if (conn != null) conn.disconnect();
            }
        } catch (Exception e) {
            Log.e(TAG, "Auto-unlock failed", e);
            return false;
        }
    }

    // ── Permission Gate ─────────────────────────────────────────

    /**
     * Launch PermissionGateActivity and block until user responds (up to 30s).
     * Returns null if allowed, or a 403 Response if denied.
     */
    private NanoHTTPD.Response launchPermissionGate(String token, String agentName,
                                                     NanoHTTPD.IHTTPSession httpSession) {
        try {
            // Extract source IP from the HTTP session
            String sourceIp = httpSession.getHeaders().get("http-client-ip");
            if (sourceIp == null) sourceIp = httpSession.getHeaders().get("remote-addr");
            if (sourceIp == null) sourceIp = "unknown";

            CompletableFuture<PermissionGateActivity.GateResult> future =
                    PermissionGateActivity.prepare(agentName, sourceIp);

            Intent gateIntent = new Intent(this, PermissionGateActivity.class);
            gateIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(gateIntent);

            // Block thread up to 30s waiting for user decision
            PermissionGateActivity.GateResult result = future.get(30, TimeUnit.SECONDS);

            if (result.allowed) {
                // Trust if checkbox was checked
                if (result.trustAgent) {
                    trustStore.trust(token, agentName);
                }
                // Start a session if none exists
                SessionManager.SessionInfo active = SessionManager.get().getActiveSession();
                if (active == null) {
                    SessionManager.get().startSession(agentName, "", 60000);
                }
                return null; // proceed
            } else {
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN,
                        "application/json; charset=UTF-8",
                        "{\"error\":\"user_denied\"}");
            }
        } catch (java.util.concurrent.TimeoutException e) {
            Log.w(TAG, "Permission gate timed out for agent: " + agentName);
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN,
                    "application/json; charset=UTF-8",
                    "{\"error\":\"user_denied\",\"reason\":\"timeout\"}");
        } catch (Exception e) {
            Log.e(TAG, "Permission gate error", e);
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN,
                    "application/json; charset=UTF-8",
                    "{\"error\":\"permission_gate_error\",\"detail\":\"" + e.getMessage() + "\"}");
        }
    }

    // ── HTTP Server ─────────────────────────────────────────────

    private class BridgeHttpServer extends NanoHTTPD {
        BridgeHttpServer(int port) { super("0.0.0.0", port); }

        @Override
        public Response serve(IHTTPSession session) {
            String path = session.getUri();
            String method = session.getMethod().name();
            Map<String, String> params = session.getParms();

            // Raw binary upload — stream directly through proxy, no body buffering
            if ("/file/upload".equals(path) && "POST".equals(method)) {
                String token = session.getHeaders().get("x-bridge-token");
                if (!tokenManager.validate(token)) return cors(unauthorized());
                StatusTracker.get().recordRequest(path);
                return cors(proxyStreamUpload(session, params));
            }

            // Read body as raw UTF-8 (NanoHTTPD parseBody uses ISO-8859-1)
            String body = "";
            if ("POST".equals(method)) {
                body = readBodyUtf8(session);
            }

            // CORS preflight
            if ("OPTIONS".equals(method)) {
                return cors(newFixedLengthResponse(Response.Status.OK, "text/plain", ""));
            }

            // === UNAUTHENTICATED ===
            if ("/ping".equals(path)) {
                StatusTracker.get().recordRequest(path);
                return handleLocal(infoHandler, method, path, params, body);
            }

            // === AUTHENTICATED ===
            String token = session.getHeaders().get("x-bridge-token");

            // Takeover check + overlay trigger via internal server (main process)
            if (!"/status".equals(path) && !"/config".equals(path)
                    && !"/info".equals(path) && !"/permissions".equals(path)
                    && !"/ping".equals(path) && !"/overlay".equals(path)) {
                try {
                    String desc = java.net.URLEncoder.encode(
                            OverlayManager.describeOperation(method, path), "UTF-8");
                    java.net.URL url = new java.net.URL("http://127.0.0.1:" + A11Y_PORT
                            + "/a11y/overlay?action=log&desc=" + desc);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(500);
                    conn.setReadTimeout(500);
                    conn.setRequestMethod("GET");
                    java.io.InputStream is = conn.getInputStream();
                    byte[] buf = new byte[256];
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    int n;
                    while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                    String resp = baos.toString("UTF-8");
                    conn.disconnect();
                    if (resp.contains("\"paused\":true")) {
                        return cors(newFixedLengthResponse(Response.Status.OK,
                                "application/json; charset=UTF-8", resp));
                    }
                } catch (Exception e) {
                    Log.w("BridgeService", "overlay log failed: " + e.getMessage());
                }
            }

            if ("/info".equals(path) || "/permissions".equals(path)) {
                if (!tokenManager.validate(token)) return cors(unauthorized());
                StatusTracker.get().recordRequest(path);
                return handleLocal(infoHandler, method, path, params, body);
            }
            if ("/launch".equals(path)) {
                if (!tokenManager.validate(token)) return cors(unauthorized());
                StatusTracker.get().recordRequest(path);
                return handleLocal(launchHandler, method, path, params, body);
            }

            // === /install — trigger APK install via FileProvider ===
            if ("/install".equals(path)) {
                if (!tokenManager.validate(token)) return cors(unauthorized());
                StatusTracker.get().recordRequest(path);
                return cors(handleInstall(body, params));
            }

            // === /overlay — proxy to internal overlay handler ===
            if ("/overlay".equals(path)) {
                if (!tokenManager.validate(token)) return cors(unauthorized());
                return cors(proxyToA11y("GET", "/overlay", params, ""));
            }

            // === Session API routes — proxy to internal ===
            if ("/session/start".equals(path) || "/session/end".equals(path)
                    || "/session/status".equals(path)) {
                if (!tokenManager.validate(token)) return cors(unauthorized());
                StatusTracker.get().recordRequest(path);
                return cors(proxyToA11y(method, path, params, body));
            }

            // === /status (Task 9) ===
            if ("/status".equals(path)) {
                if (!tokenManager.validate(token)) return cors(unauthorized());
                StatusTracker.get().recordRequest(path);
                return cors(handleStatus());
            }

            // === /config (Task 8) ===
            if ("/config".equals(path)) {
                if (!tokenManager.validate(token)) return cors(unauthorized());
                StatusTracker.get().recordRequest(path);
                return cors(handleConfig(method, body));
            }

            // === Proxied to main process (no auto-unlock needed) ===
            if (path.startsWith("/notifications") || path.equals("/intent")
                    || path.startsWith("/tts") || path.equals("/audio/record")
                    || path.equals("/batch")
                    || path.equals("/clipboard") || path.equals("/volume")
                    || path.equals("/battery") || path.equals("/wifi")
                    || path.equals("/vibrate") || path.equals("/sms")
                    || path.equals("/contacts") || path.startsWith("/file")) {
                if (!tokenManager.validate(token)) return cors(unauthorized());
                StatusTracker.get().recordRequest(path);
                return cors(proxyToA11y(method, path, params, body));
            }

            // === Camera and Location (proxied, longer timeout) ===
            if (path.equals("/camera") || path.equals("/location")) {
                if (!tokenManager.validate(token)) return cors(unauthorized());
                StatusTracker.get().recordRequest(path);
                return cors(proxyToA11y(method, path, params, body));
            }

            // === A11Y ENDPOINTS ===
            if (!tokenManager.validate(token)) return cors(unauthorized());
            StatusTracker.get().recordRequest(path);

            if (isA11yEndpoint(path)) {
                // Permission gate for non-GET a11y actions
                if (!"GET".equals(method)) {
                    String agentName = session.getHeaders().getOrDefault("x-agent-name", "Unknown Agent");
                    String sessionId = session.getHeaders().get("x-session-id");

                    // Record action if session ID is provided
                    if (sessionId != null && !sessionId.isEmpty()) {
                        SessionManager.get().recordAction(sessionId, OverlayManager.describeOperation(method, path));
                    }

                    // Check if agent is trusted or has an active session
                    if (!trustStore.isTrusted(token, agentName)) {
                        SessionManager.SessionInfo activeSession = SessionManager.get().getActiveSession();
                        if (activeSession == null) {
                            // Launch permission gate, block up to 30s
                            Response gateResponse = launchPermissionGate(token, agentName, session);
                            if (gateResponse != null) {
                                return cors(gateResponse);
                            }
                            // allowed — proceed
                        }
                    }
                }
                // Auto-unlock before a11y operations (Task 7)
                KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                boolean locked = km != null && km.isKeyguardLocked();
                if (locked && !path.contains("/screen/")) {
                    if (configStore.hasUnlockCredential()) {
                        boolean unlocked = ensureUnlocked();
                        if (!unlocked) {
                            return cors(newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                                    "application/json; charset=UTF-8",
                                    "{\"error\":\"device locked, auto-unlock failed\",\"hint\":\"check PIN/pattern via POST /config\"}"));
                        }
                    } else {
                        return cors(newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                                "application/json; charset=UTF-8",
                                "{\"error\":\"device locked\",\"hint\":\"set PIN via POST /config or unlock manually\"}"));
                    }
                }
                return cors(proxyToA11y(method, path, params, body));
            }

            return cors(newFixedLengthResponse(Response.Status.NOT_FOUND,
                    "application/json; charset=UTF-8",
                    "{\"error\":\"not found\",\"path\":\"" + escapeJson(path) + "\"}"));
        }

        // ── /status handler ─────────────────────────────────────

        private String getVersionName() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "unknown";
            }
        }

        private Response handleInstall(String body, Map<String, String> params) {
            try {
                String filePath = params.get("path");
                if (filePath == null || filePath.isEmpty()) {
                    // Try JSON body
                    if (body != null && !body.isEmpty()) {
                        org.json.JSONObject req = new org.json.JSONObject(body);
                        filePath = req.optString("path", "");
                    }
                }
                if (filePath == null || filePath.isEmpty()) {
                    filePath = "/sdcard/Download/claw-update.apk"; // default
                }

                java.io.File apkFile = new java.io.File(filePath);
                if (!apkFile.exists()) {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                            "{\"error\":\"APK not found: " + filePath + "\"}");
                }

                android.net.Uri contentUri = androidx.core.content.FileProvider.getUriForFile(
                        BridgeService.this,
                        getPackageName() + ".fileprovider",
                        apkFile);

                android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                intent.setDataAndType(contentUri, "application/vnd.android.package-archive");
                intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);

                return newFixedLengthResponse(Response.Status.OK, "application/json",
                        "{\"installing\":true,\"path\":\"" + filePath + "\"}");
            } catch (Exception e) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                        "{\"error\":\"install failed: " + escapeJson(e.getMessage()) + "\"}");
            }
        }

        private Response handleStatus() {
            try {
                JSONObject j = StatusTracker.get().toJson();
                j.put("status", StatusTracker.get().isA11yAlive() ? "ok" : "degraded");
                j.put("version", getVersionName());

                // Device info
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

                // Network
                JSONObject net = new JSONObject();
                net.put("ip", getWifiIpAddress());
                net.put("port", PORT);
                j.put("network", net);

                // Config
                j.put("config", configStore.toJson());

                return newFixedLengthResponse(Response.Status.OK,
                        "application/json; charset=UTF-8", j.toString());
            } catch (Exception e) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                        "application/json; charset=UTF-8",
                        "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        }

        // ── /config handler ─────────────────────────────────────

        private Response handleConfig(String method, String body) {
            try {
                if ("GET".equals(method)) {
                    return newFixedLengthResponse(Response.Status.OK,
                            "application/json; charset=UTF-8", configStore.toJson().toString());
                }
                if ("DELETE".equals(method)) {
                    configStore.clearPin();
                    return newFixedLengthResponse(Response.Status.OK,
                            "application/json; charset=UTF-8", "{\"cleared\":true}");
                }
                // POST
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
                return newFixedLengthResponse(Response.Status.OK,
                        "application/json; charset=UTF-8", configStore.toJson().toString());
            } catch (Exception e) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                        "application/json; charset=UTF-8",
                        "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        }

        // ── Routing ─────────────────────────────────────────────

        private boolean isA11yEndpoint(String path) {
            return path.startsWith("/screen") || path.equals("/click") || path.equals("/tap")
                    || path.equals("/longpress") || path.equals("/swipe")
                    || path.equals("/type") || path.equals("/scroll")
                    || path.equals("/global") || path.equals("/flow");
        }

        private Response handleLocal(RouteHandler handler, String method, String path,
                                     Map<String, String> params, String body) {
            try {
                String json = handler.handle(method, path, params, body);
                return cors(newFixedLengthResponse(Response.Status.OK,
                        "application/json; charset=UTF-8", json));
            } catch (Exception e) {
                return cors(newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                        "application/json; charset=UTF-8",
                        "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}"));
            }
        }

        /**
         * Proxy request to internal a11y server on 127.0.0.1:7334.
         * Strict timeout: 1s connect + 5s read.
         */
        /**
         * Stream raw binary upload to internal server without buffering entire body.
         * POST /file/upload?path=/sdcard/Download/app.apk with raw binary body.
         */
        private Response proxyStreamUpload(IHTTPSession session, Map<String, String> params) {
            String filePath = params.get("path");
            if (filePath == null || filePath.isEmpty()) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                        "{\"error\":\"'path' query param required\"}");
            }
            try {
                String lenStr = session.getHeaders().get("content-length");
                if (lenStr == null) {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                            "{\"error\":\"Content-Length required\"}");
                }
                long contentLength = Long.parseLong(lenStr.trim());

                // Stream to internal server
                String urlStr = "http://127.0.0.1:" + A11Y_PORT
                        + "/a11y/file/upload?path=" + java.net.URLEncoder.encode(filePath, "UTF-8");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                        new java.net.URL(urlStr).openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(120_000); // 2 min for large files
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(contentLength);
                conn.setRequestProperty("Content-Type", "application/octet-stream");
                conn.setRequestProperty("Content-Length", String.valueOf(contentLength));

                // Pipe client input → internal server output
                java.io.InputStream clientIn = session.getInputStream();
                java.io.OutputStream serverOut = conn.getOutputStream();
                byte[] buf = new byte[8192];
                long remaining = contentLength;
                int n;
                while (remaining > 0 && (n = clientIn.read(buf, 0,
                        (int) Math.min(buf.length, remaining))) != -1) {
                    serverOut.write(buf, 0, n);
                    remaining -= n;
                }
                serverOut.flush();
                serverOut.close();

                // Read response
                int code = conn.getResponseCode();
                java.io.InputStream respStream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                while ((n = respStream.read(buf)) != -1) baos.write(buf, 0, n);
                respStream.close();
                conn.disconnect();

                Response.IStatus status = code == 200 ? Response.Status.OK : Response.Status.INTERNAL_ERROR;
                return newFixedLengthResponse(status, "application/json; charset=UTF-8", baos.toString("UTF-8"));
            } catch (Exception e) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                        "{\"error\":\"upload proxy failed: " + escapeJson(e.getMessage()) + "\"}");
            }
        }

        private Response proxyToA11y(String method, String path,
                                     Map<String, String> params, String body) {
            HttpURLConnection conn = null;
            try {
                StringBuilder urlStr = new StringBuilder("http://127.0.0.1:" + A11Y_PORT + "/a11y" + path);
                if (!params.isEmpty()) {
                    urlStr.append('?');
                    boolean first = true;
                    for (Map.Entry<String, String> e : params.entrySet()) {
                        if (!first) urlStr.append('&');
                        urlStr.append(e.getKey()).append('=').append(e.getValue());
                        first = false;
                    }
                }

                conn = (HttpURLConnection) new URL(urlStr.toString()).openConnection();
                conn.setConnectTimeout(PROXY_CONNECT_TIMEOUT);
                // Longer timeouts for slow endpoints
                int readTimeout = (path.contains("/tts") || path.contains("/unlock")
                        || path.equals("/audio/record") || path.equals("/camera"))
                        ? 35000 : path.equals("/flow") ? 310_000
                        : path.equals("/batch") ? 60000
                        : path.equals("/location") ? 15000
                        : PROXY_READ_TIMEOUT;
                conn.setReadTimeout(readTimeout);
                conn.setRequestMethod(method);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

                if ("POST".equals(method) && body != null && !body.isEmpty()) {
                    conn.setDoOutput(true);
                    OutputStream os = conn.getOutputStream();
                    os.write(body.getBytes("UTF-8"));
                    os.flush();
                    os.close();
                }

                int code = conn.getResponseCode();
                java.io.InputStream respStream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int n;
                while ((n = respStream.read(chunk)) != -1) baos.write(chunk, 0, n);
                respStream.close();
                String responseBody = baos.toString("UTF-8");

                Response.IStatus status = code == 200 ? Response.Status.OK
                        : code == 404 ? Response.Status.NOT_FOUND
                        : Response.Status.INTERNAL_ERROR;
                return newFixedLengthResponse(status, "application/json; charset=UTF-8", responseBody);

            } catch (java.net.SocketTimeoutException e) {
                Log.w(TAG, "A11y proxy timeout for " + path);
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                        "application/json; charset=UTF-8",
                        "{\"error\":\"accessibility timeout\",\"hint\":\"a11y IPC may be stuck\",\"path\":\"" + escapeJson(path) + "\"}");
            } catch (java.net.ConnectException e) {
                Log.w(TAG, "A11y proxy connection refused for " + path);
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                        "application/json; charset=UTF-8",
                        "{\"error\":\"accessibility service not available\",\"hint\":\"enable accessibility service in Settings\"}");
            } catch (Exception e) {
                Log.e(TAG, "A11y proxy error for " + path, e);
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                        "application/json; charset=UTF-8",
                        "{\"error\":\"proxy error: " + escapeJson(e.getMessage()) + "\"}");
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        // ── Helpers ─────────────────────────────────────────────

        private Response unauthorized() {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED,
                    "application/json; charset=UTF-8",
                    "{\"error\":\"unauthorized\",\"hint\":\"include X-Bridge-Token header\"}");
        }

        private Response cors(Response resp) {
            resp.addHeader("Access-Control-Allow-Origin", "*");
            resp.addHeader("Access-Control-Allow-Headers", "X-Bridge-Token, X-Agent-Name, X-Session-Id, Content-Type");
            resp.addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
            return resp;
        }

        /** Read POST body as raw UTF-8 bytes, bypassing NanoHTTPD's broken parseBody. */
        private String readBodyUtf8(IHTTPSession session) {
            try {
                String lenStr = session.getHeaders().get("content-length");
                if (lenStr == null) return "";
                int contentLength = Integer.parseInt(lenStr.trim());
                if (contentLength <= 0) return "";
                byte[] buf = new byte[contentLength];
                int totalRead = 0;
                InputStream is = session.getInputStream();
                while (totalRead < contentLength) {
                    int read = is.read(buf, totalRead, contentLength - totalRead);
                    if (read == -1) break;
                    totalRead += read;
                }
                return new String(buf, 0, totalRead, "UTF-8");
            } catch (Exception e) {
                return "";
            }
        }

        private String escapeJson(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r");
        }
    }

    // ── Network ─────────────────────────────────────────────────

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
