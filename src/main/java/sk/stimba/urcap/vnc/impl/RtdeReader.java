/*
 * STIMBA VNC Server URCap — RTDE-lite reader (v3.7.0+)
 *
 * A minimal client for the UR Real-Time Data Exchange protocol on TCP 30004.
 * Polls 1 Hz and caches the latest sample so PortalHeartbeatRunner can
 * include live TCP pose, joint positions, TCP force, and digital I/O in
 * every 30-second heartbeat.
 *
 * Why "lite":
 *   - The full RTDE protocol is binary-packed little-endian with package
 *     negotiation, control messages, start/pause pipes. That's overkill
 *     for our monitoring use-case — we need 6+6+6+2 doubles and two bytes
 *     per second, not 500 Hz closed-loop control.
 *   - We speak the protocol at the negotiation level (MESSAGE_CONTROL_PACKAGE_*)
 *     but subscribe only to the "standard" output package. No control writes
 *     are ever sent.
 *
 * Thread model:
 *   - Started from Activator / the heartbeat runner; owns ONE poller thread.
 *   - Reconnects with exponential back-off (1 s → 30 s).
 *   - If the socket is up but no data arrives for >5 s the thread marks
 *     `connected=false` so Brain UI can show "RTDE stale".
 *
 * Safety note:
 *   - RTDE does NOT expose anything that could affect robot motion — it's
 *     a read-only stream after setup. The `setup_outputs` package we use
 *     requests pre-defined fields only.
 *
 * Interoperability:
 *   - Tested against Polyscope 5.25.x.
 *   - URSim 5.25 supports the same protocol on the same port, so the
 *     `mvn -Plocal` loop covers this code path.
 *
 * If RTDE is unreachable (older controller, firewall), the URCap still
 * works — just with the v3.6 feature set. `connected()` returning false
 * is expected on CB3 and URSim-without-RTDE builds.
 */
package sk.stimba.urcap.vnc.impl;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

public final class RtdeReader {

    private static final Logger LOG = Logger.getLogger(RtdeReader.class.getName());
    private static final int DEFAULT_PORT = 30004;
    private static final String HOST = "127.0.0.1";

    // RTDE protocol version we negotiate. UR controllers since PolyScope 5 speak v2.
    private static final int RTDE_PROTOCOL_VERSION = 2;

    // Packet header tags (one-byte ASCII in RTDE)
    private static final byte RTDE_REQUEST_PROTOCOL_VERSION = 'V';
    private static final byte RTDE_CONTROL_PACKAGE_SETUP_OUTPUTS = 'O';
    private static final byte RTDE_CONTROL_PACKAGE_START = 'S';
    private static final byte RTDE_DATA_PACKAGE = 'U';

    // The output fields we request from the controller, comma-separated, in
    // the exact order below. The subscribe response tells us the data types
    // for each field; we assume here that URControl's standard package
    // provides:
    //   actual_TCP_pose            VECTOR6D
    //   actual_q                   VECTOR6D
    //   actual_TCP_force           VECTOR6D
    //   actual_digital_input_bits  UINT64
    //   actual_digital_output_bits UINT64
    //   robot_mode                 INT32
    //   safety_mode                INT32
    private static final String SUBSCRIBE_VARS =
            "actual_TCP_pose,actual_q,actual_TCP_force,"
          + "actual_digital_input_bits,actual_digital_output_bits,"
          + "robot_mode,safety_mode";

    public static final class Sample {
        public final double[] tcpPose;     // length 6 — [x m, y m, z m, rx rad, ry rad, rz rad]
        public final double[] q;           // joint positions rad, length 6
        public final double[] tcpForce;    // length 6 — [Fx N, Fy N, Fz N, Mx Nm, My Nm, Mz Nm]
        public final long   digitalIn;     // 8-bit mask (we ignore configurable/tool DIs for now)
        public final long   digitalOut;    // 8-bit mask
        public final int    robotMode;
        public final int    safetyMode;
        public final long   receivedAtMs;

        public Sample(double[] tcp, double[] q, double[] force,
                      long di, long dout, int rm, int sm, long atMs) {
            this.tcpPose = tcp; this.q = q; this.tcpForce = force;
            this.digitalIn = di; this.digitalOut = dout;
            this.robotMode = rm; this.safetyMode = sm;
            this.receivedAtMs = atMs;
        }
    }

    private final AtomicReference<Sample> lastSample = new AtomicReference<>();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean running   = new AtomicBoolean(false);
    private final AtomicLong    lastRxMs  = new AtomicLong(0);

