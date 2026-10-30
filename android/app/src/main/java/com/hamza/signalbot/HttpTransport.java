package com.hamza.signalbot;

import java.io.IOException;
import java.util.Map;

/**
 * Synchronous HTTP transport abstraction.
 * v2.0 tries Cronet (real Chrome TLS fingerprint = max stealth) first,
 * then falls back to OkHttp with DNS-over-HTTPS (fixes ISP DNS blocking).
 */
public interface HttpTransport {
    /** Human-readable transport name for logs. */
    String name();

    /** GET request, returns response body as string. Throws on non-2xx. */
    String get(String url, Map<String, String> headers) throws IOException;

    /** POST with plain-text body, returns response body. Throws on non-2xx. */
    String post(String url, String body, Map<String, String> headers) throws IOException;

    /** Release native resources (Cronet engine shutdown, connection pools). */
    void shutdown();
}
