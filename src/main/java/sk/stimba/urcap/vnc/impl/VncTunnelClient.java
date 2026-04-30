/*
 * STIMBA VNC Server URCap — VNC Relay tunnel client (v3.12.0)
 * Copyright (c) 2026 STIMBA, s. r. o.
 *
 * Reverse-tunnels VNC (RFB) bytes between the STIMBA VNC Relay on Fly.io and
 * the local x11vnc daemon at 127.0.0.1:$VNC_PORT. Outbound-only WSS: the URCap
 * initiates the connection (same direction as /api/agent/heartbeat), so NAT /
 * IXrouter firewalls need no port forward or inbound allow. Replaces the IXON
 * VNC path that broke for many customers after Polyscope 5.20+ updates.
 *
 * Why hand-rolled WebSocket (RFC 6455):
 * - javax.websocket / Tyrus is NOT exported by the Polyscope OSGi runtime.
 * - We deliberately do not embed 3rd-party JARs (see pom.xml — matches the
 *   PortalClient / TokenCrypto pattern).
 * - A client-only WS implementation is ~250 LoC and avoids every classloader
 *   issue we'd hit with an embedded library.
 *
 * Threading model:
 *   connector thread   (1) — owns session lifecycle, reconnect backoff
 *     |
 *     +-- ws-to-local pump (1) — reads WS frames from relay, writes to x11vnc
 *     +-- local-to-ws pump (1) — reads bytes from x11vnc, frames + writes WS
 *
 * Reconnect:
 *   Exponential backoff 1s -> 60s cap. Reset to 1s on clean session end
 *   (peer close, no error). Interrupted by stop().
 *
 * Close codes surfaced by the relay (see relay/src/sessions.ts):
 *   4409  — superseded (a newer agent connection replaced us) -> back off long
 *   4503  — agent disconnected / reconnected — will be us on re-attempt
 *   (other 4xxx from the relay just cause a normal reconnect)
 */
