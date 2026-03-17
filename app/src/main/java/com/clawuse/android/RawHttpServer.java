package com.clawuse.android;

import android.util.Log;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal raw socket HTTP server. No NanoHTTPD, no thread pool limits,
 * no multipart parsing overhead. Each request gets its own thread.
 *
 * Inspired by a11y-bridge's 40-line server — extended with:
 * - POST body reading
 * - Query parameter parsing
 * - CORS headers
 * - Proper error handling
 * - Self-healing (server monitor)
 */
public abstract class RawHttpServer {
    private static final String TAG = "RawHttp";

    private final String bindAddress;
    private final int port;
    private volatile ServerSocket serverSocket;
    private volatile Thread serverThread;
    private volatile boolean running = false;

    public RawHttpServer(String bindAddress, int port) {
        this.bindAddress = bindAddress;
        this.port = port;
    }

    /** Override to handle requests. Return a Response. */
    protected abstract Response serve(String method, String path,
                                       Map<String, String> params, String body,
                                       Map<String, String> headers, InputStream rawInput);

    // ── Lifecycle ────────────────────────────────────────────────

    public void start() throws IOException {
        if (running) return;
        running = true;
        InetAddress addr = "0.0.0.0".equals(bindAddress) ? null : InetAddress.getByName(bindAddress);
        serverSocket = new ServerSocket(port, 50, addr);
        serverThread = new Thread(() -> {
            Log.i(TAG, "HTTP server listening on " + bindAddress + ":" + port);
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    client.setSoTimeout(60_000); // per-connection read timeout
                    new Thread(() -> handleClient(client), "http-req").start();
                } catch (IOException e) {
                    if (running) Log.e(TAG, "Accept error", e);
                }
            }
        }, "http-server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
    }

    public boolean isAlive() {
        return running && serverSocket != null && !serverSocket.isClosed();
    }

    // ── Request handling ─────────────────────────────────────────

    private void handleClient(Socket client) {
        try {
            InputStream rawInput = client.getInputStream();
            BufferedInputStream bis = new BufferedInputStream(rawInput);
            OutputStream out = client.getOutputStream();

            // Read request line
            String requestLine = readLine(bis);
            if (requestLine == null || requestLine.isEmpty()) {
                client.close();
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                client.close();
                return;
            }
            String method = parts[0];
            String fullPath = parts[1];

            // Parse path and query params
            String path;
            Map<String, String> params = new HashMap<>();
            int qIdx = fullPath.indexOf('?');
            if (qIdx >= 0) {
                path = fullPath.substring(0, qIdx);
                parseQueryParams(fullPath.substring(qIdx + 1), params);
            } else {
                path = fullPath;
            }

            // Read headers
            Map<String, String> headers = new HashMap<>();
            int contentLength = 0;
            String line;
            while ((line = readLine(bis)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String key = line.substring(0, colon).trim().toLowerCase();
                    String val = line.substring(colon + 1).trim();
                    headers.put(key, val);
                    if ("content-length".equals(key)) {
                        try { contentLength = Integer.parseInt(val); } catch (NumberFormatException ignored) {}
                    }
                }
            }

            // Read body
            String body = "";
            if (contentLength > 0 && !"GET".equals(method)) {
                byte[] buf = new byte[contentLength];
                int totalRead = 0;
                while (totalRead < contentLength) {
                    int n = bis.read(buf, totalRead, contentLength - totalRead);
                    if (n == -1) break;
                    totalRead += n;
                }
                body = new String(buf, 0, totalRead, "UTF-8");
            }

            // Handle CORS preflight
            Response response;
            if ("OPTIONS".equals(method)) {
                response = new Response(200, "");
            } else {
                try {
                    response = serve(method, path, params, body, headers, bis);
                } catch (Exception e) {
                    Log.e(TAG, "Handler error for " + method + " " + path, e);
                    response = new Response(500,
                            "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                }
            }

            if (response == null) {
                response = new Response(500, "{\"error\":\"null response\"}");
            }

            // Write response
            byte[] respBytes = response.body.getBytes("UTF-8");
            String contentType = response.contentType != null ? response.contentType : "application/json; charset=utf-8";
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 ").append(response.status).append(" OK\r\n");
            sb.append("Content-Type: ").append(contentType).append("\r\n");
            sb.append("Content-Length: ").append(respBytes.length).append("\r\n");
            sb.append("Access-Control-Allow-Origin: *\r\n");
            sb.append("Access-Control-Allow-Headers: X-Bridge-Token, X-Agent-Name, X-Session-Id, Content-Type\r\n");
            sb.append("Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\n");
            sb.append("Connection: close\r\n");
            sb.append("\r\n");
            out.write(sb.toString().getBytes("UTF-8"));
            out.write(respBytes);
            out.flush();

        } catch (Exception e) {
            // Client disconnected or other I/O error — normal, don't spam logs
        } finally {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    /** Read a line terminated by \r\n from a stream. */
    private String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(128);
        int prev = -1;
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n' && prev == '\r') {
                sb.setLength(sb.length() - 1); // remove trailing \r
                return sb.toString();
            }
            sb.append((char) c);
            prev = c;
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private void parseQueryParams(String query, Map<String, String> params) {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            try {
                if (eq >= 0) {
                    params.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                               URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
                } else {
                    params.put(URLDecoder.decode(pair, "UTF-8"), "");
                }
            } catch (UnsupportedEncodingException ignored) {}
        }
    }

    static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    // ── Response ─────────────────────────────────────────────────

    public static class Response {
        public final int status;
        public final String body;
        public final String contentType;

        public Response(int status, String body) {
            this(status, body, "application/json; charset=utf-8");
        }

        public Response(int status, String body, String contentType) {
            this.status = status;
            this.body = body;
            this.contentType = contentType;
        }
    }
}