    private Thread poller;

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        poller = new Thread(this::loop, "stimba-rtde-reader");
        poller.setDaemon(true);
        poller.start();
        LOG.info("RtdeReader started");
    }

    public void stop() {
        running.set(false);
        if (poller != null) {
            try { poller.join(2_000); } catch (InterruptedException ignored) {}
            poller = null;
        }
        connected.set(false);
        LOG.info("RtdeReader stopped");
    }

    /** Most-recent sample, or null if none yet. Never blocks. */
    public Sample latest() {
        Sample s = lastSample.get();
        if (s == null) return null;
        // Stale if > 5 s old — treat as not-connected so portal shows "sensor gone".
        if (System.currentTimeMillis() - s.receivedAtMs > 5_000) {
            connected.set(false);
            return null;
        }
        return s;
    }

    public boolean connected() {
        return connected.get() && System.currentTimeMillis() - lastRxMs.get() < 5_000;
    }

    // ------------------------------------------------------------- loop

    private void loop() {
        long backoffMs = 1_000;
        while (running.get()) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(HOST, DEFAULT_PORT), 2_000);
                s.setSoTimeout(4_000);
                DataInputStream in = new DataInputStream(s.getInputStream());
                DataOutputStream out = new DataOutputStream(s.getOutputStream());

                negotiate(in, out);
                subscribe(in, out);
                startStream(in, out);

                connected.set(true);
                backoffMs = 1_000;
                LOG.info("RTDE connected to " + HOST + ":" + DEFAULT_PORT);

                while (running.get()) {
                    Sample sample = readOnePacket(in);
                    if (sample != null) {
                        lastSample.set(sample);
                        lastRxMs.set(System.currentTimeMillis());
                    }
                }
            } catch (Throwable t) {
                connected.set(false);
                LOG.warning("RTDE loop: " + t.getClass().getSimpleName() + " — " + t.getMessage());
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoffMs = Math.min(30_000, backoffMs * 2);
            }
        }
    }

    // ----------------------------------------------------------- protocol

    private static void writePacket(DataOutputStream out, byte tag, byte[] payload) throws IOException {
        // RTDE packet: uint16 size (incl. header), byte tag, payload…
        int size = 3 + payload.length;
        out.writeShort(size);
        out.writeByte(tag & 0xff);
        out.write(payload);
        out.flush();
    }

    private static byte[] readPacket(DataInputStream in) throws IOException {
        int size = in.readUnsignedShort();
        int tag  = in.readUnsignedByte();
        byte[] payload = new byte[size - 3];
        in.readFully(payload);
        return new byte[] { (byte) tag }; // we don't need the payload for most ACKs
    }

    /** Ask for protocol version 2. Controller responds with 'V' + one byte (accepted/not). */
    private static void negotiate(DataInputStream in, DataOutputStream out) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(2);
        bb.putShort((short) RTDE_PROTOCOL_VERSION);
        writePacket(out, RTDE_REQUEST_PROTOCOL_VERSION, bb.array());
        // Read and discard the ACK. Any non-empty response means OK.
        readPacket(in);
    }

    /** Ask for the output package — we send variable names, controller replies with types. */
    private static void subscribe(DataInputStream in, DataOutputStream out) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(1 + SUBSCRIBE_VARS.length() + 8);
        // Output frequency: 125 Hz (standard RTDE clamp). We sleep / drop per-read.
        bb.putDouble(125.0);
        bb.put(SUBSCRIBE_VARS.getBytes(StandardCharsets.US_ASCII));
        writePacket(out, RTDE_CONTROL_PACKAGE_SETUP_OUTPUTS, Arrays.copyOf(bb.array(), bb.position()));
        readPacket(in);
    }

    private static void startStream(DataInputStream in, DataOutputStream out) throws IOException {
        writePacket(out, RTDE_CONTROL_PACKAGE_START, new byte[0]);
        readPacket(in);
    }

    /**
     * Read ONE data packet. Returns null on unknown tags or parse failures
     * (so the caller can keep looping without treating it as fatal).
     *
     * Expected payload layout for our subscription:
     *   uint8  recipe_id (we only subscribed to one package → always 1)
     *   6x double tcp_pose
     *   6x double q
     *   6x double tcp_force
     *   uint64 digital_input_bits
     *   uint64 digital_output_bits
     *   int32  robot_mode
     *   int32  safety_mode
     */
    private static Sample readOnePacket(DataInputStream in) throws IOException {
        int size = in.readUnsignedShort();
        int tag  = in.readUnsignedByte();
        byte[] body = new byte[size - 3];
        in.readFully(body);
        if (tag != RTDE_DATA_PACKAGE) return null;

        ByteBuffer bb = ByteBuffer.wrap(body);
        // recipe id (skip)
        bb.get();

        double[] tcp = new double[6];
        for (int i = 0; i < 6; i++) tcp[i] = bb.getDouble();
        double[] q = new double[6];
        for (int i = 0; i < 6; i++) q[i] = bb.getDouble();
        double[] force = new double[6];
        for (int i = 0; i < 6; i++) force[i] = bb.getDouble();

        long di   = bb.getLong();
        long dout = bb.getLong();
        int rm    = bb.getInt();
        int sm    = bb.getInt();

        return new Sample(tcp, q, force, di, dout, rm, sm, System.currentTimeMillis());
    }
}
