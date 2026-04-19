/*
 * STIMBA VNC Server URCap — OSGi Activator
 * Copyright (c) 2026 STIMBA, s. r. o.
 *
 * Registers:
 *   - DaemonService                    — Polyscope-managed x11vnc daemon
 *   - SwingInstallationNodeService     — Installation tab UI
 *
 * Targets URCap API 1.3.0 (Polyscope 5.0 – 5.25+, CB3 3.x).
 */
package sk.stimba.urcap.vnc.impl;

import com.ur.urcap.api.contribution.DaemonService;
import com.ur.urcap.api.contribution.installation.swing.SwingInstallationNodeService;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

public class Activator implements BundleActivator {

    @Override
    public void start(final BundleContext context) throws Exception {
        System.out.println("[stimba-vnc] URCap activator starting…");

        VncDaemonService daemonService = new VncDaemonService();
        VncInstallationNodeService installationNodeService =
                new VncInstallationNodeService(daemonService);

        context.registerService(SwingInstallationNodeService.class, installationNodeService, null);
        context.registerService(DaemonService.class, daemonService, null);

        System.out.println("[stimba-vnc] URCap services registered.");
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        System.out.println("[stimba-vnc] URCap activator stopping.");
    }
}
