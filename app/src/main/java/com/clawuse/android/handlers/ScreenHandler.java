package com.clawuse.android.handlers;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.util.Base64;
import android.view.accessibility.AccessibilityNodeInfo;

import com.clawuse.android.AccessibilityBridge;
import com.clawuse.android.SafeA11y;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Handles the two perception endpoints:
 *
 *   GET /screen    → semantic, ref-indexed UI elements grouped by spatial zone
 *   GET /snapshot  → JPEG screenshot
 *
 * /screen returns elements with stable integer refs that /act can target.
 * Elements are grouped into zones by Y-coordinate bands and assigned roles
 * based on known resource IDs and heuristics.
 */
public class ScreenHandler implements RouteHandler {
    private static final int DEFAULT_TIMEOUT_MS = 3000;
    private static final int SCREENSHOT_TIMEOUT_MS = 5000;
    private AccessibilityBridge bridge;

    /** Temporary ref→node mapping, refreshed on each /screen call. */
    private static volatile List<RefEntry> currentRefs = Collections.emptyList();

    public void setBridge(AccessibilityBridge bridge) {
        this.bridge = bridge;
    }

    private AccessibilityBridge getBridge() {
        return bridge != null ? bridge : AccessibilityBridge.getInstance();
    }

    /** Look up a ref from the last /screen call. Returns bounds center or null. */
    public static int[] resolveRef(int ref) {
        List<RefEntry> refs = currentRefs;
        for (RefEntry e : refs) {
            if (e.ref == ref) return new int[]{e.cx, e.cy};
        }
        return null;
    }

    /** Look up a ref and return the full entry (for performAction click). */
    public static RefEntry resolveRefEntry(int ref) {
        List<RefEntry> refs = currentRefs;
        for (RefEntry e : refs) {
            if (e.ref == ref) return e;
        }
        return null;
    }

    @Override
    public String handle(String method, String path, Map<String, String> params, String body) throws Exception {
        if (path.equals("/snapshot") || path.startsWith("/snapshot")) {
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
            return handleSnapshot(quality, maxWidth);
        }
        // Default: /screen → semantic view
        return handleScreen();
    }

    // ── /screen — semantic, ref-indexed ─────────────────────────

    private String handleScreen() throws Exception {
        AccessibilityBridge b = getBridge();
        if (b == null) return "{\"error\":\"accessibility service not running\",\"elements\":[]}";

        if (SafeA11y.isWorkerBusy()) {
            return "{\"error\":\"accessibility worker busy\",\"elements\":[]}";
        }

        String result = SafeA11y.run(() -> {
            AccessibilityNodeInfo root = b.getRootNode();
            if (root == null) return "{\"error\":\"no active window\",\"elements\":[]}";

            try {
                // Collect all meaningful nodes
                List<RawNode> rawNodes = new ArrayList<>();
                collectNodes(root, rawNodes, 0);

                // Get screen dimensions for zone calculation
                android.util.DisplayMetrics dm = b.getResources().getDisplayMetrics();
                int screenH = dm.heightPixels;

                // Assign refs and build semantic output
                List<RefEntry> refs = new ArrayList<>();
                JSONArray elements = new JSONArray();
                int refCounter = 1;

                // Sort by Y then X for natural reading order
                Collections.sort(rawNodes, (a, c) -> {
                    int dy = Integer.compare(a.cy, c.cy);
                    return dy != 0 ? dy : Integer.compare(a.cx, c.cx);
                });

                // Assign zones based on Y position (5 bands)
                // top 0-15%: status/nav, 15-35%: header/instruction, 35-65%: content,
                // 65-85%: options, 85-100%: actions
                for (RawNode n : rawNodes) {
                    float yRatio = (float) n.cy / screenH;
                    String zone;
                    if (yRatio < 0.12f) zone = "nav";
                    else if (yRatio < 0.35f) zone = "header";
                    else if (yRatio < 0.65f) zone = "content";
                    else if (yRatio < 0.88f) zone = "options";
                    else zone = "actions";

                    JSONObject el = new JSONObject();
                    el.put("ref", refCounter);
                    if (n.text != null) el.put("text", n.text);
                    if (n.desc != null) el.put("desc", n.desc);
                    el.put("zone", zone);

                    // Determine role
                    String role = inferRole(n);
                    if (role != null) el.put("role", role);

                    if (n.clickable) el.put("click", true);
                    if (n.editable) el.put("edit", true);
                    if (n.scrollable) el.put("scroll", true);
                    if (n.checked) el.put("checked", true);

                    elements.put(el);
                    refs.add(new RefEntry(refCounter, n.cx, n.cy, n.bounds, n.text, n.desc, n.clickable, n.nodeId));
                    refCounter++;
                }

                // Store refs for /act resolution
                currentRefs = refs;

                JSONObject out = new JSONObject();
                CharSequence pkg = root.getPackageName();
                out.put("package", pkg != null ? pkg.toString() : "");
                out.put("elements", elements);
                out.put("count", elements.length());
                return out.toString();
            } finally {
                root.recycle();
            }
        }, DEFAULT_TIMEOUT_MS);

        if (result == null) {
            return "{\"error\":\"screen read timed out\",\"elements\":[]}";
        }
        return result;
    }

