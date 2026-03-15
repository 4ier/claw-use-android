package com.clawuse.android.handlers;

import java.util.Map;

/**
 * Stub handler for Phase 2 endpoints. Returns 501 Not Implemented.
 */
public class StubHandler implements RouteHandler {
    private final String category;

    public StubHandler(String category) {
        this.category = category;
    }

    @Override
    public String handle(String method, String path, Map<String, String> params, String body) {
        return "{\"error\":\"not implemented\",\"category\":\"" + category + "\",\"phase\":2,\"path\":\"" + path + "\"}";
    }
}
