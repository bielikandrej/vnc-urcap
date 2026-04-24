/*
 * STIMBA VNC Server URCap — Portal HTTPS client (v3.4.0+)
 *
 * Minimal HTTP client for talking to portal.stimba.sk /api/claim/device +
 * /api/agent/heartbeat + /api/claim/device/revoke. Uses HttpsURLConnection with
 * the system CA bundle (standard Java TLS). Cert pinning on LE intermediate
 * CA is scoped for v3.5.
 *
 * No 3rd-party deps (org.json, okhttp, etc.) — OSGi classpath hostility +
 * fatter bundle size. Hand-rolled JSON with tight enough escaping for our
 * payloads, which contain only serial numbers, version strings, and enum-
 * valued state flags. All input is validated upstream by Zod on the portal
 * side; worst case a malformed payload gets a 400 response.
 */
package sk.stimba.urcap.vnc.impl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.logging.Logger;

public final class PortalClient {

    private static final Logger LOG = Logger.getLogger(PortalClient.class.getName());
    public static final String DEFAULT_PORTAL_URL = "https://portal.stimba.sk";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS    = 15_000;
    public static final String URCAP_VERSION    = "stimba-vnc-urcap/3.12.2";

    private final String portalUrl;

    public PortalClient(String portalUrl) {
        this.portalUrl = (portalUrl == null || portalUrl.isEmpty())
                ? DEFAULT_PORTAL_URL : portalUrl;
    }

    // --- DTOs -----------------------------------------------------------------

    public static final class ClaimResponse {
        public final String deviceId;
        public final String token;
        public final int heartbeatIntervalS;
        public final boolean reused;
        ClaimResponse(String deviceId, String token, int hb, boolean reused) {
            this.deviceId = deviceId;
            this.token = token;
            this.heartbeatIntervalS = hb;
            this.reused = reused;
        }
    }

    public static final class QueuedCommand {
        public final String id;
        public final String toolName;
        public final String argsJson;
        public QueuedCommand(String id, String toolName, String argsJson) {
            this.id = id; this.toolName = toolName; this.argsJson = argsJson;
        }
    }

    public static final class ClaimError extends RuntimeException {
        public final int httpStatus;
        public final String errorCode;
        ClaimError(int status, String code, String message) {
            super(message == null ? ("HTTP " + status + " " + code) : message);
            this.httpStatus = status;
            this.errorCode = code == null ? "unknown" : code;
        }
    }

    // --- API --------------------------------------------------------------

    /**
     * Exchanges a pairing code for a device id + token.
     * @param code  STB-XXXX-XXXX claim code typed by operator
     * @param robotInfo  { "robot_serial": "...", "model": "...", "polyscope_version": "...",
     *                     "polyscope_major": "ps5"|"psx"|"cb3", "lan_ip": "...",
     *                     "hostname": "..." }  — any subset, all optional except we always
     *                    add urcap_version
     */
    public ClaimResponse claim(String code, Map<String, String> robotInfo) throws IOException {
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("urcap_version", URCAP_VERSION);
        if (robotInfo != null) {
            for (Map.Entry<String, String> e : robotInfo.entrySet()) {
                if (e.getValue() != null && !e.getValue().isEmpty()) {
                    body.put(e.getKey(), e.getValue());
                }
            }
        }

        HttpResult r = post("/api/claim/device", null, null, toJson(body));
        if (r.code == 200) {
            String deviceId = extractString(r.body, "device_id");
            String token    = extractString(r.body, "token");
            String hbStr    = extractNumber(r.body, "heartbeat_interval_s");
            int hb = hbStr == null ? 30 : Integer.parseInt(hbStr);
            boolean reused  = "true".equals(extractLiteral(r.body, "reused"));
            return new ClaimResponse(deviceId, token, hb, reused);
        }
        String errCode = extractString(r.body, "error");
        throw new ClaimError(r.code, errCode, "claim failed: HTTP " + r.code
                + (errCode != null ? " " + errCode : "") + " body=" + r.body);
    }

