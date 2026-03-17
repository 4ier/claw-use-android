package com.clawuse.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.Map;

/**
 * Manages trusted agent list in EncryptedSharedPreferences.
 * Key: SHA-256(token) + "|" + agentName → "trusted"
 */
public class TrustStore {
    private static final String TAG = "TrustStore";
    private static final String PREFS_NAME = "claw_use_trust_encrypted";
    private static final String VALUE_TRUSTED = "trusted";

    private static volatile TrustStore instance;
    private SharedPreferences prefs;

    public static TrustStore get(Context context) {
        if (instance == null) {
            synchronized (TrustStore.class) {
                if (instance == null) {
                    instance = new TrustStore(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private TrustStore(Context context) {
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
            Log.e(TAG, "Failed to create encrypted prefs, using fallback", e);
            prefs = context.getSharedPreferences(PREFS_NAME + "_fallback", Context.MODE_PRIVATE);
        }
    }

    public boolean isTrusted(String token, String agentName) {
        String key = makeKey(token, agentName);
        return VALUE_TRUSTED.equals(prefs.getString(key, null));
    }

    public void trust(String token, String agentName) {
        String key = makeKey(token, agentName);
        prefs.edit().putString(key, VALUE_TRUSTED).apply();
        Log.i(TAG, "Trusted agent: " + agentName);
    }

    public void revoke(String token, String agentName) {
        String key = makeKey(token, agentName);
        prefs.edit().remove(key).apply();
        Log.i(TAG, "Revoked agent: " + agentName);
    }

    public JSONArray listTrusted() {
        JSONArray arr = new JSONArray();
        try {
            Map<String, ?> all = prefs.getAll();
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                if (VALUE_TRUSTED.equals(entry.getValue())) {
                    String key = entry.getKey();
                    int sep = key.indexOf('|');
                    if (sep > 0 && sep < key.length() - 1) {
                        JSONObject obj = new JSONObject();
                        obj.put("tokenHash", key.substring(0, sep));
                        obj.put("agentName", key.substring(sep + 1));
                        arr.put(obj);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to list trusted agents", e);
        }
        return arr;
    }

    private String makeKey(String token, String agentName) {
        return hashToken(token) + "|" + (agentName != null ? agentName : "Unknown Agent");
    }

    private String hashToken(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(token.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) { // first 8 bytes = 16 hex chars (enough for key)
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(token.hashCode());
        }
    }
}
