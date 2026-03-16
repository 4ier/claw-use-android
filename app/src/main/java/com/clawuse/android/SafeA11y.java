package com.clawuse.android;

import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * All accessibility tree operations MUST go through this class.
 *
 * Uses a SINGLE-THREAD bounded executor so that at most ONE Binder thread
 * is ever consumed by getRootInActiveWindow() or tree traversal. Even if that
 * thread gets permanently stuck, only 1 out of ~16 Binder threads is lost,
 * and NanoHTTPD keeps serving normally.
 *
 * If the single worker is stuck, new tasks are silently discarded (return null/false).
 */
public class SafeA11y {
    private static final String TAG = "SafeA11y";
    public static final int DEFAULT_TIMEOUT_MS = 3000;

    // Single worker thread, queue size 1 (1 running + 1 waiting max).
    // If both slots are occupied, new tasks are silently discarded.
    private static final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1, 1, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1),
            r -> {
                Thread t = new Thread(r, "SafeA11y-worker");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardPolicy()
    );

    /**
     * Run any accessibility operation with a timeout.
     * At most 1 operation runs at a time. If the worker is stuck,
     * returns null immediately (task discarded).
     */
    public static <T> T run(Callable<T> task, int timeoutMs) {
        Future<T> future;
        try {
            future = executor.submit(task);
        } catch (Exception e) {
            // Executor rejected (shouldn't happen with DiscardPolicy, but safety)
            Log.w(TAG, "Task rejected: " + e.getMessage());
            return null;
        }

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            Log.w(TAG, "A11y operation timed out after " + timeoutMs + "ms");
            return null;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            Log.w(TAG, "A11y task was discarded (worker busy)");
            return null;
        } catch (java.util.concurrent.CancellationException e) {
            // Task was discarded by DiscardPolicy — Future was never started
            Log.w(TAG, "A11y task was discarded before execution");
            return null;
        } catch (Exception e) {
            Log.e(TAG, "A11y operation failed", e);
            return null;
        }
    }

    /**
     * Run a void accessibility operation with a timeout.
     */
    public static boolean runVoid(Runnable task, int timeoutMs) {
        Future<?> future;
        try {
            future = executor.submit(task);
        } catch (Exception e) {
            return false;
        }
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            future.cancel(true);
            Log.w(TAG, "A11y void operation timed out after " + timeoutMs + "ms");
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** Safely get root node with timeout. */
    public static AccessibilityNodeInfo getRootSafe(AccessibilityBridge bridge, int timeoutMs) {
        if (bridge == null) return null;
        return run(bridge::getRootNode, timeoutMs);
    }

    /** Safely get root node with default timeout. */
    public static AccessibilityNodeInfo getRootSafe(AccessibilityBridge bridge) {
        return getRootSafe(bridge, DEFAULT_TIMEOUT_MS);
    }

    /** Safely find node by text with timeout. */
    public static AccessibilityNodeInfo findByTextSafe(AccessibilityBridge bridge, AccessibilityNodeInfo root, String text, int timeoutMs) {
        if (bridge == null || root == null) return null;
        return run(() -> bridge.findByText(root, text), timeoutMs);
    }

    /** Safely find node by id with timeout. */
    public static AccessibilityNodeInfo findByIdSafe(AccessibilityBridge bridge, AccessibilityNodeInfo root, String id, int timeoutMs) {
        if (bridge == null || root == null) return null;
        return run(() -> bridge.findById(root, id), timeoutMs);
    }

    /** Safely find node by content description with timeout. */
    public static AccessibilityNodeInfo findByDescSafe(AccessibilityBridge bridge, AccessibilityNodeInfo root, String desc, int timeoutMs) {
        if (bridge == null || root == null) return null;
        return run(() -> bridge.findByDesc(root, desc), timeoutMs);
    }

    /** Check if the worker thread is currently stuck (busy for too long). */
    public static boolean isWorkerBusy() {
        return executor.getActiveCount() > 0 && executor.getQueue().remainingCapacity() == 0;
    }
}
