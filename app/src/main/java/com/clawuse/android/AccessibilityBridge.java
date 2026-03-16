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

    /** Find a node by text (case-insensitive partial match). */
    /**
     * Find a node by text. Two-pass: exact match first, then contains fallback.
     * This prevents "eau" matching "nouveau" when an exact "eau" node exists.
     */
    public AccessibilityNodeInfo findByText(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null || text.isEmpty()) return null;
        // Pass 1: exact match (case-insensitive)
        AccessibilityNodeInfo exact = findByTextPass(root, text, true);
        if (exact != null) return exact;
        // Pass 2: contains match (case-insensitive)
        return findByTextPass(root, text, false);
    }

    private AccessibilityNodeInfo findByTextPass(AccessibilityNodeInfo root, String text, boolean exactOnly) {
        if (root == null) return null;
        String nodeText = root.getText() != null ? root.getText().toString() : "";
        String lower = text.toLowerCase();
        if (exactOnly) {
            if (nodeText.toLowerCase().equals(lower)) return root;
        } else {
            if (!nodeText.isEmpty() && nodeText.toLowerCase().contains(lower)) return root;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findByTextPass(child, text, exactOnly);
                if (found != null) {
                    if (found != child) child.recycle();
                    return found;
                }
                child.recycle();
            }
        }
        return null;
    }

    /** Find a node by resource ID (partial match). */
    public AccessibilityNodeInfo findById(AccessibilityNodeInfo root, String id) {
        if (root == null) return null;
        String nodeId = root.getViewIdResourceName() != null ? root.getViewIdResourceName() : "";
        if (!id.isEmpty() && nodeId.contains(id)) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findById(child, id);
                if (found != null) {
                    if (found != child) child.recycle();
                    return found;
                }
                child.recycle();
            }
        }
        return null;
    }

    /** Find a node by content description (case-insensitive partial match). */
    public AccessibilityNodeInfo findByDesc(AccessibilityNodeInfo root, String desc) {
        if (root == null) return null;
        String nodeDesc = root.getContentDescription() != null ? root.getContentDescription().toString() : "";
        if (!desc.isEmpty() && nodeDesc.toLowerCase().contains(desc.toLowerCase())) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findByDesc(child, desc);
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
