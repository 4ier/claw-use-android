package com.clawuse.android;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import org.json.JSONObject;

/**
 * Encrypted storage for sensitive config (PIN, etc).
 * Uses AndroidX EncryptedSharedPreferences with AES256.
 */
public class ConfigStore {
    private static final String PREFS_NAME = "claw_use_config_encrypted";
    private static final String KEY_PIN = "device_pin";
    private static final String KEY_SCREEN_TIMEOUT = "screen_timeout_sec";

    private static volatile ConfigStore instance;
    private SharedPreferences prefs;

    public static ConfigStore get(Context context) {
        if (instance == null) {
            synchronized (ConfigStore.class) {
                if (instance == null) {
                    instance = new ConfigStore(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private ConfigStore(Context context) {
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            prefs = EncryptedSharedPreferences.create(
                    PREFS_NAME,
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            // Fallback to regular SharedPreferences if encryption fails
            prefs = context.getSharedPreferences(PREFS_NAME + "_fallback", Context.MODE_PRIVATE);
        }
    }

    public void setPin(String pin) {
        prefs.edit().putString(KEY_PIN, pin).apply();
    }

    public String getPin() {
        return prefs.getString(KEY_PIN, null);
    }

    public boolean hasPin() {
        return getPin() != null && !getPin().isEmpty();
    }

    public void clearPin() {
        prefs.edit().remove(KEY_PIN).apply();
    }

    public void setScreenTimeout(int seconds) {
        prefs.edit().putInt(KEY_SCREEN_TIMEOUT, seconds).apply();
    }

    public int getScreenTimeout() {
        return prefs.getInt(KEY_SCREEN_TIMEOUT, 30);
    }

    public JSONObject toJson() {
        try {
            JSONObject j = new JSONObject();
            j.put("hasPin", hasPin());
            j.put("screenTimeout", getScreenTimeout());
            return j;
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
