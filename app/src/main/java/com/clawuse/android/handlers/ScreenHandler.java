package com.clawuse.android.handlers;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.util.Base64;
import android.view.accessibility.AccessibilityNodeInfo;

import com.clawuse.android.AccessibilityBridge;
import com.clawuse.android.SafeA11y;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import android.graphics.Rect;

/**
 * Handles /screen (UI tree) and /screenshot (actual screenshot) endpoints.
 */
public class ScreenHandler implements RouteHandler {
    private static final int DEFAULT_TIMEOUT_MS = 3000;
    private static final int SCREENSHOT_TIMEOUT_MS = 5000;
    private AccessibilityBridge bridge;

    public void setBridge(AccessibilityBridge bridge) {
        this.bridge = bridge;
    }

    private AccessibilityBridge getBridge() {
        return bridge != null ? bridge : AccessibilityBridge.getInstance();
    }

    @Override
    public String handle(String method, String path, Map<String, String> params, String body) throws Exception {
        if (path.startsWith("/screenshot")) {
            int quality = 50;
            if (params.containsKey("quality")) {
                try { quality = Math.max(10, Math.min(100, Integer.parseInt(params.get("quality")))); }
                catch (NumberFormatException ignored) {}
            }
            int maxWidth = 720;
            if (params.containsKey("maxWidth")) {
                try { maxWidth = Math.max(100, Math.min(2000, Integer.parseInt(params.get("maxWidth")))); }
                catch (NumberFormatException ignored) {}
            }
            return handleScreenshot(quality, maxWidth);
        }
        boolean compact = params.containsKey("compact");
        int timeoutMs = DEFAULT_TIMEOUT_MS;
        if (params.containsKey("timeout")) {
            try {
                timeoutMs = Integer.parseInt(params.get("timeout"));
                if (timeoutMs < 500) timeoutMs = 500;
                if (timeoutMs > 15000) timeoutMs = 15000;
            } catch (NumberFormatException ignored) {}
        }
        return getScreenJson(compact, timeoutMs);
    }

    private String handleScreenshot(int quality, int maxWidth) throws Exception {
        AccessibilityBridge b = getBridge();
        if (b == null) {
            return "{\"error\":\"accessibility service not running\"}";
        }

        if (Build.VERSION.SDK_INT < 30) {
            return "{\"error\":\"screenshot requires API 30+\",\"apiLevel\":" + Build.VERSION.SDK_INT + "}";
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final byte[][] result = {null};
        final String[] error = {null};
        final int q = quality;
        final int mw = maxWidth;

        b.takeScreenshot(0, b.getMainExecutor(), new AccessibilityService.TakeScreenshotCallback() {
            @Override
            public void onSuccess(AccessibilityService.ScreenshotResult screenshot) {
                try {
                    HardwareBuffer hwBuffer = screenshot.getHardwareBuffer();
                    Bitmap bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.getColorSpace());
                    hwBuffer.close();

                    if (bitmap == null) {
                        error[0] = "failed to create bitmap from hardware buffer";
                        latch.countDown();
                        return;
                    }

                    // Scale down if needed
                    if (bitmap.getWidth() > mw) {
                        float scale = (float) mw / bitmap.getWidth();
                        int newH = (int) (bitmap.getHeight() * scale);
                        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, mw, newH, true);
                        bitmap.recycle();
                        bitmap = scaled;
                    }

                    // Convert to software bitmap for JPEG compression
                    Bitmap swBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                    bitmap.recycle();

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    swBitmap.compress(Bitmap.CompressFormat.JPEG, q, baos);
                    swBitmap.recycle();
                    result[0] = baos.toByteArray();
                } catch (Exception e) {
                    error[0] = e.getMessage();
                }
                latch.countDown();
            }

            @Override
            public void onFailure(int errorCode) {
                error[0] = "screenshot failed with code " + errorCode;
                latch.countDown();
            }
        });

        boolean ok = latch.await(SCREENSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        if (!ok) {
            return "{\"error\":\"screenshot timed out\"}";
        }
        if (error[0] != null) {
            return new JSONObject().put("error", error[0]).toString();
        }
        if (result[0] == null) {
            return "{\"error\":\"screenshot returned null\"}";
        }

        String base64 = Base64.encodeToString(result[0], Base64.NO_WRAP);
        return new JSONObject()
                .put("screenshot", base64)
                .put("format", "jpeg")
                .put("quality", quality)
                .put("sizeBytes", result[0].length)
                .put("timestamp", System.currentTimeMillis())
                .toString();
    }

