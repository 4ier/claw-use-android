package com.clawuse.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.clawuse.android.auth.TokenManager;
import com.clawuse.android.handlers.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import java.io.InputStream;

import fi.iki.elonen.NanoHTTPD;

/**
 * External HTTP server on 0.0.0.0:7333, running in the :http process.
 *
 * Non-a11y endpoints are handled locally (ping, info, launch, screen/state, screen/wake).
 * A11y-dependent endpoints are proxied to the internal server on 127.0.0.1:7334
 * with strict timeouts. If the main process hangs, this process stays responsive.
 */
public class BridgeService extends Service {
    private static final String TAG = "ClawBridge";
    private static final int PORT = 7333;
    private static final int A11Y_PORT = 7334;
    private static final int PROXY_CONNECT_TIMEOUT = 1000;
    private static final int PROXY_READ_TIMEOUT = 5000;
    private static final String CHANNEL_ID = "claw_bridge_channel";
    private static final int NOTIFICATION_ID = 7333;

    private BridgeHttpServer server;
    private TokenManager tokenManager;
    private static volatile BridgeService instance;

    // Local handlers (no a11y dependency)
    private InfoHandler infoHandler;
    private LaunchHandler launchHandler;

    public static BridgeService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        tokenManager = new TokenManager(this);
        infoHandler = new InfoHandler(this);
        launchHandler = new LaunchHandler(this);
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
        if (server != null) server.stop();
        Log.i(TAG, "BridgeService destroyed");
    }

    public TokenManager getTokenManager() {
        return tokenManager;
    }

    public String getServerUrl() {
        return "http://" + getWifiIpAddress() + ":" + PORT;
    }

    private void startServer() {
        try {
            server = new BridgeHttpServer(PORT);
            server.start(5000, false);
            Log.i(TAG, "HTTP server started on port " + PORT);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
        } catch (IOException e) {
            Log.e(TAG, "Failed to start HTTP server", e);
        }
    }

    // ── HTTP Server ─────────────────────────────────────────────

    private class BridgeHttpServer extends NanoHTTPD {
        BridgeHttpServer(int port) {
            super("0.0.0.0", port);
        }

        @Override
        public Response serve(IHTTPSession session) {
            String path = session.getUri();
            String method = session.getMethod().name();
            Map<String, String> params = session.getParms();

            // Read body for POST — use raw InputStream for correct UTF-8 handling
            // (NanoHTTPD's parseBody() uses ISO-8859-1 which corrupts CJK text)
            String body = "";
            if ("POST".equals(method)) {
                body = readBodyUtf8(session);
            }

            // CORS preflight
            if ("OPTIONS".equals(method)) {
                return cors(newFixedLengthResponse(Response.Status.OK, "text/plain", ""));
            }

            // === LOCAL ENDPOINTS (no a11y dependency) ===

            if ("/ping".equals(path)) {
                return handleLocal(infoHandler, method, path, params, body);
            }
            if ("/info".equals(path) || "/permissions".equals(path)) {
                String token = session.getHeaders().get("x-bridge-token");
                if (!tokenManager.validate(token))
                    return cors(unauthorized());
                return handleLocal(infoHandler, method, path, params, body);
            }
            if ("/launch".equals(path)) {
                String token = session.getHeaders().get("x-bridge-token");
                if (!tokenManager.validate(token))
                    return cors(unauthorized());
                return handleLocal(launchHandler, method, path, params, body);
            }

            // === A11Y ENDPOINTS (proxied to main process :7334) ===

            String token = session.getHeaders().get("x-bridge-token");
            if (!tokenManager.validate(token))
                return cors(unauthorized());

            if (isA11yEndpoint(path)) {
                return cors(proxyToA11y(method, path, params, body));
            }

            return cors(newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json",
                    "{\"error\":\"not found\",\"path\":\"" + escapeJson(path) + "\"}"));
        }

        private boolean isA11yEndpoint(String path) {
            return path.startsWith("/screen") || path.equals("/click") || path.equals("/tap")
                    || path.equals("/longpress") || path.equals("/swipe")
                    || path.equals("/type") || path.equals("/scroll")
                    || path.equals("/global");
        }

        private Response handleLocal(RouteHandler handler, String method, String path,
                                     Map<String, String> params, String body) {
            try {
                String json = handler.handle(method, path, params, body);
                return cors(newFixedLengthResponse(Response.Status.OK, "application/json; charset=UTF-8", json));
            } catch (Exception e) {
                return cors(newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                        "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}"));
            }
        }

        /**
         * Proxy request to the internal a11y server on 127.0.0.1:7334.
         * Strict timeout: 1s connect + 5s read. Returns error JSON on timeout.
         */
        private Response proxyToA11y(String method, String path,
                                     Map<String, String> params, String body) {
            HttpURLConnection conn = null;
            try {
                // Build URL with /a11y prefix and query params
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
                conn.setReadTimeout(PROXY_READ_TIMEOUT);
                conn.setRequestMethod(method);
                conn.setRequestProperty("Content-Type", "application/json");

                if ("POST".equals(method) && body != null && !body.isEmpty()) {
                    conn.setDoOutput(true);
                    OutputStream os = conn.getOutputStream();
                    os.write(body.getBytes("UTF-8"));
                    os.flush();
                    os.close();
                }

                int code = conn.getResponseCode();
                java.io.InputStream respStream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                // Read response as raw bytes, then decode as UTF-8 (not platform default)
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
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                        "{\"error\":\"accessibility timeout\",\"hint\":\"a11y IPC may be stuck, try again in a few seconds\",\"path\":\"" + escapeJson(path) + "\"}");
            } catch (java.net.ConnectException e) {
                Log.w(TAG, "A11y proxy connection refused for " + path);
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                        "{\"error\":\"accessibility service not available\",\"hint\":\"enable accessibility service in Settings\"}");
            } catch (Exception e) {
                Log.e(TAG, "A11y proxy error for " + path, e);
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                        "{\"error\":\"proxy error: " + escapeJson(e.getMessage()) + "\"}");
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        private Response unauthorized() {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json",
                    "{\"error\":\"unauthorized\",\"hint\":\"include X-Bridge-Token header\"}");
        }

        private Response cors(Response resp) {
            resp.addHeader("Access-Control-Allow-Origin", "*");
            resp.addHeader("Access-Control-Allow-Headers", "X-Bridge-Token, Content-Type");
            resp.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            return resp;
        }

        /** Read POST body as raw UTF-8 bytes, bypassing NanoHTTPD's broken parseBody. */
        private String readBodyUtf8(IHTTPSession session) {
            try {
                String lenStr = session.getHeaders().get("content-length");
                if (lenStr == null) return "";
                int contentLength = Integer.parseInt(lenStr.trim());
                if (contentLength <= 0 || contentLength > 1024 * 1024) return ""; // 1MB limit
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

    // ── Notification ────────────────────────────────────────────

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Claw Use Bridge",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("HTTP API bridge for AI agent device control");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Claw Use — Bridge Active")
                .setContentText(getServerUrl())
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
