package com.clawuse.android.auth;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;

/**
 * Manages the authentication token for the HTTP bridge.
 * Token is a UUID v4 stored in SharedPreferences, generated on first launch.
 */
public class TokenManager {
    private static final String PREFS_NAME = "claw_use_auth";
    private static final String KEY_TOKEN = "bridge_token";
    private final SharedPreferences prefs;

    public TokenManager(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (getToken() == null) {
            regenerateToken();
        }
    }

    public String getToken() {
        return prefs.getString(KEY_TOKEN, null);
    }

    public String regenerateToken() {
        String token = UUID.randomUUID().toString();
        prefs.edit().putString(KEY_TOKEN, token).apply();
        return token;
    }

    public boolean validate(String token) {
        if (token == null) return false;
        String stored = getToken();
        return stored != null && stored.equals(token);
    }
}