package sk.stimba.urcap.vnc.impl;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public final class VncTunnelClient {

    private static final Logger LOG = Logger.getLogger(VncTunnelClient.class.getName());

    /**
     * v3.12.14 — Override Java's default DNS caching at class load.
     *
     * Polyscope's JRE on UR e-Series ships with the Sun JVM defaults
     * `networkaddress.cache.ttl=-1` (positive results cached forever) and
     * `networkaddress.cache.negative.ttl=10`. The "forever positive" is the
     * killer: if the very first lookup of stimba-vnc-relay.fly.dev hits while
     * Polyscope's resolver is in a bad state (WiFi-firewalled DNS, dnsmasq
     * cache poisoning, /etc/resolv.conf race during first boot), the JVM
     * caches the resulting UnknownHostException and never re-queries — the
     * tunnel reports "UnknownHostException" forever even after the OS
     * resolver recovers.
     *
     * 30 s positive TTL + 0 s negative TTL means we re-query reasonably often
     * but never cache failures. Set via java.security.Security at class
     * load so it takes effect before InetAddress is touched the first time.
     * Re-set on every JVM restart; harmless if Polyscope's policy already
     * has these.
     */
    static {
        try {
            Security.setProperty("networkaddress.cache.ttl",          "30");
            Security.setProperty("networkaddress.cache.negative.ttl",  "0");
        } catch (Throwable t) {
            // Some Polyscope JREs run a SecurityManager that forbids this.
            // Falling back to default policy is fine — UnknownHostException
            // retry below still helps on the next iteration.
        }
    }

    /** RFC 6455 §1.3 handshake GUID. */
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final long MIN_BACKOFF_MS = 1_000L;
    /** v3.12.14 — DNS-failure backoff caps shorter than the generic MAX_BACKOFF_MS
     *  because UnknownHostException is usually a stale-cache or transient
     *  resolver issue that clears within seconds, not the multi-minute outage
     *  the generic exponential backoff was tuned for. */
    private static final long DNS_FAIL_BACKOFF_CAP_MS = 15_000L;
    private static final long MAX_BACKOFF_MS = 60_000L;
    private static final int  PUMP_BUFFER    = 16_384;
    private static final int  WS_OP_CONT     = 0x0;
    private static final int  WS_OP_BINARY   = 0x2;
    private static final int  WS_OP_CLOSE    = 0x8;
    private static final int  WS_OP_PING     = 0x9;
    private static final int  WS_OP_PONG     = 0xA;

    // ----- Tiny Java-8 functional interfaces (kept local so we don't rely on
    //       java.util.function which PortalHeartbeatRunner also avoids) -----
    public interface Supplier      { String get(); }
    public interface IntSupplier   { int  getAsInt(); }
    public interface BoolSupplier  { boolean getAsBoolean(); }
    public interface StatusSink    { void update(String msg); }

    private final Supplier     tokenSupplier;
    private final Supplier     deviceIdSupplier;
    private final Supplier     relayUrlSupplier;
    private final IntSupplier  localPortSupplier;
    private final BoolSupplier enabledSupplier;
    private final StatusSink   statusSink;
    private final SecureRandom rng = new SecureRandom();

    private final AtomicBoolean running   = new AtomicBoolean(false);
    private volatile boolean    connected = false;
    private volatile Thread     connectorThread;
    private volatile Socket     wsSocket;    // TCP/TLS to relay
    private volatile Socket     localSocket; // TCP to 127.0.0.1:5900

    // v3.12.11 — surface relay state to the UI / heartbeat. The connector loop
    // currently logs status changes only via java.util.logging which is invisible
    // to the operator on Polyscope. These four fields let VncInstallationNodeView
    // render a "Relay tunnel" row in Stav démona, and let PortalHeartbeatRunner
    // include the last failure reason in the heartbeat so portal.stimba.sk can
    // show it in the device card.
    private volatile String currentStatus    = "—";
    private volatile String lastError        = null;   // class + message of most recent throwable
    private volatile long   lastErrorAt      = 0L;     // epoch ms; 0 = never
    private volatile long   lastConnectedAt  = 0L;     // epoch ms; 0 = never

    public VncTunnelClient(Supplier tokenSupplier,
                           Supplier deviceIdSupplier,
                           Supplier relayUrlSupplier,
                           IntSupplier localPortSupplier,
                           BoolSupplier enabledSupplier,
                           StatusSink statusSink) {
        this.tokenSupplier     = tokenSupplier;
        this.deviceIdSupplier  = deviceIdSupplier;
        this.relayUrlSupplier  = relayUrlSupplier;
        this.localPortSupplier = localPortSupplier;
        this.enabledSupplier   = enabledSupplier;
        this.statusSink        = statusSink;
    }

    public synchronized void start() {
        if (running.get()) return;
        running.set(true);
        Thread t = new Thread(new Runnable() {
            @Override public void run() { connectorLoop(); }
        }, "stimba-vnc-relay");
        t.setDaemon(true);
        connectorThread = t;
        t.start();
        LOG.info("VNC relay tunnel started");
    }

    public synchronized void stop() {
        if (!running.get()) return;
        running.set(false);
        closeAllSilent();
        Thread t = connectorThread;
        if (t != null) t.interrupt();
        LOG.info("VNC relay tunnel stopped");
    }

    /** True when the WS upgrade is up AND the local x11vnc TCP socket is attached. */
    public boolean isConnected()  { return connected; }
    public boolean isRunning()    { return running.get(); }

    /** v3.12.11 — UI/heartbeat surface for the otherwise-invisible relay state. */
    public String getCurrentStatus()  { return currentStatus; }
    public String getLastError()      { return lastError; }
    public long   getLastErrorAt()    { return lastErrorAt; }
    public long   getLastConnectedAt(){ return lastConnectedAt; }

    // =====================================================================
    // Connector loop
    // =====================================================================

    private void connectorLoop() {
        long backoff = MIN_BACKOFF_MS;
        while (running.get()) {
            try {
                if (!enabledSupplier.getAsBoolean()) {
                    setStatus("Relay vypnutý v nastaveniach");
                    sleepInterruptible(10_000L);
                    continue;
                }
                String relayBase = trimToNull(relayUrlSupplier.get());
                String deviceId  = trimToNull(deviceIdSupplier.get());
                String token     = trimToNull(tokenSupplier.get());
                int    localPort = localPortSupplier.getAsInt();
                if (relayBase == null || deviceId == null || token == null) {
                    setStatus("Relay čaká na párovanie");
                    sleepInterruptible(5_000L);
                    continue;
                }

                setStatus("Relay pripája…");
                runSession(relayBase, deviceId, token, localPort);
                setStatus("Relay zavretý (clean)");
                backoff = MIN_BACKOFF_MS;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                connected = false;
                String errLine = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
                lastError   = errLine;
                lastErrorAt = System.currentTimeMillis();

                // v3.12.14 — DNS errors are usually a stale cache or a transient
                // resolver hiccup. Using the same exponential backoff (capped at
                // 60s) we use for socket-level errors meant a stale negative DNS
                // cache caused a 60s+ wait between retries — turning a few-second
                // problem into a many-minute one. UnknownHostException-class
                // failures get a tighter cap (15s) so we retry sooner. We also
                // reset the backoff growth so subsequent non-DNS errors start
                // fresh from MIN_BACKOFF_MS.
                boolean isDnsFailure = (t instanceof UnknownHostException);
                long effectiveCap = isDnsFailure ? DNS_FAIL_BACKOFF_CAP_MS : MAX_BACKOFF_MS;
                long waitMs = Math.min(effectiveCap, backoff);
                long waitS  = waitMs / 1000L;

                LOG.log(Level.WARNING, "relay session ended: " + errLine);
                setStatus("Relay reconnect za " + waitS + "s — " + errLine);
                try {
                    sleepInterruptible(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (isDnsFailure) {
                    // Don't compound DNS retries into the slow socket-error backoff
                    backoff = MIN_BACKOFF_MS;
                } else {
                    backoff = Math.min(MAX_BACKOFF_MS, backoff * 2L);
                }
            } finally {
                closeAllSilent();
                connected = false;
            }
        }
        setStatus("Relay zastavený");
        LOG.info("Relay connector exited");
    }

    // =====================================================================
    // Session: WS handshake + bidirectional pump
    // =====================================================================

    private void runSession(String relayBase, String deviceId, String token, int localPort)
            throws IOException, NoSuchAlgorithmException, InterruptedException {

        URI uri = URI.create(relayBase);
        String scheme = uri.getScheme();
        boolean secure = "wss".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null || host.isEmpty()) throw new IOException("Relay URL missing host: " + relayBase);
        if (port <= 0) port = secure ? 443 : 80;

        // ---- TCP / TLS to relay ----------------------------------------
        Socket raw;
        if (secure) {
            SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket s = (SSLSocket) sf.createSocket(host, port);
            s.setUseClientMode(true);
            s.startHandshake();
            raw = s;
        } else {
            raw = new Socket(host, port);
        }
        raw.setTcpNoDelay(true);
        raw.setSoTimeout(0);
        this.wsSocket = raw;

        InputStream  in  = new BufferedInputStream(raw.getInputStream());
        OutputStream out = new BufferedOutputStream(raw.getOutputStream());

        // ---- WS upgrade ------------------------------------------------
        byte[] keyBytes = new byte[16];
        rng.nextBytes(keyBytes);
        String wsKey = Base64.getEncoder().encodeToString(keyBytes);
        String path = "/agent/" + urlEncode(deviceId) + "?token=" + urlEncode(token);
        String hostHeader = host + (port == (secure ? 443 : 80) ? "" : (":" + port));

        StringBuilder req = new StringBuilder();
        req.append("GET ").append(path).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(hostHeader).append("\r\n");
        req.append("Upgrade: websocket\r\n");
        req.append("Connection: Upgrade\r\n");
        req.append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n");
        req.append("Sec-WebSocket-Version: 13\r\n");
        req.append("User-Agent: ").append(PortalClient.URCAP_VERSION).append("\r\n");
        req.append("\r\n");
        out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        String statusLine = readHttpLine(in);
        if (statusLine == null || !statusLine.contains(" 101 ")) {
            StringBuilder rest = new StringBuilder();
            String l;
            while ((l = readHttpLine(in)) != null && !l.isEmpty()) rest.append(l).append('\n');
            throw new IOException("WS upgrade failed: "
                    + (statusLine == null ? "(no status)" : statusLine)
                    + " | " + rest.toString().trim());
        }
        String expectedAccept = sha1Base64(wsKey + WS_GUID);
        boolean acceptOk = false;
        String line;
        while ((line = readHttpLine(in)) != null && !line.isEmpty()) {
            int c = line.indexOf(':');
            if (c < 0) continue;
            String k = line.substring(0, c).trim();
            String v = line.substring(c + 1).trim();
            if ("Sec-WebSocket-Accept".equalsIgnoreCase(k) && expectedAccept.equals(v)) {
                acceptOk = true;
            }
        }
        if (!acceptOk) throw new IOException("WS upgrade: missing/invalid Sec-WebSocket-Accept");

        LOG.info("WS relay connected " + host + ":" + port + " /agent/" + deviceId);

        // ---- Local TCP to x11vnc --------------------------------------
        Socket local = new Socket("127.0.0.1", localPort);
        local.setTcpNoDelay(true);
        local.setSoTimeout(0);
        this.localSocket = local;
        InputStream  localIn  = new BufferedInputStream(local.getInputStream());
        OutputStream localOut = new BufferedOutputStream(local.getOutputStream());

        connected = true;
        lastError = null;                              // v3.12.11
        lastConnectedAt = System.currentTimeMillis();  // v3.12.11
        setStatus("Relay pripojený");

        // ---- Pump WS -> local ------------------------------------------
        final OutputStream wsOutRef = out;
        final Socket       rawRef   = raw;
        final Socket       localRef = local;
        final OutputStream localOutRef = localOut;

        Thread wsToLocal = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    while (running.get()) {
                        Frame f = readFrame(in);
                        if (f == null) break;
                        if (f.opcode == WS_OP_CLOSE) break;
                        if (f.opcode == WS_OP_PING) {
                            synchronized (wsOutRef) {
                                writeFrame(wsOutRef, WS_OP_PONG, f.payload, maskKey());
                                wsOutRef.flush();
                            }
                            continue;
                        }
                        if (f.opcode == WS_OP_BINARY || f.opcode == WS_OP_CONT) {
                            localOutRef.write(f.payload);
                            localOutRef.flush();
                        }
                        // other opcodes (text 0x1, pong 0xA) ignored
                    }
                } catch (IOException ignored) {
                    // Normal path on close — peer closed the stream
                } finally {
                    closeSilent(localRef);
                    closeSilent(rawRef);
                }
            }
        }, "stimba-vnc-relay-ws2local");
        wsToLocal.setDaemon(true);
        wsToLocal.start();

        // ---- Pump local -> WS (main thread) ----------------------------
        byte[] buf = new byte[PUMP_BUFFER];
        try {
            int n;
            while (running.get() && (n = localIn.read(buf)) > 0) {
                byte[] slice = new byte[n];
                System.arraycopy(buf, 0, slice, 0, n);
                synchronized (out) {
                    writeFrame(out, WS_OP_BINARY, slice, maskKey());
                    out.flush();
                }
            }
        } finally {
            // Send polite close frame; peer will tear down
            try {
                synchronized (out) {
                    writeFrame(out, WS_OP_CLOSE, new byte[0], maskKey());
                    out.flush();
                }
            } catch (IOException ignored) { /* best effort */ }
            closeSilent(local);
            closeSilent(raw);
            try {
                wsToLocal.join(2_000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            connected = false;
        }
    }

    // =====================================================================
    // RFC 6455 framing — client-only (client frames MUST be masked,
    // server frames MUST NOT be masked, but we tolerate either).
    // =====================================================================

    private static final class Frame {
        final int opcode;
        final byte[] payload;
        Frame(int op, byte[] p) { this.opcode = op; this.payload = p; }
    }

    private static Frame readFrame(InputStream in) throws IOException {
        int b1 = in.read();
        if (b1 < 0) return null;
        int b2 = in.read();
        if (b2 < 0) return null;
        boolean fin = (b1 & 0x80) != 0;
        int opcode = b1 & 0x0F;
        boolean masked = (b2 & 0x80) != 0;
        long len = b2 & 0x7F;
        if (len == 126) {
            len = ((readOrFail(in) & 0xFFL) << 8) | (readOrFail(in) & 0xFFL);
        } else if (len == 127) {
            len = 0L;
            for (int i = 0; i < 8; i++) len = (len << 8) | (readOrFail(in) & 0xFFL);
        }
        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            readFully(in, mask);
        }
        if (len < 0 || len > (long) Integer.MAX_VALUE) {
            throw new IOException("frame length out of range: " + len);
        }
        byte[] payload = new byte[(int) len];
        readFully(in, payload);
        if (masked) {
            for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
        }
        // v3.12 simplification — relay sends single-frame binary, no fragmentation.
        if (!fin && opcode != WS_OP_CONT) {
            throw new IOException("unexpected fragmented frame from relay");
        }
        return new Frame(opcode, payload);
    }

    private static void writeFrame(OutputStream out, int opcode, byte[] payload, byte[] mask)
            throws IOException {
        out.write(0x80 | (opcode & 0x0F));                  // FIN + opcode
        int len = payload.length;
        if (len < 126) {
            out.write(0x80 | len);
        } else if (len < 65_536) {
            out.write(0x80 | 126);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(0x80 | 127);
            long l = len;
            for (int i = 7; i >= 0; i--) out.write((int) ((l >> (i * 8)) & 0xFFL));
        }
        out.write(mask);
        for (int i = 0; i < len; i++) out.write(payload[i] ^ mask[i & 3]);
    }

    private byte[] maskKey() {
        byte[] k = new byte[4];
        rng.nextBytes(k);
        return k;
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static int readOrFail(InputStream in) throws IOException {
        int v = in.read();
        if (v < 0) throw new EOFException();
        return v;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new EOFException("short read");
            off += n;
        }
    }

    /** Read a CRLF-terminated HTTP line (ISO-8859-1). Returns null on EOF before CRLF. */
    private static String readHttpLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(128);
        int last = -1;
        int cur;
        while ((cur = in.read()) != -1) {
            if (last == '\r' && cur == '\n') {
                // trim trailing \r
                return sb.substring(0, sb.length() - 1);
            }
            sb.append((char) cur);
            last = cur;
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static String sha1Base64(String s) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] hash = md.digest(s.getBytes(StandardCharsets.ISO_8859_1));
        return Base64.getEncoder().encodeToString(hash);
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private void setStatus(String s) {
        currentStatus = (s == null) ? "—" : s;        // v3.12.11
        if (statusSink == null) return;
        try { statusSink.update(s); } catch (Throwable ignored) { /* swallow */ }
    }

    private static void sleepInterruptible(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    private void closeAllSilent() {
        closeSilent(this.localSocket);
        closeSilent(this.wsSocket);
        this.localSocket = null;
        this.wsSocket = null;
    }

    private static void closeSilent(Closeable c) {
        if (c != null) { try { c.close(); } catch (IOException ignored) { /* ok */ } }
    }
}
