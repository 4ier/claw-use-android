package com.clawuse.android.handlers;

import java.util.Map;

/**
 * Interface for all HTTP endpoint handlers.
 * Each handler processes requests for a URL prefix and returns JSON response.
 */
public interface RouteHandler {
    /**
     * Handle an HTTP request.
     * @param method HTTP method (GET, POST, etc.)
     * @param path Full request path (e.g. /screen?compact)
     * @param params Query parameters
     * @param body Request body (for POST)
     * @return JSON response string
     */
    String handle(String method, String path, Map<String, String> params, String body) throws Exception;
}
