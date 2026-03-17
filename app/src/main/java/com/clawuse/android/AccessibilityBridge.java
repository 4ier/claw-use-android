package com.clawuse.android;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.List;

/**
 * Core AccessibilityService that provides UI tree reading, gesture dispatch,
 * and global actions. Singleton pattern for access from HTTP handlers.
 */
public class AccessibilityBridge extends AccessibilityService {
    private static final String TAG = "ClawA11y";
    private static volatile AccessibilityBridge instance;
    private A11yInternalServer internalServer;

    public static AccessibilityBridge getInstance() {
        return instance;
    }

    public static boolean isRunning() {
        return instance != null;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "AccessibilityBridge connected");

        // Initialize overlay manager (needs a11y service context for TYPE_ACCESSIBILITY_OVERLAY)
        OverlayManager.getInstance(this);

        // Start internal a11y server for cross-process communication
        internalServer = new A11yInternalServer(this);
        internalServer.startServer();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // On-demand reading via getRootInActiveWindow(), no event processing needed
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "AccessibilityBridge interrupted");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (internalServer != null) internalServer.stopServer();
        instance = null;
        Log.i(TAG, "AccessibilityBridge destroyed");
    }

    // ── Public API for handlers ─────────────────────────────────

    /** Get the root node of the currently active window. Caller must recycle. */
    public AccessibilityNodeInfo getRootNode() {
        return getRootInActiveWindow();
    }

    /**
     * Get root nodes from ALL windows (including lock screen).
     * Use when getRootNode() returns null (e.g. on lock screen).
     * Caller must recycle all returned nodes.
     */
    public List<AccessibilityNodeInfo> getAllWindowRoots() {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            for (AccessibilityWindowInfo w : windows) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root != null) {
                    roots.add(root);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "getAllWindowRoots failed: " + e.getMessage());
        }
        return roots;
    }

    /** Perform a global action (BACK, HOME, RECENTS, etc.) */
    public boolean doGlobalAction(int action) {
        return performGlobalAction(action);
    }

    /** Dispatch a tap gesture at the given coordinates. */
    public boolean tap(int x, int y) {
        return dispatchTapGesture(x, y, 50);
    }

    /** Dispatch a long press gesture at the given coordinates. */
    public boolean longPress(int x, int y, long durationMs) {
        return dispatchTapGesture(x, y, durationMs);
    }

    /** Dispatch a swipe gesture. */
    public boolean swipe(int x1, int y1, int x2, int y2, long durationMs) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs));
        return dispatchGesture(builder.build(), null, null);
    }

    /** Take a screenshot (API 30+). Returns via callback. */
    public void takeScreenshot(TakeScreenshotCallback callback) {
        if (Build.VERSION.SDK_INT >= 30) {
            takeScreenshot(0, getMainExecutor(), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult screenshot) {
                    callback.onSuccess(screenshot);
                }

                @Override
                public void onFailure(int errorCode) {
                    callback.onFailure(errorCode);
                }
            });
        }
    }

    /**
     * Find a node by text using system API (single IPC call to system_server).
     * Two-pass: exact match first, then first contains match.
     *
     * OLD approach: manual DFS = N nodes × ~7 IPC calls each = thousands of Binder roundtrips.
     * NEW approach: findAccessibilityNodeInfosByText() = 1 IPC call, search runs in system_server.
     */
    public AccessibilityNodeInfo findByText(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null || text.isEmpty()) return null;

        // System API: searches in system_server process, single IPC
        List<AccessibilityNodeInfo> results = root.findAccessibilityNodeInfosByText(text);
        if (results == null || results.isEmpty()) return null;

        String lower = text.toLowerCase();

        // Pass 1: exact match (case-insensitive)
        for (AccessibilityNodeInfo node : results) {
            String nodeText = node.getText() != null ? node.getText().toString() : "";
            if (nodeText.toLowerCase().equals(lower)) {
                // Recycle all others
                for (AccessibilityNodeInfo other : results) {
                    if (other != node) {
                        try { other.recycle(); } catch (Exception ignored) {}
                    }
                }
                return node;
            }
        }

        // Pass 2: return first contains match, recycle the rest
        AccessibilityNodeInfo first = results.get(0);
        for (int i = 1; i < results.size(); i++) {
            try { results.get(i).recycle(); } catch (Exception ignored) {}
        }
        return first;
    }

    /**
     * Find a node by resource ID using system API (single IPC call).
     * findAccessibilityNodeInfosByViewId() requires full resource name like
     * "com.android.settings:id/title". For partial IDs, falls back to
     * depth-limited manual search.
     */
    public AccessibilityNodeInfo findById(AccessibilityNodeInfo root, String id) {
        if (root == null || id == null || id.isEmpty()) return null;

        // System API works best with fully-qualified IDs (package:id/name)
        // Try it first — single IPC call
        List<AccessibilityNodeInfo> results = root.findAccessibilityNodeInfosByViewId(id);
        if (results != null && !results.isEmpty()) {
            AccessibilityNodeInfo first = results.get(0);
            for (int i = 1; i < results.size(); i++) {
                try { results.get(i).recycle(); } catch (Exception ignored) {}
            }
            return first;
        }

        // Fallback for partial IDs: depth-limited manual search (max depth 15)
        return findByIdManual(root, id, 0, 15);
    }

    private AccessibilityNodeInfo findByIdManual(AccessibilityNodeInfo node, String id, int depth, int maxDepth) {
        if (node == null || depth > maxDepth) return null;
        String nodeId = node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "";
        if (!nodeId.isEmpty() && nodeId.contains(id)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findByIdManual(child, id, depth + 1, maxDepth);
                if (found != null) {
                    if (found != child) child.recycle();
                    return found;
                }
                child.recycle();
            }
        }
        return null;
    }

    /**
     * Find a node by content description (case-insensitive partial match).
     * No system API for desc search — manual traversal with depth limit.
     */
    public AccessibilityNodeInfo findByDesc(AccessibilityNodeInfo root, String desc) {
        if (root == null || desc == null || desc.isEmpty()) return null;
        return findByDescDepthLimited(root, desc.toLowerCase(), 0, 15);
    }

    private AccessibilityNodeInfo findByDescDepthLimited(AccessibilityNodeInfo node, String descLower, int depth, int maxDepth) {
        if (node == null || depth > maxDepth) return null;
        String nodeDesc = node.getContentDescription() != null ? node.getContentDescription().toString() : "";
        if (!nodeDesc.isEmpty() && nodeDesc.toLowerCase().contains(descLower)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findByDescDepthLimited(child, descLower, depth + 1, maxDepth);
                if (found != null) {
                    if (found != child) child.recycle();
                    return found;
                }
                child.recycle();
            }
        }
        return null;
    }

    /** Click a node. Falls back to tap gesture if ACTION_CLICK fails. */
    public boolean clickNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        boolean clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!clicked) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            clicked = tap(bounds.centerX(), bounds.centerY());
        }
        return clicked;
    }

    // ── Private helpers ─────────────────────────────────────────

    private boolean dispatchTapGesture(int x, int y, long durationMs) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs));
        return dispatchGesture(builder.build(), null, null);
    }
}
