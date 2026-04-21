/*
 * STIMBA VNC Server URCap — Dashboard command poller (v3.5.0+)
 *
 * Polls portal.stimba.sk /api/agent/commands every 5s (when paired). For each
 * claimed command, executes the matching Dashboard Server action and PATCHes
 * the ack back to portal with status=completed / failed.
 *
 * Scope in v3.5.0 (matches portal /api/ai/chat TOOL_ALLOWLIST):
 *   - set_vnc_password       (handled inline — writes x11vnc -storepasswd + restart)
 *   - dashboard_power_on     (DashboardClient -> 'power on')
 *   - dashboard_brake_release (DashboardClient -> 'brake release')
 *   - dashboard_safetymode   (DashboardClient -> 'safetymode normal')
 *   - program_load           (DashboardClient -> 'load <program_name>.urp')
 *   - program_play           (DashboardClient -> 'play')
 *   - program_pause          (DashboardClient -> 'pause')
 *   - program_stop           (DashboardClient -> 'stop')
 *   - io_set_digital_out     (DashboardClient -> setting digital out via primary I/O script)
 *
 * Safety: every command that moves the robot (power_on, brake_release,
 * program_play, io_set_digital_out) MUST have been approved on the portal
 * side by a user with ai.execute permission — portal gates that at the
 * /api/ai/execute enqueue point. URCap just runs what the queue contains.
 *
 * Poll interval is 5s. We honor X-Stimba-Poll-Hint-Sec from portal responses
 * if present, clamped to [5, 60].
 */
package sk.stimba.urcap.vnc.impl;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public final class DashboardCommandPoller {

    private static final Logger LOG = Logger.getLogger(DashboardCommandPoller.class.getName());
    private static final int DEFAULT_POLL_S = 5;

    private final PortalClient client;
    private final DashboardClient dashboard;
    private final PortalHeartbeatRunner.Supplier tokenSupplier;
    private final PortalHeartbeatRunner.Supplier deviceIdSupplier;
    private final CommandDispatcher dispatcher;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService exec;

    public interface CommandDispatcher {
        /**
         * Execute the named tool with args-json. Return either:
         *   - new Ack(true, resultJson, null)  — success
         *   - new Ack(false, null, errorMsg)   — failure (portal will record and the
         *     operator will see it in /audit)
         *
         * MUST not throw.
         */
        Ack execute(String toolName, String argsJson);
    }

    public static final class Ack {
        public final boolean ok;
        public final String result;
        public final String error;
        public Ack(boolean ok, String result, String error) {
            this.ok = ok; this.result = result; this.error = error;
        }
    }

    public DashboardCommandPoller(PortalClient client,
                                   DashboardClient dashboard,
                                   PortalHeartbeatRunner.Supplier tokenSupplier,
                                   PortalHeartbeatRunner.Supplier deviceIdSupplier,
                                   CommandDispatcher dispatcher) {
        this.client = client;
        this.dashboard = dashboard;
        this.tokenSupplier = tokenSupplier;
        this.deviceIdSupplier = deviceIdSupplier;
        this.dispatcher = dispatcher;
    }

    public synchronized void start() {
        if (running.get()) return;
        running.set(true);
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stimba-portal-cmd-poll");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(this::tick, 10, DEFAULT_POLL_S, TimeUnit.SECONDS);
        LOG.info("Portal command poller started (" + DEFAULT_POLL_S + "s)");
    }

    public synchronized void stop() {
        if (!running.get()) return;
        running.set(false);
        if (exec != null) { exec.shutdownNow(); exec = null; }
    }

    public boolean isRunning() { return running.get(); }

    private void tick() {
        String token = tokenSupplier.get();
        String deviceId = deviceIdSupplier.get();
        if (token == null || token.isEmpty() || deviceId == null || deviceId.isEmpty()) return;

        try {
            List<PortalClient.QueuedCommand> batch = client.pollCommands(token, deviceId);
            for (PortalClient.QueuedCommand cmd : batch) {
                Ack result;
                try {
                    result = dispatcher.execute(cmd.toolName, cmd.argsJson);
                } catch (Throwable t) {
                    result = new Ack(false, null, "dispatcher threw: " + t.getMessage());
                }
                client.ackCommand(token, deviceId, cmd.id, result.ok, result.result, result.error);
            }
        } catch (Throwable t) {
            LOG.warning("command poll tick failed: " + t.getMessage());
        }
    }
}
