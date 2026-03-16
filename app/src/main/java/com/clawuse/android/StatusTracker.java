package com.clawuse.android;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Global metrics singleton. Thread-safe.
 * Tracks uptime, request count, a11y health, etc.
 */
public class StatusTracker {
    private static final StatusTracker INSTANCE = new StatusTracker();

    private final long startTimeMs = System.currentTimeMillis();
    private final AtomicLong requestCount = new AtomicLong(0);
    private final AtomicLong lastRequestTimeMs = new AtomicLong(0);
    private volatile String lastRequestPath = "";
    private final AtomicBoolean a11yAlive = new AtomicBoolean(false);
    private final AtomicLong a11yLastCheckMs = new AtomicLong(0);
    private final AtomicLong a11yLatencyMs = new AtomicLong(-1);
    private volatile int consecutiveA11yFailures = 0;

    public static StatusTracker get() {
        return INSTANCE;
    }

    public void recordRequest(String path) {
        requestCount.incrementAndGet();
        lastRequestTimeMs.set(System.currentTimeMillis());
        lastRequestPath = path;
    }

    public void recordA11yCheck(boolean alive, long latencyMs) {
        a11yAlive.set(alive);
        a11yLastCheckMs.set(System.currentTimeMillis());
        a11yLatencyMs.set(latencyMs);
        if (alive) {
            consecutiveA11yFailures = 0;
        } else {
            consecutiveA11yFailures++;
        }
    }

    public boolean isA11yAlive() { return a11yAlive.get(); }
    public int getConsecutiveA11yFailures() { return consecutiveA11yFailures; }
    public long getRequestCount() { return requestCount.get(); }
    public long getUptimeSeconds() { return (System.currentTimeMillis() - startTimeMs) / 1000; }

    public JSONObject toJson() {
        try {
            long now = System.currentTimeMillis();
            JSONObject j = new JSONObject();
            j.put("uptimeSeconds", (now - startTimeMs) / 1000);
            j.put("requests", requestCount.get());

            JSONObject last = new JSONObject();
            long lastReq = lastRequestTimeMs.get();
            last.put("path", lastRequestPath);
            last.put("timeAgoSeconds", lastReq > 0 ? (now - lastReq) / 1000 : -1);
            j.put("lastRequest", last);

            JSONObject a11y = new JSONObject();
            a11y.put("alive", a11yAlive.get());
            a11y.put("latencyMs", a11yLatencyMs.get());
            a11y.put("consecutiveFailures", consecutiveA11yFailures);
            j.put("accessibility", a11y);

            return j;
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
