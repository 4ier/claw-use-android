package com.clawuse.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.clawuse.android.auth.TokenManager;
import com.clawuse.android.handlers.*;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * Foreground service hosting the NanoHTTPD HTTP server on port 7333.
 * Routes requests to handler classes. Requires X-Bridge-Token for auth.
 */
public class BridgeService extends Service {
    private static final String TAG = "ClawBridge";
    private static final int PORT = 7333;
    private static final String CHANNEL_ID = "claw_bridge_channel";
    private static final int NOTIFICATION_ID = 7333;

    private BridgeHttpServer server;
    private TokenManager tokenManager;
    private static volatile BridgeService instance;

    // Handlers
    private ScreenHandler screenHandler;
    private GestureHandler gestureHandler;
    private GlobalHandler globalHandler;
    private ScreenControlHandler screenControlHandler;
    private InfoHandler infoHandler;
    private StubHandler cameraStub, audioStub, locationStub, sensorStub;
    private StubHandler notificationStub, clipboardStub, appStub, phoneStub;
    private StubHandler contactStub, fileStub, deviceStub;

    public static BridgeService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        tokenManager = new TokenManager(this);
        initHandlers();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        startServer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        if (server != null) {
            server.stop();
        }
        Log.i(TAG, "BridgeService destroyed");
    }

    public TokenManager getTokenManager() {
        return tokenManager;
    }

    public String getServerUrl() {
        String ip = getWifiIpAddress();
        return "http://" + ip + ":" + PORT;
    }

    // ── Server ──────────────────────────────────────────────────

    private void initHandlers() {
        screenHandler = new ScreenHandler();
        gestureHandler = new GestureHandler(this);
        globalHandler = new GlobalHandler();
        screenControlHandler = new ScreenControlHandler(this);
        infoHandler = new InfoHandler(this);

        // Phase 2 stubs
        cameraStub = new StubHandler("camera");
        audioStub = new StubHandler("audio");
        locationStub = new StubHandler("location");
        sensorStub = new StubHandler("sensors");
        notificationStub = new StubHandler("notifications");
        clipboardStub = new StubHandler("clipboard");
        appStub = new StubHandler("apps");
        phoneStub = new StubHandler("phone/sms");
        contactStub = new StubHandler("contacts/calendar");
        fileStub = new StubHandler("files");
        deviceStub = new StubHandler("device");
    }

    private void startServer() {
        try {
            server = new BridgeHttpServer(PORT);
            server.start();
            Log.i(TAG, "HTTP server started on port " + PORT);
            // Update notification with URL
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
        } catch (IOException e) {
            Log.e(TAG, "Failed to start HTTP server", e);
        }
    }

    // ── HTTP Server (NanoHTTPD) ─────────────────────────────────

    private class BridgeHttpServer extends NanoHTTPD {
        BridgeHttpServer(int port) {
            super("0.0.0.0", port);
        }

        @Override
        public Response serve(IHTTPSession session) {
            String path = session.getUri();
            String method = session.getMethod().name();
            Map<String, String> params = session.getParms();

            // Read body for POST
            String body = "";
            if (method.equals("POST")) {
                try {
                    Map<String, String> bodyMap = new HashMap<>();
                    session.parseBody(bodyMap);
                    body = bodyMap.getOrDefault("postData", "");
                } catch (Exception e) {
                    body = "";
                }
            }

            // CORS preflight
            if (method.equals("OPTIONS")) {
                return newCorsResponse(newFixedLengthResponse(Response.Status.OK, "text/plain", ""));
            }

            // /ping is unauthenticated
            if (path.equals("/ping")) {
                try {
                    String json = infoHandler.handle(method, path, params, body);
                    return newCorsResponse(newFixedLengthResponse(Response.Status.OK, "application/json", json));
                } catch (Exception e) {
                    return errorResponse(500, e.getMessage());
                }
            }

            // Auth check for all other endpoints
            String token = session.getHeaders().get("x-bridge-token");
            if (!tokenManager.validate(token)) {
                return newCorsResponse(newFixedLengthResponse(
                        Response.Status.UNAUTHORIZED,
                        "application/json",
                        "{\"error\":\"unauthorized\",\"hint\":\"include X-Bridge-Token header\"}"));
            }

            // Route to handler
            try {
                RouteHandler handler = resolveHandler(path);
                if (handler == null) {
                    return errorResponse(404,
                            "{\"error\":\"not found\",\"path\":\"" + path + "\"}");
                }
                String json = handler.handle(method, path, params, body);
                return newCorsResponse(newFixedLengthResponse(Response.Status.OK, "application/json", json));
            } catch (Exception e) {
                Log.e(TAG, "Handler error for " + path, e);
                return errorResponse(500, e.getMessage());
            }
        }

        private RouteHandler resolveHandler(String path) {
            // UI — screen reading
            if (path.startsWith("/screen/")) return screenControlHandler;  // /screen/lock etc.
            if (path.startsWith("/screen")) return screenHandler;         // /screen, /screenshot

            // UI — interaction
            if (path.equals("/click") || path.equals("/tap") || path.equals("/longpress")
                    || path.equals("/swipe") || path.equals("/type") || path.equals("/scroll")) {
                return gestureHandler;
            }

            // Global actions
            if (path.equals("/global")) return globalHandler;

            // System info
            if (path.equals("/info") || path.equals("/permissions")) return infoHandler;

            // Phase 2 stubs
            if (path.startsWith("/camera")) return cameraStub;
            if (path.startsWith("/audio") || path.startsWith("/tts") || path.equals("/volume")) return audioStub;
            if (path.startsWith("/location")) return locationStub;
            if (path.startsWith("/sensors")) return sensorStub;
            if (path.startsWith("/notifications")) return notificationStub;
            if (path.startsWith("/clipboard")) return clipboardStub;
            if (path.startsWith("/apps") || path.equals("/intent")) return appStub;
            if (path.startsWith("/phone") || path.startsWith("/sms")) return phoneStub;
            if (path.startsWith("/contacts") || path.startsWith("/calendar")) return contactStub;
            if (path.startsWith("/files")) return fileStub;
            if (path.equals("/vibrate") || path.equals("/brightness") || path.equals("/torch")) return deviceStub;

            return null;
        }

        private Response errorResponse(int code, String message) {
            Response.IStatus status = code == 404 ? Response.Status.NOT_FOUND : Response.Status.INTERNAL_ERROR;
            return newCorsResponse(newFixedLengthResponse(status, "application/json",
                    "{\"error\":\"" + escapeJson(message) + "\"}"));
        }

        private Response newCorsResponse(Response resp) {
            resp.addHeader("Access-Control-Allow-Origin", "*");
            resp.addHeader("Access-Control-Allow-Headers", "X-Bridge-Token, Content-Type");
            resp.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            return resp;
        }

        private String escapeJson(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r");
        }
    }

    // ── Notification ────────────────────────────────────────────

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_desc));
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String url = getServerUrl();
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Claw Use — Bridge Active")
                .setContentText(url)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
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
