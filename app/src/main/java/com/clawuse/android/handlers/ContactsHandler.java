package com.clawuse.android.handlers;

import android.content.Context;
import android.database.Cursor;
import android.provider.ContactsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

/**
 * GET /contacts — list contacts
 * GET /contacts?search=xxx&limit=50
 */
public class ContactsHandler implements RouteHandler {
    private final Context context;

    public ContactsHandler(Context context) {
        this.context = context;
    }

    @Override
    public String handle(String method, String path, Map<String, String> params, String body) throws Exception {
        String search = params.getOrDefault("search", "");
        int limit = 50;
        try { limit = Integer.parseInt(params.getOrDefault("limit", "50")); } catch (Exception ignored) {}
        if (limit > 500) limit = 500;

        JSONArray contacts = new JSONArray();
        String selection = null;
        String[] selectionArgs = null;

        if (!search.isEmpty()) {
            selection = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?";
            selectionArgs = new String[]{"%" + search + "%"};
        }

        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER,
                            ContactsContract.CommonDataKinds.Phone.TYPE
                    },
                    selection,
                    selectionArgs,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            );

            if (cursor != null) {
                int count = 0;
                while (cursor.moveToNext() && count < limit) {
                    JSONObject c = new JSONObject();
                    c.put("name", cursor.getString(0));
                    c.put("phone", cursor.getString(1));
                    int type = cursor.getInt(2);
                    c.put("type", ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                            context.getResources(), type, "").toString());
                    contacts.put(c);
                    count++;
                }
            }
        } catch (SecurityException e) {
            return "{\"error\":\"contacts permission denied\"}";
        } finally {
            if (cursor != null) cursor.close();
        }

        JSONObject json = new JSONObject();
        json.put("contacts", contacts);
        json.put("count", contacts.length());
        return json.toString();
    }
}
