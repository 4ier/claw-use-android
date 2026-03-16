package com.clawuse.android.handlers;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Map;

/**
 * GET /file?path=... — read file (base64)
 * POST /file — write file
 * DELETE /file?path=... — delete file
 * GET /file/list?path=... — list directory
 */
public class FileHandler implements RouteHandler {
    private final Context context;
    private static final long MAX_READ_SIZE = 10 * 1024 * 1024; // 10MB

    public FileHandler(Context context) {
        this.context = context;
    }

    @Override
    public String handle(String method, String path, Map<String, String> params, String body) throws Exception {
        // Strip /file prefix to get sub-path
        String subPath = path;
        if (subPath.startsWith("/file/list") || subPath.startsWith("/a11y/file/list")) {
            return listDirectory(params);
        }
        if ("DELETE".equals(method)) {
            return deleteFile(params);
        }
        if ("POST".equals(method)) {
            return writeFile(body);
        }
        return readFile(params);
    }

    private String readFile(Map<String, String> params) throws Exception {
        String filePath = params.get("path");
        if (filePath == null || filePath.isEmpty()) return "{\"error\":\"'path' param required\"}";

        File file = new File(filePath);
        if (!file.exists()) return "{\"error\":\"file not found\"}";
        if (!file.isFile()) return "{\"error\":\"not a file\"}";
        if (file.length() > MAX_READ_SIZE) return "{\"error\":\"file too large (max 10MB)\"}";

        byte[] data = new byte[(int) file.length()];
        try (FileInputStream fis = new FileInputStream(file)) {
            fis.read(data);
        }

        JSONObject json = new JSONObject();
        json.put("content", Base64.encodeToString(data, Base64.NO_WRAP));
        json.put("name", file.getName());
        json.put("size", file.length());
        json.put("lastModified", file.lastModified());
        // Try to detect if it's text
        boolean isText = isLikelyText(file.getName());
        if (isText && data.length < 1024 * 1024) {
            json.put("text", new String(data, "UTF-8"));
        }
        return json.toString();
    }

    private String writeFile(String body) throws Exception {
        JSONObject req = body != null && !body.isEmpty() ? new JSONObject(body) : new JSONObject();
        String filePath = req.optString("path", "");
        if (filePath.isEmpty()) return "{\"error\":\"'path' required\"}";

        File file = new File(filePath);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        String encoding = req.optString("encoding", "text");
        byte[] data;
        if ("base64".equals(encoding)) {
            data = Base64.decode(req.optString("content", ""), Base64.DEFAULT);
        } else {
            data = req.optString("content", "").getBytes("UTF-8");
        }

        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
        }

        return new JSONObject()
                .put("written", true)
                .put("path", file.getAbsolutePath())
                .put("size", data.length)
                .toString();
    }

    private String deleteFile(Map<String, String> params) throws Exception {
        String filePath = params.get("path");
        if (filePath == null || filePath.isEmpty()) return "{\"error\":\"'path' param required\"}";

        File file = new File(filePath);
        if (!file.exists()) return "{\"error\":\"file not found\"}";

        boolean deleted = file.delete();
        return new JSONObject().put("deleted", deleted).toString();
    }

    private String listDirectory(Map<String, String> params) throws Exception {
        String dirPath = params.getOrDefault("path", "/sdcard");
        File dir = new File(dirPath);
        if (!dir.exists()) return "{\"error\":\"directory not found\"}";
        if (!dir.isDirectory()) return "{\"error\":\"not a directory\"}";

        File[] files = dir.listFiles();
        JSONArray list = new JSONArray();
        if (files != null) {
            for (File f : files) {
                JSONObject entry = new JSONObject();
                entry.put("name", f.getName());
                entry.put("size", f.isFile() ? f.length() : 0);
                entry.put("isDir", f.isDirectory());
                entry.put("lastModified", f.lastModified());
                list.put(entry);
            }
        }

        return new JSONObject()
                .put("path", dir.getAbsolutePath())
                .put("files", list)
                .put("count", list.length())
                .toString();
    }

    private boolean isLikelyText(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".txt") || lower.endsWith(".json") || lower.endsWith(".xml")
                || lower.endsWith(".csv") || lower.endsWith(".md") || lower.endsWith(".log")
                || lower.endsWith(".html") || lower.endsWith(".js") || lower.endsWith(".py")
                || lower.endsWith(".sh") || lower.endsWith(".yml") || lower.endsWith(".yaml")
                || lower.endsWith(".conf") || lower.endsWith(".cfg") || lower.endsWith(".ini");
    }
}
