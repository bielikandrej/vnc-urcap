/*
 * STIMBA VNC Server URCap — Primary Interface client (v3.6.0+)
 *
 * Raw TCP client for UR Primary Interface on 127.0.0.1:30001. Used by the
 * agent command dispatcher to send free URScript (urscript_send tool) as well
 * as synthesized one-liners for io_set_digital_out, set_vnc_password
 * helpers, etc.
 *
 * Protocol: you open a TCP connection to :30001, write URScript text
 * terminated with a newline, and close the connection. UR executes the script
 * immediately on receive. No response is returned on Primary — if we need
 * feedback we read Secondary Interface :30002 (Sprint 7 scope).
 *
 * Design: DEEP_RESEARCH_AI_ROBOT_CONTROL.md §1.2
 *
 * Safety layers applied at call site (dispatcher), not here — this class is
 * a dumb pipe. If you change it, preserve the Java 8 compat (UR e-Series
 * still ships Java 8) and the "never throw on socket close" behaviour.
 */
package sk.stimba.urcap.vnc.impl;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

public final class PrimaryInterfaceClient {

    private static final Logger LOG = Logger.getLogger(PrimaryInterfaceClient.class.getName());
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 30001;
    private static final int CONNECT_TIMEOUT_MS = 2_000;
    private static final int WRITE_TIMEOUT_MS = 2_000;

    private final String host;
    private final int port;

    public PrimaryInterfaceClient() {
        this(DEFAULT_HOST, DEFAULT_PORT);
    }

    public PrimaryInterfaceClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * Send a URScript snippet to the Primary Interface. The UR controller
     * executes on receipt — caller must have validated the script upstream.
     *
     * @param script URScript text (any length, a trailing newline will be
     *               added if missing). Can be a single line
     *               ({@code set_digital_out(0, True)}) or a multi-line
     *               block.
     * @throws IOException on connect/write failure
     */
    public void sendScript(String script) throws IOException {
        if (script == null || script.isEmpty()) {
            throw new IOException("empty script");
        }
        String payload = script.endsWith("\n") ? script : script + "\n";
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);

        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(WRITE_TIMEOUT_MS);
            try (OutputStream os = socket.getOutputStream()) {
                os.write(bytes);
                os.flush();
            }
            LOG.info("sent URScript (" + bytes.length + " bytes) to Primary Interface");
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    /**
     * Convenience — build + send a single-line URScript from a verb + args.
     * Used by io_set_digital_out to avoid leaking the exact URScript shape
     * into the dispatcher (which is already busy).
     */
    public void sendOneLiner(String urscriptLine) throws IOException {
        sendScript(urscriptLine);
    }
}