    /**
     * Posts a heartbeat. Returns true on 204, false on any non-2xx. Does NOT throw —
     * heartbeat loop must be resilient.
     */
    public boolean heartbeat(String token, String deviceId, String bodyJson) {
        try {
            HttpResult r = post("/api/agent/heartbeat", token, deviceId, bodyJson);
            return r.code >= 200 && r.code < 300;
        } catch (IOException e) {
            LOG.warning("heartbeat failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Self-revokes token (unpair flow).
     */
    public boolean unpair(String token, String deviceId) {
        try {
            HttpResult r = post("/api/claim/device/revoke", token, deviceId, "{}");
            return r.code == 200;
        } catch (IOException e) {
            LOG.warning("unpair failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * v3.5.0 — Poll queued AI commands for this device. Atomic claim on the
     * portal side (UPDATE … RETURNING * so concurrent pollers don't double-dispatch).
     *
     * v3.10.0 — supports long-poll via `waitSeconds > 0` (portal holds connection
     * up to waitSeconds and returns immediately when a command arrives). This
     * drops effective command latency from ~5 s (poll interval) to <500 ms.
     */
    public java.util.List<QueuedCommand> pollCommands(String token, String deviceId) {
        return pollCommands(token, deviceId, 0);
    }

    public java.util.List<QueuedCommand> pollCommands(String token, String deviceId, int waitSeconds) {
        java.util.List<QueuedCommand> out = new java.util.ArrayList<>();
        try {
            String path = waitSeconds > 0
                    ? "/api/agent/commands?wait=" + waitSeconds
                    : "/api/agent/commands";
            // Use extended timeout for long-poll (waitSeconds + 5 s slack)
            HttpResult r = get(path, token, deviceId,
                    waitSeconds > 0 ? (waitSeconds + 5) * 1000 : 0);
            if (r.code != 200) return out;
            // Response shape: { device_id, polled_at, commands: [ { id, tool, args: {...} } ] }
            // v3.10: portal returns `tool` (not `tool_name`). Accept both for compat.
            String body = r.body;
            int idxList = body.indexOf("\"commands\"");
            if (idxList < 0) return out;
            int arrStart = body.indexOf('[', idxList);
            int arrEnd   = findMatchingBracket(body, arrStart);
            if (arrStart < 0 || arrEnd < 0) return out;
            String arr = body.substring(arrStart + 1, arrEnd);
            java.util.List<String> items = splitTopLevelObjects(arr);
            for (String item : items) {
                String id   = extractString(item, "id");
                String tool = extractString(item, "tool");          // v3.10 canonical
                if (tool == null) tool = extractString(item, "tool_name"); // legacy fallback
                String argsJson = extractRawObject(item, "args");
                if (id != null && tool != null) {
                    out.add(new QueuedCommand(id, tool, argsJson == null ? "{}" : argsJson));
                }
            }
        } catch (Throwable t) {
            LOG.warning("pollCommands failed: " + t.getMessage());
        }
        return out;
    }

    /**
     * v3.5.0 — PATCH command with completion status. ok → "completed", else "failed".
     */
    public boolean ackCommand(String token, String deviceId, String commandId,
                              boolean ok, String result, String error) {
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("id", commandId);
        body.put("status", ok ? "completed" : "failed");
        if (result != null) body.put("result", result);
        if (error != null)  body.put("error", error);
        try {
            HttpResult r = patch("/api/agent/commands", token, deviceId, toJson(body));
            return r.code >= 200 && r.code < 300;
        } catch (IOException e) {
            LOG.warning("ackCommand failed: " + e.getMessage());
            return false;
        }
    }

    // --- HTTP helpers ---------------------------------------------------------

    private static final class HttpResult {
        int code;
        String body;
        HttpResult(int code, String body) { this.code = code; this.body = body; }
    }

    private HttpResult post(String path, String bearerToken, String deviceIdHeader, String bodyJson) throws IOException {
        return exchange("POST", path, bearerToken, deviceIdHeader, bodyJson);
    }

    private HttpResult patch(String path, String bearerToken, String deviceIdHeader, String bodyJson) throws IOException {
        return exchange("PATCH", path, bearerToken, deviceIdHeader, bodyJson);
    }

    private HttpResult get(String path, String bearerToken, String deviceIdHeader) throws IOException {
        return exchange("GET", path, bearerToken, deviceIdHeader, null, 0);
    }

    // v3.10 — overload accepting explicit read-timeout override (for long-poll)
    private HttpResult get(String path, String bearerToken, String deviceIdHeader, int readTimeoutMsOverride) throws IOException {
        return exchange("GET", path, bearerToken, deviceIdHeader, null, readTimeoutMsOverride);
    }

    private HttpResult exchange(String method, String path, String bearerToken,
                                String deviceIdHeader, String bodyJson) throws IOException {
        return exchange(method, path, bearerToken, deviceIdHeader, bodyJson, 0);
    }

    private HttpResult exchange(String method, String path, String bearerToken,
                                String deviceIdHeader, String bodyJson,
                                int readTimeoutMsOverride) throws IOException {
        URL url = new URL(this.portalUrl + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(readTimeoutMsOverride > 0 ? readTimeoutMsOverride : READ_TIMEOUT_MS);
            if (bodyJson != null) conn.setDoOutput(true);
            try {
                conn.setRequestMethod(method);
            } catch (java.net.ProtocolException pe) {
                // PATCH on older JDKs requires reflection tricks; our build target is
                // modern enough that this should never fire. Fall back to X-HTTP-Method-Override.
                conn.setRequestMethod("POST");
                conn.setRequestProperty("X-HTTP-Method-Override", method);
            }
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", URCAP_VERSION);
            if (bearerToken != null) {
                conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
            }
            if (deviceIdHeader != null) {
                conn.setRequestProperty("X-Stimba-Device-Id", deviceIdHeader);
            }

            // v3.5 — apply cert pinning if configured
            if (conn instanceof javax.net.ssl.HttpsURLConnection) {
                CertPinner.apply((javax.net.ssl.HttpsURLConnection) conn);
            }

            if (bodyJson != null) {
                byte[] bytes = bodyJson.getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                }
            }
            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            String body = (in == null) ? "" : readAll(in);
            return new HttpResult(code, body);
        } finally {
            conn.disconnect();
        }
    }

    // --- Minimal JSON scanning helpers for pollCommands -----------------------

    static int findMatchingBracket(String s, int openIdx) {
        if (openIdx < 0 || openIdx >= s.length() || s.charAt(openIdx) != '[') return -1;
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) { esc = false; continue; }
            if (c == '\\') { esc = true; continue; }
            if (c == '"') { inStr = !inStr; continue; }
            if (inStr) continue;
            if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    static java.util.List<String> splitTopLevelObjects(String arr) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        int start = -1;
        for (int i = 0; i < arr.length(); i++) {
            char c = arr.charAt(i);
            if (esc) { esc = false; continue; }
            if (c == '\\') { esc = true; continue; }
            if (c == '"') { inStr = !inStr; continue; }
            if (inStr) continue;
            if (c == '{') { if (depth == 0) start = i; depth++; }
            else if (c == '}') { depth--; if (depth == 0 && start >= 0) {
                out.add(arr.substring(start, i + 1));
                start = -1;
            }}
        }
        return out;
    }

    static String extractRawObject(String json, String key) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return null;
        int braceStart = -1;
        for (int i = colon + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') continue;
            if (c == '{') { braceStart = i; break; }
            return null;
        }
        if (braceStart < 0) return null;
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int i = braceStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (esc) { esc = false; continue; }
            if (c == '\\') { esc = true; continue; }
            if (c == '"') { inStr = !inStr; continue; }
            if (inStr) continue;
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return json.substring(braceStart, i + 1); }
        }
        return null;
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[1024];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    // --- Minimal JSON serialization -------------------------------------------

    static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":");
            appendValue(sb, e.getValue());
        }
        sb.append("}");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendValue(StringBuilder sb, Object v) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof Boolean || v instanceof Integer || v instanceof Long) {
            sb.append(v);
            return;
        }
        if (v instanceof Number) {
            // Handle NaN/Infinity — JSON has no representation, emit null.
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) { sb.append("null"); return; }
            sb.append(((Number) v).toString());
            return;
        }
        if (v instanceof Map) {
            sb.append(toJson((Map<String, Object>) v));
            return;
        }
        // v3.7.0 — arrays + iterables (for RTDE telemetry in heartbeat payload)
        if (v instanceof double[]) {
            double[] arr = (double[]) v;
            sb.append('[');
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(',');
                if (Double.isNaN(arr[i]) || Double.isInfinite(arr[i])) sb.append("null");
                else sb.append(arr[i]);
            }
            sb.append(']');
            return;
        }
        if (v instanceof int[]) {
            int[] arr = (int[]) v;
            sb.append('[');
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(arr[i]);
            }
            sb.append(']');
            return;
        }
        if (v instanceof long[]) {
            long[] arr = (long[]) v;
            sb.append('[');
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(arr[i]);
            }
            sb.append(']');
            return;
        }
        if (v instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object e : (Iterable<?>) v) {
                if (!first) sb.append(',');
                first = false;
                appendValue(sb, e);
            }
            sb.append(']');
            return;
        }
        // Default: string
        sb.append('"').append(escape(v.toString())).append('"');
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    // --- Minimal JSON field extractor ----------------------------------------
    // Hand-rolled because the URCap classpath doesn't guarantee org.json. For
    // our use (flat, server-produced JSON with known keys) it's enough to grab
    // the first occurrence of "key":"value" / "key":123 / "key":true.

    static String extractString(String json, String key) {
        int idx = findKey(json, key);
        if (idx < 0) return null;
        int q = json.indexOf('"', idx);
        if (q < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = q + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(++i);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    default:  sb.append(n); break;
                }
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return null;
    }

    static String extractNumber(String json, String key) {
        int idx = findKey(json, key);
        if (idx < 0) return null;
        StringBuilder sb = new StringBuilder();
        boolean seen = false;
        for (int i = idx; i < json.length(); i++) {
            char c = json.charAt(i);
            if ((c >= '0' && c <= '9') || c == '-' || c == '.') {
                sb.append(c);
                seen = true;
            } else if (seen) {
                break;
            }
        }
        return seen ? sb.toString() : null;
    }

    static String extractLiteral(String json, String key) {
        int idx = findKey(json, key);
        if (idx < 0) return null;
        String rest = json.substring(idx).trim();
        if (rest.startsWith("true")) return "true";
        if (rest.startsWith("false")) return "false";
        if (rest.startsWith("null")) return "null";
        return null;
    }

    private static int findKey(String json, String key) {
        String needle = "\"" + key + "\"";
        int k = json.indexOf(needle);
        if (k < 0) return -1;
        int colon = json.indexOf(':', k + needle.length());
        return (colon < 0) ? -1 : colon + 1;
    }
}