    /** Collect all nodes with meaningful content (text, desc, clickable, editable). */
    private void collectNodes(AccessibilityNodeInfo node, List<RawNode> out, int depth) {
        if (node == null || depth > 20 || Thread.currentThread().isInterrupted()) return;

        try {
            String text = node.getText() != null ? node.getText().toString().trim() : null;
            String desc = node.getContentDescription() != null ? node.getContentDescription().toString().trim() : null;
            String id = node.getViewIdResourceName();
            boolean clickable = node.isClickable();
            boolean editable = node.isEditable();
            boolean scrollable = node.isScrollable();
            boolean checkable = node.isCheckable();
            boolean checked = node.isChecked();

            if (text != null && text.isEmpty()) text = null;
            if (desc != null && desc.isEmpty()) desc = null;

            boolean meaningful = text != null || desc != null || editable;

            if (meaningful) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                // Skip zero-size or off-screen nodes
                if (bounds.width() > 0 && bounds.height() > 0 && bounds.right > 0 && bounds.bottom > 0) {
                    RawNode rn = new RawNode();
                    rn.text = text;
                    rn.desc = desc;
                    rn.nodeId = id;
                    rn.bounds = bounds;
                    rn.cx = bounds.centerX();
                    rn.cy = bounds.centerY();
                    rn.clickable = clickable;
                    rn.editable = editable;
                    rn.scrollable = scrollable;
                    rn.checkable = checkable;
                    rn.checked = checked;
                    rn.depth = depth;
                    out.add(rn);
                }
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    collectNodes(child, out, depth + 1);
                    child.recycle();
                }
            }
        } catch (Exception ignored) {}
    }

    /** Infer semantic role from resource ID and element properties. */
    private String inferRole(RawNode n) {
        if (n.nodeId != null) {
            String id = n.nodeId.toLowerCase();
            if (id.contains("instruction") || id.contains("prompt_header")) return "instruction";
            if (id.contains("hintableprompt") || id.contains("prompt")) return "prompt";
            if (id.contains("heartsIndicator") || id.contains("hearts")) return "status";
        }
        if (n.editable) return "input";
        if (n.checkable) return "toggle";
        // Clickable nodes in the options zone with desc but no text → likely word tiles
        if (n.clickable && n.desc != null && n.text == null) return "tile";
        if (n.clickable && n.text != null) return "button";
        return null;
    }

    // ── /snapshot — JPEG screenshot ─────────────────────────────

    private String handleSnapshot(int quality, int maxWidth) throws Exception {
        AccessibilityBridge b = getBridge();
        if (b == null) return "{\"error\":\"accessibility service not running\"}";
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
                    if (bitmap == null) { error[0] = "failed to create bitmap"; latch.countDown(); return; }

                    if (bitmap.getWidth() > mw) {
                        float scale = (float) mw / bitmap.getWidth();
                        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, mw, (int)(bitmap.getHeight() * scale), true);
                        bitmap.recycle();
                        bitmap = scaled;
                    }
                    Bitmap swBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                    bitmap.recycle();

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    swBitmap.compress(Bitmap.CompressFormat.JPEG, q, baos);
                    swBitmap.recycle();
                    result[0] = baos.toByteArray();
                } catch (Exception e) { error[0] = e.getMessage(); }
                latch.countDown();
            }

            @Override
            public void onFailure(int errorCode) {
                error[0] = "screenshot failed code " + errorCode;
                latch.countDown();
            }
        });

        if (!latch.await(SCREENSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return "{\"error\":\"screenshot timed out\"}";
        }
        if (error[0] != null) return new JSONObject().put("error", error[0]).toString();
        if (result[0] == null) return "{\"error\":\"screenshot null\"}";

        return new JSONObject()
                .put("screenshot", Base64.encodeToString(result[0], Base64.NO_WRAP))
                .put("format", "jpeg")
                .put("quality", quality)
                .put("sizeBytes", result[0].length)
                .toString();
    }

    // ── Data classes ─────────────────────────────────────────────

    private static class RawNode {
        String text, desc, nodeId;
        Rect bounds;
        int cx, cy, depth;
        boolean clickable, editable, scrollable, checkable, checked;
    }

    /** Public ref entry for /act resolution. */
    public static class RefEntry {
        public final int ref, cx, cy;
        public final Rect bounds;
        public final String text, desc;
        public final boolean clickable;
        public final String nodeId;

        RefEntry(int ref, int cx, int cy, Rect bounds, String text, String desc, boolean clickable, String nodeId) {
            this.ref = ref; this.cx = cx; this.cy = cy; this.bounds = bounds;
            this.text = text; this.desc = desc; this.clickable = clickable; this.nodeId = nodeId;
        }
    }

    // ── Backward compat: buildQuickState for other handlers ─────

    public static String buildQuickState(AccessibilityBridge bridge, int maxDepth, int maxTexts, int maxDescs) throws Exception {
        AccessibilityNodeInfo root = bridge.getRootNode();
        if (root == null) return "{\"error\":\"no active window\"}";
        try {
            JSONObject json = new JSONObject();
            json.put("package", root.getPackageName() != null ? root.getPackageName().toString() : "");
            json.put("timestamp", System.currentTimeMillis());
            JSONArray topTexts = new JSONArray();
            JSONArray topDescs = new JSONArray();
            int[] counter = {0};
            collectQuickInfo(root, topTexts, topDescs, counter, 0, maxDepth, maxTexts, maxDescs);
            json.put("topTexts", topTexts);
            json.put("topDescs", topDescs);
            json.put("count", counter[0]);
            return json.toString();
        } finally { root.recycle(); }
    }

    private static void collectQuickInfo(AccessibilityNodeInfo node, JSONArray texts, JSONArray descs,
                                          int[] counter, int depth, int maxDepth, int maxTexts, int maxDescs) {
        if (node == null || depth > maxDepth || Thread.currentThread().isInterrupted()) return;
        counter[0]++;
        try {
            if (texts.length() < maxTexts) {
                CharSequence text = node.getText();
                if (text != null && text.length() > 0) texts.put(text.toString());
            }
            if (descs.length() < maxDescs) {
                CharSequence desc = node.getContentDescription();
                if (desc != null && desc.length() > 0) descs.put(desc.toString());
            }
            if (depth >= maxDepth) return;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) { collectQuickInfo(child, texts, descs, counter, depth + 1, maxDepth, maxTexts, maxDescs); child.recycle(); }
            }
        } catch (Exception ignored) {}
    }
}