    private String getScreenJson(boolean compact, int timeoutMs) throws Exception {
        AccessibilityBridge b = getBridge();
        if (b == null) {
            return "{\"error\":\"accessibility service not running\",\"nodes\":[]}";
        }

        if (SafeA11y.isWorkerBusy()) {
            return new JSONObject()
                    .put("error", "accessibility worker busy (previous request stuck)")
                    .put("nodes", new JSONArray())
                    .put("hint", "try again later or navigate away from current app")
                    .toString();
        }

        String result = SafeA11y.run(() -> {
            AccessibilityNodeInfo root = b.getRootNode();
            if (root == null) {
                // Fallback: try getAllWindowRoots() for lock screen etc.
                java.util.List<AccessibilityNodeInfo> allRoots = b.getAllWindowRoots();
                if (allRoots.isEmpty()) {
                    return "{\"error\":\"no active window\",\"nodes\":[]}";
                }
                try {
                    JSONObject json = new JSONObject();
                    json.put("package", "");
                    json.put("timestamp", System.currentTimeMillis());
                    json.put("source", "allWindows");
                    JSONArray nodes = new JSONArray();
                    AtomicBoolean cancelled = new AtomicBoolean(false);
                    for (AccessibilityNodeInfo r : allRoots) {
                        traverseNode(r, nodes, 0, compact, cancelled);
                    }
                    json.put("nodes", nodes);
                    json.put("count", nodes.length());
                    return json.toString();
                } finally {
                    for (AccessibilityNodeInfo r : allRoots) {
                        try { r.recycle(); } catch (Exception ignored) {}
                    }
                }
            }

            try {
                JSONObject json = new JSONObject();
                json.put("package", root.getPackageName());
                json.put("timestamp", System.currentTimeMillis());
                JSONArray nodes = new JSONArray();
                AtomicBoolean cancelled = new AtomicBoolean(false);
                traverseNode(root, nodes, 0, compact, cancelled);
                json.put("nodes", nodes);
                json.put("count", nodes.length());
                return json.toString();
            } finally {
                root.recycle();
            }
        }, timeoutMs);

        if (result == null) {
            return new JSONObject()
                    .put("error", "screen read timed out")
                    .put("nodes", new JSONArray())
                    .put("truncated", true)
                    .put("timeoutMs", timeoutMs)
                    .toString();
        }

        return result;
    }

    private void traverseNode(AccessibilityNodeInfo node, JSONArray nodes, int depth, boolean compact, AtomicBoolean cancelled) {
        if (node == null || cancelled.get() || Thread.currentThread().isInterrupted()) return;
        try {
            String text = node.getText() != null ? node.getText().toString() : "";
            String desc = node.getContentDescription() != null ? node.getContentDescription().toString() : "";
            String id = node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "";
            String cls = node.getClassName() != null ? node.getClassName().toString() : "";
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);

            boolean hasContent = !text.isEmpty() || !desc.isEmpty() || node.isClickable()
                    || node.isEditable() || node.isScrollable() || node.isCheckable();

            if (!compact || hasContent) {
                JSONObject obj = new JSONObject();
                if (!text.isEmpty()) obj.put("text", text);
                if (!desc.isEmpty()) obj.put("desc", desc);
                if (!id.isEmpty()) obj.put("id", id);
                if (!compact) obj.put("cls", cls);
                obj.put("bounds", bounds.left + "," + bounds.top + "," + bounds.right + "," + bounds.bottom);
                if (node.isClickable()) obj.put("click", true);
                if (node.isEditable()) obj.put("edit", true);
                if (node.isScrollable()) obj.put("scroll", true);
                if (node.isCheckable()) obj.put("checkable", true);
                if (node.isChecked()) obj.put("checked", true);
                if (node.isFocused()) obj.put("focused", true);
                if (node.isSelected()) obj.put("selected", true);
                if (!compact) obj.put("depth", depth);
                nodes.put(obj);
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                if (cancelled.get() || Thread.currentThread().isInterrupted()) return;
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    traverseNode(child, nodes, depth + 1, compact, cancelled);
                    child.recycle();
                }
            }
        } catch (Exception ignored) {}
    }
}
