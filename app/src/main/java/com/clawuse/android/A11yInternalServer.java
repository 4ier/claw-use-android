package com.clawuse.android;

import android.util.Log;

import com.clawuse.android.handlers.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * Internal HTTP server running on 127.0.0.1:7334 in the MAIN process.
 * Handles all accessibility-dependent operations. No auth required (localhost only).
 *
 * BridgeService (in :http process) proxies requests here with strict timeouts.
 * If this server hangs due to a11y IPC issues, only the main process is affected.
 * The external HTTP server on :7333 remains responsive and returns timeout errors.
 */
public class A11yInternalServer extends NanoHTTPD {
    private static final String TAG = "A11yInternal";

    private final ScreenHandler screenHandler;
    private final GestureHandler gestureHandler;
    private final GlobalHandler globalHandler;
    private final ScreenControlHandler screenControlHandler;
    private final NotificationHandler notificationHandler;
    private final IntentHandler intentHandler;
    private final AudioHandler audioHandler;
    private final AudioRecordHandler audioRecordHandler;
    private final FlowHandler flowHandler;
    private final BatchHandler batchHandler;
    private final ClipboardHandler clipboardHandler;
    private final VolumeHandler volumeHandler;
    private final BatteryHandler batteryHandler;
    private final WifiHandler wifiHandler;
    private final VibrateHandler vibrateHandler;
    private final LocationHandler locationHandler;
    private final ContactsHandler contactsHandler;
    private final SmsHandler smsHandler;
    private final FileHandler fileHandler;
    private final CameraHandler cameraHandler;
    private final RouteHandler overlayHandler;

    public A11yInternalServer(AccessibilityBridge bridge) {
        super("127.0.0.1", 7334);
        android.content.Context ctx = bridge;
        // All handlers run in the main process, direct access to AccessibilityBridge
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
        this.clipboardHandler = new ClipboardHandler(ctx);
        this.volumeHandler = new VolumeHandler(ctx);
        this.batteryHandler = new BatteryHandler(ctx);
        this.wifiHandler = new WifiHandler(ctx);
        this.vibrateHandler = new VibrateHandler(ctx);
        this.locationHandler = new LocationHandler(ctx);
        this.contactsHandler = new ContactsHandler(ctx);
        this.smsHandler = new SmsHandler(ctx);
        this.fileHandler = new FileHandler(ctx);
        this.cameraHandler = new CameraHandler(ctx);
        this.overlayHandler = (method, path, params, body) -> {
            OverlayManager overlay = OverlayManager.getInstanceOrNull();
            if (overlay == null) return "{\"error\":\"overlay not initialized\"}";
            String action = params.getOrDefault("action", "status");
            if ("activity".equals(action)) {
                if (overlay.isTakenOver()) {
                    return "{\"paused\":true,\"reason\":\"user takeover\",\"hint\":\"user has taken control, retry later\"}";
                }
                overlay.onApiActivity();
                return "{\"paused\":false}";
            }
            return "{\"takenOver\":" + overlay.isTakenOver() + "}";
        };
    }

    @Override
    public Response serve(IHTTPSession session) {
        String path = session.getUri();
        String method = session.getMethod().name();
        Map<String, String> params = session.getParms();

        // Read POST body as raw UTF-8 (NanoHTTPD's parseBody uses ISO-8859-1)
        String body = "";
        if ("POST".equals(method)) {
            body = readBodyUtf8(session);
        }

        // Health check for the internal server
        if ("/a11y/ping".equals(path)) {
            boolean a11yRunning = AccessibilityBridge.isRunning();
            return newFixedLengthResponse(Response.Status.OK, "application/json",
                    "{\"status\":\"ok\",\"a11y\":" + a11yRunning + "}");
        }

        try {
            RouteHandler handler = resolveHandler(path);
            if (handler == null) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json",
                        "{\"error\":\"not found\",\"path\":\"" + path + "\"}");
            }
            // Strip /a11y prefix before passing to handlers ("/a11y" = 5 chars)
            String handlerPath = path.startsWith("/a11y") ? path.substring(5) : path;
            String json = handler.handle(method, handlerPath, params, body);
            return newFixedLengthResponse(Response.Status.OK, "application/json; charset=UTF-8", json);
        } catch (Exception e) {
            Log.e(TAG, "Internal handler error for " + path, e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                    "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private RouteHandler resolveHandler(String path) {
        // All paths are prefixed with /a11y/ from the proxy
        if (path.startsWith("/a11y/screen/")) return screenControlHandler; // /screen/lock etc.
        if (path.startsWith("/a11y/screen")) return screenHandler;         // /screen, /screenshot

        if ("/a11y/click".equals(path) || "/a11y/tap".equals(path) || "/a11y/longpress".equals(path)
                || "/a11y/swipe".equals(path) || "/a11y/type".equals(path) || "/a11y/scroll".equals(path)) {
            return gestureHandler;
        }

        if ("/a11y/global".equals(path)) return globalHandler;

        if (path.startsWith("/a11y/notifications")) return notificationHandler;

        if ("/a11y/intent".equals(path)) return intentHandler;

        if (path.startsWith("/a11y/tts")) return audioHandler;

        if ("/a11y/audio/record".equals(path)) return audioRecordHandler;

        if ("/a11y/flow".equals(path)) return flowHandler;

        if ("/a11y/batch".equals(path)) return batchHandler;

        // Overlay management (activity notification + takeover check)
        if ("/a11y/overlay".equals(path)) return overlayHandler;

        if ("/a11y/clipboard".equals(path)) return clipboardHandler;
        if ("/a11y/volume".equals(path)) return volumeHandler;
        if ("/a11y/battery".equals(path)) return batteryHandler;
        if ("/a11y/wifi".equals(path)) return wifiHandler;
        if ("/a11y/vibrate".equals(path)) return vibrateHandler;
        if ("/a11y/location".equals(path)) return locationHandler;
        if ("/a11y/contacts".equals(path)) return contactsHandler;
        if ("/a11y/sms".equals(path)) return smsHandler;
        if (path.startsWith("/a11y/file")) return fileHandler;
        if ("/a11y/camera".equals(path)) return cameraHandler;

        return null;
    }

    public void startServer() {
        try {
            start(5000, false);
            Log.i(TAG, "Internal a11y server started on 127.0.0.1:7334");
        } catch (IOException e) {
            Log.e(TAG, "Failed to start internal server", e);
        }
    }

    public void stopServer() {
        stop();
        if (audioHandler != null) audioHandler.shutdown();
        Log.i(TAG, "Internal a11y server stopped");
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    /** Read POST body as raw UTF-8 bytes, bypassing NanoHTTPD's broken parseBody. */
    private String readBodyUtf8(IHTTPSession session) {
        try {
            String lenStr = session.getHeaders().get("content-length");
            if (lenStr == null) return "";
            int contentLength = Integer.parseInt(lenStr.trim());
            if (contentLength <= 0 || contentLength > 10 * 1024 * 1024) return ""; // 10MB max
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
}
