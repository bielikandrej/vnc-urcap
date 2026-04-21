/*
 * STIMBA VNC Server URCap — Portal heartbeat thread (v3.4.0+)
 *
 * Fires every 30s while the URCap view is open and the robot is paired with
 * portal.stimba.sk. Snapshots Dashboard Server :29999 and POSTs to
 * /api/agent/heartbeat. Pure background work — never blocks the EDT and
 * swallows all failures; worst case the portal UI shows the device as offline
 * for a minute and we recover on the next tick.
 */
package sk.stimba.urcap.vnc.impl;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public final class PortalHeartbeatRunner {

    private static final Logger LOG = Logger.getLogger(PortalHeartbeatRunner.class.getName());
    private static final String AGENT_VERSION = PortalClient.URCAP_VERSION;
    private static final int DEFAULT_INTERVAL_S = 30;
    private static final int DEFAULT_INITIAL_DELAY_S = 3;

    private final PortalClient client;
    private final DashboardClient dashboard;
    private final Supplier tokenSupplier;
    private final Supplier deviceIdSupplier;
    private final StatusSink statusSink;
    private final long startedAtMs = System.currentTimeMillis();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ScheduledExecutorService exec;

    /**
     * Functional interfaces kept primitive to stay compatible with Java 8
     * (UR e-Series still ships PS5 with Java 8 runtime).
     */
    public interface Supplier { String get(); }
    public interface StatusSink { void update(String status); }

    public PortalHeartbeatRunner(PortalClient client,
                                 DashboardClient dashboard,
                                 Supplier tokenSupplier,
                                 Supplier deviceIdSupplier,
                                 StatusSink statusSink) {
        this.client = client;
        this.dashboard = dashboard;
        this.tokenSupplier = tokenSupplier;
        this.deviceIdSupplier = deviceIdSupplier;
        this.statusSink = statusSink;
    }

    public synchronized void start() {
        if (running.get()) return;
        running.set(true);
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stimba-portal-heartbeat");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(this::tick,
                DEFAULT_INITIAL_DELAY_S, DEFAULT_INTERVAL_S, TimeUnit.SECONDS);
        LOG.info("Portal heartbeat started (interval=" + DEFAULT_INTERVAL_S + "s)");
    }

    public synchronized void stop() {
        if (!running.get()) return;
        running.set(false);
        if (exec != null) {
            exec.shutdownNow();
            exec = null;
        }
        LOG.info("Portal heartbeat stopped");
    }

    public boolean isRunning() {
        return running.get();
    }

    private void tick() {
        String token = tokenSupplier.get();
        String deviceId = deviceIdSupplier.get();
        if (token == null || token.isEmpty() || deviceId == null || deviceId.isEmpty()) {
            updateStatus("Nespárované");
            return;
        }
        try {
            DashboardClient.Snapshot snap;
            try {
                snap = dashboard.snapshot();
            } catch (Throwable t) {
                snap = DashboardClient.Snapshot.disconnected();
            }
            String body = buildHeartbeatBody(snap);
            boolean ok = client.heartbeat(token, deviceId, body);
            if (ok) {
                updateStatus("Online · " + (snap.connected ? "Dashboard OK" : "Dashboard nedostupný"));
            } else {
                updateStatus("Chyba heartbeat — over token / sieť");
            }
        } catch (Throwable t) {
            LOG.warning("heartbeat tick failed: " + t.getMessage());
            updateStatus("Chyba: " + t.getClass().getSimpleName());
        }
    }

    private String buildHeartbeatBody(DashboardClient.Snapshot snap) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("ts", Instant.now().toString());
        payload.put("agentVersion", AGENT_VERSION);
        payload.put("uptimeS", (int) ((System.currentTimeMillis() - startedAtMs) / 1000));
        payload.put("robotMode", snap.robotMode);
        payload.put("safetyStatus", snap.safetyMode);
        payload.put("loadedProgram", snap.loadedProgram);
        payload.put("programRunning", snap.programRunning);
        payload.put("rtdeConnected", false);
        payload.put("dashboardConnected", snap.connected);

        LinkedHashMap<String, Object> queue = new LinkedHashMap<>();
        queue.put("metrics", 0);
        queue.put("audit", 0);
        payload.put("queueDepth", queue);

        return PortalClient.toJson(payload);
    }

    private void updateStatus(String s) {
        if (statusSink == null) return;
        try {
            statusSink.update(s);
        } catch (Throwable t) {
            // swallow — UI might be closed
        }
    }
}
