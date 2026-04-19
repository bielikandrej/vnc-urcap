/*
 * STIMBA VNC Server URCap — DaemonService implementation
 * Copyright (c) 2026 STIMBA, s. r. o.
 *
 * Follows the canonical Universal Robots MyDaemonSwing pattern (API 1.3.0).
 *
 * Polyscope calls init() once with a DaemonContribution handle. We:
 *   - install the daemon resource folder (bash scripts)
 *   - point getExecutable() at our run-vnc.sh
 * Polyscope then controls the process via DaemonContribution.start()/stop()/getState().
 */
package sk.stimba.urcap.vnc.impl;

import com.ur.urcap.api.contribution.DaemonContribution;
import com.ur.urcap.api.contribution.DaemonService;

import java.net.MalformedURLException;
import java.net.URL;

public class VncDaemonService implements DaemonService {

    /**
     * Path inside the bundle JAR to the daemon resource folder.
     * Must match the directory under src/main/resources/.
     */
    private static final String DAEMON_RESOURCE_FOLDER =
            "file:sk/stimba/urcap/vnc/impl/daemon/";

    /** The actual executable shell script. */
    private static final String DAEMON_EXECUTABLE =
            "file:sk/stimba/urcap/vnc/impl/daemon/run-vnc.sh";

    private DaemonContribution daemonContribution;

    @Override
    public void init(DaemonContribution daemonContribution) {
        this.daemonContribution = daemonContribution;
        try {
            daemonContribution.installResource(new URL(DAEMON_RESOURCE_FOLDER));
        } catch (MalformedURLException e) {
            System.err.println("[stimba-vnc] installResource failed: " + e.getMessage());
        }
    }

    @Override
    public URL getExecutable() {
        try {
            return new URL(DAEMON_EXECUTABLE);
        } catch (MalformedURLException e) {
            System.err.println("[stimba-vnc] getExecutable malformed URL: " + e.getMessage());
            return null;
        }
    }

    /** Used by the installation node to start/stop the daemon. */
    public DaemonContribution getDaemon() {
        return daemonContribution;
    }
}
