/*
 * STIMBA VNC Server URCap — Polyscope Dashboard Server client (v3.4.0+)
 *
 * Newline-terminated textual protocol on 127.0.0.1:29999. Used by the
 * heartbeat loop to snapshot robotmode / safetymode / loaded program /
 * running state with zero RTDE overhead. Connection is opened per-snapshot
 * (fire-and-forget) because PS kills idle sockets after ~60s anyway.
 *
 * Dashboard reference:
 *   https://www.universal-robots.com/articles/ur/interface-communication/dashboard-server-e-series-port-29999/
 *
 * All methods must tolerate every failure mode (PS restart, ethernet drop,
 * firewall reject) and return a "disconnected" snapshot instead of throwing —
 * the heartbeat runner keeps going regardless.
 */
package sk.stimba.urcap.vnc.impl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class DashboardClient {

    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int    DEFAULT_PORT = 29999;
    private static final int    CONNECT_TIMEOUT_MS = 2_000;
    private static final int    READ_TIMEOUT_MS    = 2_000;

    private final String host;
    private final int port;

    public DashboardClient() { this(DEFAULT_HOST, DEFAULT_PORT); }

    public DashboardClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public static final class Snapshot {
        public final boolean connected;
        public final String robotMode;     // "POWER_OFF", "IDLE", "RUNNING", ...
        public final String safetyMode;    // "NORMAL", "REDUCED", "PROTECTIVE_STOP", ...
        public final String loadedProgram; // e.g. "default.urp" or null
        public final Boolean programRunning; // true / false / null (no info)

        public Snapshot(boolean connected, String robotMode, String safetyMode,
                        String loadedProgram, Boolean programRunning) {
            this.connected = connected;
            this.robotMode = robotMode;
            this.safetyMode = safetyMode;
            this.loadedProgram = loadedProgram;
            this.programRunning = programRunning;
        }

        public static Snapshot disconnected() {
            return new Snapshot(false, null, null, null, null);
        }
    }

    public Snapshot snapshot() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(READ_TIMEOUT_MS);
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter out = new PrintWriter(
                    new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), false)) {

                // PS prints a banner line on connect — read and discard.
                in.readLine();

                String robotMode     = parseAfter(ask(out, in, "robotmode"),        "Robotmode:");
                String safetyMode    = parseAfter(ask(out, in, "safetymode"),       "Safetymode:");
                String loadedProgram = parseAfter(ask(out, in, "get loaded program"), "Loaded program:");
                if (loadedProgram != null && loadedProgram.startsWith("/")) {
                    int idx = loadedProgram.lastIndexOf('/');
                    loadedProgram = loadedProgram.substring(idx + 1);
                }
                String running  = ask(out, in, "running");
                Boolean programRunning = running == null ? null : Boolean.valueOf(
                        running.contains("true") || running.contains("Program running: true"));

                return new Snapshot(true, robotMode, safetyMode, loadedProgram, programRunning);
            }
        } catch (IOException e) {
            return Snapshot.disconnected();
        }
    }

    private static String ask(PrintWriter out, BufferedReader in, String cmd) throws IOException {
        out.print(cmd);
        out.print('\n');
        out.flush();
        return in.readLine();
    }

    private static String parseAfter(String line, String prefix) {
        if (line == null) return null;
        int i = line.indexOf(prefix);
        if (i < 0) return null;
        return line.substring(i + prefix.length()).trim();
    }
}
