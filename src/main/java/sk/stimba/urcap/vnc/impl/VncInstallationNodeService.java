/*
 * STIMBA VNC Server URCap — Installation node service
 * Copyright (c) 2026 STIMBA, s. r. o.
 */
package sk.stimba.urcap.vnc.impl;

import com.ur.urcap.api.contribution.ViewAPIProvider;
import com.ur.urcap.api.contribution.installation.ContributionConfiguration;
import com.ur.urcap.api.contribution.installation.CreationContext;
import com.ur.urcap.api.contribution.installation.InstallationAPIProvider;
import com.ur.urcap.api.contribution.installation.swing.SwingInstallationNodeService;
import com.ur.urcap.api.domain.data.DataModel;

import java.util.Locale;

public class VncInstallationNodeService
        implements SwingInstallationNodeService<VncInstallationNodeContribution, VncInstallationNodeView> {

    private final VncDaemonService daemonService;

    public VncInstallationNodeService(VncDaemonService daemonService) {
        this.daemonService = daemonService;
    }

    @Override
    public String getTitle(Locale locale) {
        return "VNC Server (STIMBA)";
    }

    @Override
    public void configureContribution(ContributionConfiguration configuration) {
        // leave defaults
    }

    @Override
    public VncInstallationNodeView createView(ViewAPIProvider apiProvider) {
        return new VncInstallationNodeView();
    }

    @Override
    public VncInstallationNodeContribution createInstallationNode(
            InstallationAPIProvider apiProvider,
            VncInstallationNodeView view,
            DataModel model,
            CreationContext context) {
        return new VncInstallationNodeContribution(apiProvider, view, model, daemonService, context);
    }
}
