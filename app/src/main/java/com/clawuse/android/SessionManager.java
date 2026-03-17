package com.clawuse.android;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Singleton that manages one active session at a time.
 * Auto-timeout if no action within timeoutMs. Max session duration 5 min.
 * Thread-safe.
 */
public class SessionManager {
    private static final String TAG = "SessionManager";
    private static final long MAX_SESSION_DURATION_MS = 5 * 60 * 1000; // 5 min
    private static final long DEFAULT_TIMEOUT_MS = 60_000; // 60s

    private static volatile SessionManager instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicReference<SessionInfo> activeSession = new AtomicReference<>(null);
    private Runnable idleTimeoutRunnable;
    private Runnable maxDurationRunnable;

    public static SessionManager get() {
        if (instance == null) {
            synchronized (SessionManager.class) {
                if (instance == null) {
                    instance = new SessionManager();
                }
            }
        }
        return instance;
    }

    private SessionManager() {}

    /**
     * Start a new session. Returns sessionId. Fails if a session is already active.
     */
    public synchronized String startSession(String agentName, String goal, long timeoutMs) {
        SessionInfo existing = activeSession.get();
        if (existing != null && "active".equals(existing.status)) {
            Log.w(TAG, "Session already active: " + existing.sessionId);
            return null; // caller should end existing session first
        }

        String sessionId = UUID.randomUUID().toString().substring(0, 8);
        long timeout = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;

        SessionInfo session = new SessionInfo();
        session.sessionId = sessionId;
        session.agentName = agentName;
        session.goal = goal;
        session.startTime = System.currentTimeMillis();
        session.actionCount = 0;
        session.status = "active";
        session.timeoutMs = timeout;

        activeSession.set(session);
        resetIdleTimeout(session);
        scheduleMaxDuration(session);

        Log.i(TAG, "Session started: " + sessionId + " agent=" + agentName + " goal=" + goal);
        return sessionId;
    }

    /**
     * End a session normally.
     */
    public synchronized SessionInfo endSession(String sessionId, String result) {
        SessionInfo session = activeSession.get();
        if (session == null || !session.sessionId.equals(sessionId)) {
            return null;
        }
        session.status = "ended";
        session.result = result;
        session.endTime = System.currentTimeMillis();
        clearTimers();
        activeSession.set(null);
        Log.i(TAG, "Session ended: " + sessionId + " result=" + result
                + " actions=" + session.actionCount + " duration=" + session.getDurationMs() + "ms");
        return session;
    }

    /**
     * Get session by ID.
     */
    public SessionInfo getSession(String sessionId) {
        SessionInfo session = activeSession.get();
        if (session != null && session.sessionId.equals(sessionId)) {
            return session;
        }
        return null;
    }

    /**
     * Get the current active session, or null.
     */
    public SessionInfo getActiveSession() {
        SessionInfo session = activeSession.get();
        if (session != null && "active".equals(session.status)) {
            return session;
        }
        return null;
    }

    /**
     * Record an action within a session.
     */
    public synchronized void recordAction(String sessionId, String description) {
        SessionInfo session = activeSession.get();
        if (session == null || !session.sessionId.equals(sessionId) || !"active".equals(session.status)) {
            return;
        }
        session.actionCount++;
        session.lastActionTime = System.currentTimeMillis();
        resetIdleTimeout(session);
        Log.d(TAG, "Action #" + session.actionCount + " in session " + sessionId + ": " + description);
    }

    /**
     * Terminate the active session due to user takeover.
     */
    public synchronized SessionInfo terminateByTakeover() {
        SessionInfo session = activeSession.get();
        if (session == null || !"active".equals(session.status)) {
            return null;
        }
        session.status = "takeover";
        session.result = "user_takeover";
        session.endTime = System.currentTimeMillis();
        clearTimers();
        activeSession.set(null);
        Log.i(TAG, "Session terminated by takeover: " + session.sessionId);
        return session;
    }

    // ── Timeout management ──

    private void resetIdleTimeout(SessionInfo session) {
        if (idleTimeoutRunnable != null) {
            handler.removeCallbacks(idleTimeoutRunnable);
        }
        idleTimeoutRunnable = () -> {
            synchronized (SessionManager.this) {
                SessionInfo current = activeSession.get();
                if (current != null && current.sessionId.equals(session.sessionId)
                        && "active".equals(current.status)) {
                    current.status = "timeout";
                    current.result = "idle_timeout";
                    current.endTime = System.currentTimeMillis();
                    activeSession.set(null);
                    Log.i(TAG, "Session timed out (idle): " + session.sessionId);
                }
            }
        };
        handler.postDelayed(idleTimeoutRunnable, session.timeoutMs);
    }

    private void scheduleMaxDuration(SessionInfo session) {
        if (maxDurationRunnable != null) {
            handler.removeCallbacks(maxDurationRunnable);
        }
        maxDurationRunnable = () -> {
            synchronized (SessionManager.this) {
                SessionInfo current = activeSession.get();
                if (current != null && current.sessionId.equals(session.sessionId)
                        && "active".equals(current.status)) {
                    current.status = "timeout";
                    current.result = "max_duration";
                    current.endTime = System.currentTimeMillis();
                    activeSession.set(null);
                    Log.i(TAG, "Session timed out (max duration): " + session.sessionId);
                }
            }
        };
        handler.postDelayed(maxDurationRunnable, MAX_SESSION_DURATION_MS);
    }

    private void clearTimers() {
        if (idleTimeoutRunnable != null) {
            handler.removeCallbacks(idleTimeoutRunnable);
            idleTimeoutRunnable = null;
        }
        if (maxDurationRunnable != null) {
            handler.removeCallbacks(maxDurationRunnable);
            maxDurationRunnable = null;
        }
    }

    // ── SessionInfo ──

    public static class SessionInfo {
        public String sessionId;
        public String agentName;
        public String goal;
        public long startTime;
        public long endTime;
        public long lastActionTime;
        public int actionCount;
        public String status; // active, ended, timeout, takeover
        public String result;
        public long timeoutMs;

        public long getDurationMs() {
            long end = endTime > 0 ? endTime : System.currentTimeMillis();
            return end - startTime;
        }

        public JSONObject toJson() {
            try {
                JSONObject j = new JSONObject();
                j.put("sessionId", sessionId);
                j.put("agentName", agentName);
                j.put("goal", goal);
                j.put("startTime", startTime);
                j.put("actionCount", actionCount);
                j.put("status", status);
                j.put("durationMs", getDurationMs());
                if (result != null) j.put("result", result);
                return j;
            } catch (Exception e) {
                return new JSONObject();
            }
        }
    }
}
