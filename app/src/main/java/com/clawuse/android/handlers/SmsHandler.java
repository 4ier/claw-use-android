package com.clawuse.android.handlers;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

/**
 * GET /sms — read SMS messages
 * POST /sms — send SMS
 */
public class SmsHandler implements RouteHandler {
    private final Context context;

    public SmsHandler(Context context) {
        this.context = context;
    }

    @Override
    public String handle(String method, String path, Map<String, String> params, String body) throws Exception {
        if ("POST".equals(method)) {
            return sendSms(body);
        }
        return readSms(params);
    }

    private String readSms(Map<String, String> params) throws Exception {
        int limit = 20;
        try { limit = Integer.parseInt(params.getOrDefault("limit", "20")); } catch (Exception ignored) {}
        if (limit > 200) limit = 200;

        String filter = params.getOrDefault("filter", ""); // "inbox", "sent", or "" for all

        Uri uri = Uri.parse("content://sms");
        if ("inbox".equals(filter)) uri = Uri.parse("content://sms/inbox");
        else if ("sent".equals(filter)) uri = Uri.parse("content://sms/sent");

        JSONArray messages = new JSONArray();
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    uri,
                    new String[]{"address", "body", "date", "type", "read"},
                    null, null,
                    "date DESC"
            );

            if (cursor != null) {
                int count = 0;
                while (cursor.moveToNext() && count < limit) {
                    JSONObject msg = new JSONObject();
                    msg.put("address", cursor.getString(0));
                    msg.put("body", cursor.getString(1));
                    msg.put("date", cursor.getLong(2));
                    int type = cursor.getInt(3);
                    msg.put("type", type == 1 ? "inbox" : type == 2 ? "sent" : "other");
                    msg.put("read", cursor.getInt(4) == 1);
                    messages.put(msg);
                    count++;
                }
            }
        } catch (SecurityException e) {
            return "{\"error\":\"SMS permission denied\"}";
        } finally {
            if (cursor != null) cursor.close();
        }

        JSONObject json = new JSONObject();
        json.put("messages", messages);
        json.put("count", messages.length());
        return json.toString();
    }

    private String sendSms(String body) throws Exception {
        JSONObject req = body != null && !body.isEmpty() ? new JSONObject(body) : new JSONObject();
        String to = req.optString("to", "");
        String message = req.optString("message", "");

        if (to.isEmpty()) return "{\"error\":\"'to' phone number required\"}";
        if (message.isEmpty()) return "{\"error\":\"'message' text required\"}";

        try {
            SmsManager sms = SmsManager.getDefault();
            if (message.length() > 160) {
                java.util.ArrayList<String> parts = sms.divideMessage(message);
                sms.sendMultipartTextMessage(to, null, parts, null, null);
            } else {
                sms.sendTextMessage(to, null, message, null, null);
            }
            return new JSONObject().put("sent", true).put("to", to).toString();
        } catch (SecurityException e) {
            return "{\"error\":\"SMS send permission denied\"}";
        } catch (Exception e) {
            return "{\"error\":\"SMS send failed: " + e.getMessage() + "\"}";
        }
    }
}
