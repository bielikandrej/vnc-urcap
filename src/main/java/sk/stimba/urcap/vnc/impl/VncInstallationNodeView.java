/*
 * STIMBA VNC Server URCap — Swing view (v3.0.0)
 * Copyright (c) 2026 STIMBA, s. r. o.
 *
 * Polyscope Installation tab UI.
 *
 * Sprint 1 (v2.1.0) added:
 *   - IXrouter IP (A2) with IPv4 regex validation
 *   - Customer label (A3) for fleet LOG_TAG
 *   - Easybot banner + strong-pwd checkbox (A5)
 *   - Health panel with 5 dot indicators (A4)
 *   - Password strength live indicator (B5, first 8 chars only)
 *   - Apply button that atomically writes /var/lib/urcap-vnc/config
 *
 * Sprint 2 (v2.2.0) added:
 *   - Log tail panel with 3s auto-refresh Swing Timer (B1)
 *   - "Exportovať diagnostiku" button (B2)
 *   - "+ Dočasná IP" dialog + live table of active temp rules (B3)
 *   - "Test spojenia" button (B6)
 *
 * Sprint 3 (v3.0.0) adds:
 *   - TLS toggle + warning banner + "Zobraziť cert fingerprint" (C1)
 *   - Idle timeout JSpinner 0..120 min (C2)
 *   - Max clients JSpinner 1..5 (C4)
 *   - (?) info-buttons on every field with 2-3 sentence SK tooltips (C7)
 *
 * The contribution owns all state. The view is a thin UI: it pushes user
 * input into the contribution via setXxx(...), and the contribution pushes
 * values back into the UI via updateXxx(...). openView() fires an initial
 * round of updates; Swing Timers then refresh daemon state (1s), health
 * panel (5s), log tail (3s), and temp allowlist table (10s).
 */
package sk.stimba.urcap.vnc.impl;

import com.ur.urcap.api.contribution.installation.swing.SwingInstallationNodeView;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ButtonGroup;
import javax.swing.ScrollPaneConstants;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

public class VncInstallationNodeView
        implements SwingInstallationNodeView<VncInstallationNodeContribution> {

    // Stoplight colors — reused for health dots + password strength + banner
    private static final Color COLOR_OK      = new Color(0x2E7D32); // green 800
    private static final Color COLOR_WARN    = new Color(0xF9A825); // amber 800
    private static final Color COLOR_FAIL    = new Color(0xC62828); // red 800
    private static final Color COLOR_UNKNOWN = new Color(0x9E9E9E); // grey 500

    private VncInstallationNodeContribution contribution;

    // --- Field widgets -----------------------------------------------------
    private JSpinner       portSpinner;
    private JPasswordField passwordField;
    private JCheckBox      viewOnlyBox;
    private JCheckBox      autostartBox;
    private JTextField     ixrouterField;
    private JLabel         ixrouterValidationLabel;
    private JTextField     customerLabelField;
    private JLabel         customerValidationLabel;
    private JCheckBox      strongPwdBox;
    private JLabel         easybotBanner;
    private JLabel         stateLabel;
    private JLabel         statusLabel;      // last Apply result
    private JLabel         pwdStrengthLabel;

    // --- Health dots -------------------------------------------------------
    private JLabel dotDaemon, dotIptables, dotPort, dotDisplay, dotIxrouter;

    // --- Sprint 2 widgets --------------------------------------------------
    private JTextArea          logTailArea;       // B1
    private JButton            logRefreshBtn;
    private JCheckBox          logAutoRefreshBox;
    private JTable             tempAllowTable;    // B3
    private DefaultTableModel  tempAllowModel;
    private JLabel             testConnResultLabel; // B6
    private Timer              logTimer;
    private Timer              tempAllowTimer;

    // --- Sprint 3 widgets --------------------------------------------------
    private JCheckBox          tlsEnabledBox;         // C1
    private JLabel             tlsWarningBanner;      // C1 — red banner when TLS off
    private JLabel             tlsFingerprintLabel;   // C1 — inline SHA-256 preview
    private JButton            tlsFingerprintBtn;     // C1 — opens full-fingerprint dialog
    private JSpinner           idleTimeoutSpinner;    // C2
    private JSpinner           maxClientsSpinner;     // C4

    // --- Sprint 3 tooltip texts (C7) — SK, 2-3 sentences, operator-friendly ---
    private static final String TIP_PORT =
            "<html>Štandardný VNC port je 5900. Meň len ak potrebuješ viac VNC inštancií<br>"
          + "na jednom robote (zriedkavé) — inak nechaj default.<br>"
          + "<i>Pozn.: port musí byť zhodný s IXON portálom na druhej strane.</i></html>";
    private static final String TIP_PASSWORD =
            "<html><b>Heslo pre každé VNC pripojenie.</b><br>"
          + "Pozor: RFB protokol obmedzuje heslo na <b>prvých 8 znakov</b> — všetko po 8. znaku<br>"
          + "sa ignoruje. Použi silnú kombináciu malé + VEĽKÉ + číslica + špec. znak v týchto 8.<br>"
          + "Viac: wiki/04-gotchas.md G2.</html>";
    private static final String TIP_VIEW_ONLY =
            "<html>Keď zaškrtnuté, VNC klient môže <b>iba pozerať</b> — nemôže klikať ani písať.<br>"
          + "Pre remote diagnostiku často stačí. Pre teach pendant operácie odškrtni.</html>";
    private static final String TIP_AUTOSTART =
            "<html>Automatický štart daemona po boot-e Polyscope UI.<br>"
          + "Pri odškrtnutí musíš spustiť manuálne cez tlačidlo <b>Start</b>.</html>";
    private static final String TIP_IXROUTER =
            "<html>IPv4 adresa IXrouter-a na LAN. Default <b>192.168.0.100</b>.<br>"
          + "Iba z tejto IP bude povolené pripojenie na VNC port (kernel-level iptables).<br>"
          + "Zmeň len ak tvoja IXON inštalácia používa iný subnet.</html>";
    private static final String TIP_CUSTOMER_LABEL =
            "<html>Krátky identifikátor zákazníka alebo projektu (napr. <code>IKEA-TN-01</code>).<br>"
          + "Pridá sa do syslog <code>LOG_TAG</code> a audit logu — na fleet-wide <code>grep</code><br>"
          + "cez viacero robotov. Max 32 znakov: A-Z a-z 0-9 medzera hyphen podtržník.</html>";
    private static final String TIP_STRONG_PWD =
            "<html>Ak zaškrtnuté a root heslo robota je továrenský <code>easybot</code>, daemon<br>"
          + "sa odmietne spustiť. Odporúčané pre produkciu. Pre dev/test odškrtni, ale NIKDY<br>"
          + "nevystavuj taký robot na internet.</html>";
    private static final String TIP_TLS =
            "<html>Šifruje celú VNC komunikáciu self-signed TLS certifikátom cez <code>x11vnc -ssl SAVE</code>.<br>"
          + "Odporúčané pre každé nasadenie. Pri odškrtnutí ide traffic v plaintext — OK len pre LAN test.<br>"
          + "Niektoré staré VNC viewery (TightVNC 2.7) nepodporujú TLS — pre tie odškrtni.</html>";
    private static final String TIP_IDLE =
            "<html>Po koľkých minútach bez pohybu myši sa VNC session násilne odpojí.<br>"
          + "Bráni zabudnutým otvoreným oknám počas obeda/vikendu. <b>0</b> = vypnuté (žiadny kick).<br>"
          + "Rozsah 0-120 min, default 30.</html>";
    private static final String TIP_MAX_CLIENTS =
            "<html>Max. počet súčasne pripojených VNC klientov (1-5, default 1).<br>"
          + "Pre bežnú servisnú prevádzku stačí 1 — bráni tomu, aby sa 2 technici<br>"
          + "navzájom prepisovali. Zvýš len pri shadowing/training scenároch.</html>";

    // Guard so contribution-driven updateXxx() calls don't re-fire listeners
    private boolean updatingFromModel = false;

    @Override
    public void buildUI(JPanel panel, final VncInstallationNodeContribution contribution) {
        this.contribution = contribution;
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        panel.add(header("STIMBA VNC Server pre IXON Cloud"));
        panel.add(subtitle(
                "Spustí x11vnc naviazaný na Polyscope DISPLAY :0 a port 0.0.0.0:&lt;port&gt;. "
              + "Kernel-level iptables whitelist obmedzuje prístup len na IXrouter a 127.0.0.1. "
              + "IXrouter preposiela tento port cez IXON Cloud tunel — robot nie je nikdy "
              + "vystavený priamo do internetu ani do zákazníckej LAN-ky."));
        panel.add(separator());

        // Easybot banner (hidden by default — contribution shows it via updateEasybotBanner)
        easybotBanner = new JLabel(" ");
        easybotBanner.setVisible(false);
        easybotBanner.setOpaque(true);
        easybotBanner.setBackground(new Color(0xFFEBEE)); // red 50
        easybotBanner.setForeground(COLOR_FAIL);
        easybotBanner.setFont(easybotBanner.getFont().deriveFont(Font.BOLD, 13f));
        easybotBanner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(COLOR_FAIL, 1),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        easybotBanner.setAlignmentX(Component.LEFT_ALIGNMENT);
        easybotBanner.setMaximumSize(new Dimension(640, 36));
        panel.add(easybotBanner);

        // --- Core network fields ---
        panel.add(rowWithInfo("VNC port:",        buildPortField(),        TIP_PORT));
        panel.add(rowWithInfo("Heslo:",           buildPasswordField(),    TIP_PASSWORD));
        pwdStrengthLabel = new JLabel(" ");
        pwdStrengthLabel.setFont(pwdStrengthLabel.getFont().deriveFont(Font.PLAIN, 11f));
        panel.add(row("",                 pwdStrengthLabel));
        panel.add(rowWithInfo("",         buildViewOnlyCheckbox(),   TIP_VIEW_ONLY));
        panel.add(rowWithInfo("",         buildAutostartCheckbox(),  TIP_AUTOSTART));

        panel.add(separator());

        // --- Fleet identification fields (Sprint 1 A2 + A3) ---
        panel.add(rowWithInfo("IXrouter IP:",     buildIxrouterField(),      TIP_IXROUTER));
        panel.add(row("",                 ixrouterValidationLabel = feedbackLabel()));
        panel.add(rowWithInfo("Customer label:",  buildCustomerLabelField(), TIP_CUSTOMER_LABEL));
        panel.add(row("",                 customerValidationLabel = feedbackLabel()));

        panel.add(separator());

        // --- Security toggle (A5) ---
        panel.add(rowWithInfo("",                 buildStrongPwdCheckbox(),  TIP_STRONG_PWD));

        panel.add(separator());

        // --- Sprint 3 (v3.0.0): TLS + session limits (C1, C2, C4) ---
        panel.add(subtitle("<b>Šifrovanie a limity session-y (v3.0.0):</b>"));
        panel.add(buildTlsWarningBanner());
        panel.add(rowWithInfo("",                 buildTlsCheckbox(),        TIP_TLS));
        panel.add(row("",                         buildTlsFingerprintRow()));
        panel.add(rowWithInfo("Idle timeout (min):", buildIdleTimeoutField(),   TIP_IDLE));
        panel.add(rowWithInfo("Max klientov:",    buildMaxClientsField(),    TIP_MAX_CLIENTS));

        panel.add(separator());

        // --- Health panel (A4) ---
        panel.add(subtitle("<b>Stav systému (aktualizuje sa každých 5s):</b>"));
        panel.add(buildHealthPanel());

        panel.add(separator());
        stateLabel = boldLabel("UNKNOWN");
        panel.add(row("Stav démona:",     stateLabel));
        statusLabel = new JLabel(" ");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 12f));
        panel.add(row("",                 statusLabel));

        panel.add(separator());

        // --- Sprint 2 (v2.2.0) blocks --------------------------------------
        panel.add(subtitle("<b>Diagnostika &amp; test (v2.2.0):</b>"));
        panel.add(buildDiagRow());          // B2 + B6
        panel.add(Box.createRigidArea(new Dimension(0, 4)));
        panel.add(buildLogTailPanel());     // B1

        panel.add(separator());
        panel.add(subtitle("<b>Dočasná IP whitelist (ad-hoc vendor prístup):</b>"));
        panel.add(buildTempAllowlistPanel()); // B3

        panel.add(separator());
        panel.add(buildButtonRow());
    }

    // --- Update API (called by contribution on openView() and Timer tick) ---

    public void updatePort(int port) {
        updatingFromModel = true;
        try { if (portSpinner != null) portSpinner.setValue(port); }
        finally { updatingFromModel = false; }
    }

    public void updatePassword(String pw) {
        updatingFromModel = true;
        try { if (passwordField != null) passwordField.setText(pw == null ? "" : pw); }
        finally { updatingFromModel = false; }
    }

    public void updateViewOnly(boolean v) {
        updatingFromModel = true;
        try { if (viewOnlyBox != null) viewOnlyBox.setSelected(v); }
        finally { updatingFromModel = false; }
    }

    public void updateAutostart(boolean v) {
        updatingFromModel = true;
        try { if (autostartBox != null) autostartBox.setSelected(v); }
        finally { updatingFromModel = false; }
    }

    public void updateIxrouterIp(String ip) {
        updatingFromModel = true;
        try {
            if (ixrouterField != null) ixrouterField.setText(ip == null ? "" : ip);
            clearFeedback(ixrouterValidationLabel);
        } finally { updatingFromModel = false; }
    }

    public void updateCustomerLabel(String label) {
        updatingFromModel = true;
        try {
            if (customerLabelField != null) customerLabelField.setText(label == null ? "" : label);
            clearFeedback(customerValidationLabel);
        } finally { updatingFromModel = false; }
    }

    public void updateRequireStrongPwd(boolean v) {
        updatingFromModel = true;
        try { if (strongPwdBox != null) strongPwdBox.setSelected(v); }
        finally { updatingFromModel = false; }
    }

    public void updateDaemonState(String label) {
        if (stateLabel == null) return;
        String l = label == null ? "unknown" : label;
        stateLabel.setText(l);
        // Color-code: RUNNING green, STOPPED grey, else red
        if ("RUNNING".equals(l))      stateLabel.setForeground(COLOR_OK);
        else if ("STOPPED".equals(l)) stateLabel.setForeground(COLOR_UNKNOWN);
        else                          stateLabel.setForeground(COLOR_WARN);
    }

    public void updatePasswordStrength(String level) {
        if (pwdStrengthLabel == null) return;
        if (level == null) { pwdStrengthLabel.setText(" "); return; }
        String txt;
        Color  col;
        switch (level) {
            case "strong": txt = "Sila hesla (prvých 8 znakov): silné";   col = COLOR_OK;   break;
            case "medium": txt = "Sila hesla (prvých 8 znakov): stredné"; col = COLOR_WARN; break;
            default:       txt = "Sila hesla (prvých 8 znakov): slabé — pridaj veľké, číslice, znaky"; col = COLOR_FAIL; break;
        }
        pwdStrengthLabel.setText(txt);
        pwdStrengthLabel.setForeground(col);
    }

    public void updateHealth(Map<String, String> probes) {
        if (probes == null || probes.isEmpty()) {
            setDot(dotDaemon,   "unknown");
            setDot(dotIptables, "unknown");
            setDot(dotPort,     "unknown");
            setDot(dotDisplay,  "unknown");
            setDot(dotIxrouter, "unknown");
            return;
        }
        setDot(dotDaemon,   probes.getOrDefault("daemon",   "unknown"));
        setDot(dotIptables, probes.getOrDefault("iptables", "unknown"));
        setDot(dotPort,     probes.getOrDefault("port",     "unknown"));
        setDot(dotDisplay,  probes.getOrDefault("display",  "unknown"));
        setDot(dotIxrouter, probes.getOrDefault("ixrouter", "unknown"));

        // Easybot banner visibility hint: if daemon is "fail" AND probes has "error" key,
        // we can't distinguish tripwire from generic failure — leave banner control to
        // explicit contribution.updateEasybotBanner(true) callback in Sprint 2.
    }

    public void updateStatus(String msg, boolean error) {
        if (statusLabel == null) return;
        if (msg == null) { statusLabel.setText(" "); return; }
        statusLabel.setText(msg);
        statusLabel.setForeground(error ? COLOR_FAIL : COLOR_OK);
    }

    public void updateEasybotBanner(boolean show) {
        if (easybotBanner == null) return;
        if (show) {
            easybotBanner.setText("<html>⚠ VAROVANIE: root password je továrenský 'easybot'. "
                    + "Zmeň cez <code>ssh root@robot passwd root</code> pred produkčným nasadením.</html>");
            easybotBanner.setVisible(true);
        } else {
            easybotBanner.setVisible(false);
        }
    }

    // --- Sprint 3 (v3.0.0) update API ------------------------------------

    public void updateTlsEnabled(boolean v) {
        updatingFromModel = true;
        try {
            if (tlsEnabledBox != null)    tlsEnabledBox.setSelected(v);
            if (tlsWarningBanner != null) tlsWarningBanner.setVisible(!v);
        } finally { updatingFromModel = false; }
    }

    public void updateIdleTimeoutMin(int min) {
        updatingFromModel = true;
        try { if (idleTimeoutSpinner != null) idleTimeoutSpinner.setValue(min); }
        finally { updatingFromModel = false; }
    }

    public void updateMaxClients(int n) {
        updatingFromModel = true;
        try { if (maxClientsSpinner != null) maxClientsSpinner.setValue(n); }
        finally { updatingFromModel = false; }
    }

    /** Show the first line of /root/.vnc/certs/fingerprint.txt (or placeholder) inline. */
    public void updateTlsFingerprintLabel(String fp) {
        if (tlsFingerprintLabel == null) return;
        if (fp == null || fp.isEmpty()) {
            tlsFingerprintLabel.setText(" ");
        } else if (fp.startsWith("(")) {
            // placeholder: grey
            tlsFingerprintLabel.setText(fp);
            tlsFingerprintLabel.setForeground(COLOR_UNKNOWN);
        } else {
            // truncate to first 23 chars (SHA256 Fingerprint=AB:CD:EF:...) + ellipsis
            String shown = fp.length() > 34 ? fp.substring(0, 34) + "…" : fp;
            tlsFingerprintLabel.setText(shown);
            tlsFingerprintLabel.setForeground(COLOR_OK);
        }
    }

    // --- Component builders ---------------------------------------------------

    private Component buildPortField() {
        portSpinner = new JSpinner(new SpinnerNumberModel(5900, 1, 65535, 1));
        portSpinner.setPreferredSize(new Dimension(120, 32));
        portSpinner.setMaximumSize(new Dimension(120, 32));
        portSpinner.addChangeListener(new ChangeListener() {
            @Override
            public void stateChanged(ChangeEvent e) {
                if (updatingFromModel) return;
                Object v = portSpinner.getValue();
                if (v instanceof Integer) contribution.setPort(((Integer) v).intValue());
            }
        });
        return portSpinner;
    }

    private Component buildPasswordField() {
        passwordField = new JPasswordField(16);
        passwordField.setPreferredSize(new Dimension(240, 32));
        passwordField.setMaximumSize(new Dimension(240, 32));
        // Commit on Enter
        passwordField.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (updatingFromModel) return;
                String pw = new String(passwordField.getPassword());
                contribution.setPassword(pw);
                updatePasswordStrength(VncInstallationNodeContribution.estimatePasswordStrength(pw));
            }
        });
        // Commit on focus lost
        passwordField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                if (updatingFromModel) return;
                String pw = new String(passwordField.getPassword());
                contribution.setPassword(pw);
                updatePasswordStrength(VncInstallationNodeContribution.estimatePasswordStrength(pw));
            }
        });
        // Live strength indicator as user types
        passwordField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                if (updatingFromModel) return;
                String pw = new String(passwordField.getPassword());
                updatePasswordStrength(VncInstallationNodeContribution.estimatePasswordStrength(pw));
            }
        });
        return passwordField;
    }

    private Component buildViewOnlyCheckbox() {
        viewOnlyBox = new JCheckBox("View-only (vzdialený klient nemôže klikať ani písať)");
        viewOnlyBox.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (updatingFromModel) return;
                contribution.setViewOnly(viewOnlyBox.isSelected());
            }
        });
        return viewOnlyBox;
    }

    private Component buildAutostartCheckbox() {
        autostartBox = new JCheckBox("Spustiť automaticky po štarte Polyscope");
        autostartBox.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (updatingFromModel) return;
                contribution.setAutostart(autostartBox.isSelected());
            }
        });
        return autostartBox;
    }

    private Component buildIxrouterField() {
        ixrouterField = new JTextField(18);
        ixrouterField.setPreferredSize(new Dimension(240, 32));
        ixrouterField.setMaximumSize(new Dimension(240, 32));
        ixrouterField.setToolTipText("IPv4 adresa IXrouter-a na LAN-ke (default 192.168.0.100). "
                + "Iba z tejto IP bude povolené pripojenie na VNC port.");
        ixrouterField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                if (updatingFromModel) return;
                commitIxrouter();
            }
        });
        ixrouterField.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (updatingFromModel) return;
                commitIxrouter();
            }
        });
        return ixrouterField;
    }

    private void commitIxrouter() {
        String v = ixrouterField.getText();
        boolean ok = contribution.setIxrouterIp(v);
        if (ok) {
            ixrouterValidationLabel.setText("✓ OK");
            ixrouterValidationLabel.setForeground(COLOR_OK);
        } else {
            ixrouterValidationLabel.setText("✗ Neplatná IPv4 adresa (napr. 192.168.0.100)");
            ixrouterValidationLabel.setForeground(COLOR_FAIL);
        }
    }

    private Component buildCustomerLabelField() {
        customerLabelField = new JTextField(24);
        customerLabelField.setPreferredSize(new Dimension(240, 32));
        customerLabelField.setMaximumSize(new Dimension(240, 32));
        customerLabelField.setToolTipText("Krátky identifikátor zákazníka/robota. "
                + "Pridá sa do syslog LOG_TAG a audit logu — na fleet-wide grep. "
                + "Max 32 znakov: A-Z a-z 0-9 medzera hyphen podtržník.");
        customerLabelField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                if (updatingFromModel) return;
                commitCustomerLabel();
            }
        });
        customerLabelField.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (updatingFromModel) return;
                commitCustomerLabel();
            }
        });
        return customerLabelField;
    }

    private void commitCustomerLabel() {
        String v = customerLabelField.getText();
        boolean ok = contribution.setCustomerLabel(v);
        if (ok) {
            customerValidationLabel.setText(v.isEmpty() ? " " : "✓ OK");
            customerValidationLabel.setForeground(COLOR_OK);
        } else {
            customerValidationLabel.setText("✗ Max 32 znakov: A-Z 0-9 space - _");
            customerValidationLabel.setForeground(COLOR_FAIL);
        }
    }

    private Component buildStrongPwdCheckbox() {
        strongPwdBox = new JCheckBox("Vyžadovať silné root heslo (easybot tripwire — odporúčané)");
        strongPwdBox.setToolTipText("Ak je zaškrtnuté a root heslo je továrenský 'easybot', "
                + "daemon sa nespustí. Pre dev/test môžeš odškrtnúť — pre produkciu NEODPORÚČANÉ.");
        strongPwdBox.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (updatingFromModel) return;
                contribution.setRequireStrongPwd(strongPwdBox.isSelected());
            }
        });
        return strongPwdBox;
    }

    private JPanel buildHealthPanel() {
        JPanel grid = new JPanel();
        grid.setLayout(new BoxLayout(grid, BoxLayout.Y_AXIS));
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);

        dotDaemon   = makeDot("unknown");
        dotIptables = makeDot("unknown");
        dotPort     = makeDot("unknown");
        dotDisplay  = makeDot("unknown");
        dotIxrouter = makeDot("unknown");

        grid.add(healthRow(dotDaemon,   "Daemon x11vnc beží"));
        grid.add(healthRow(dotIptables, "iptables whitelist aktívna (môže byť 'unknown' z UI — check vyžaduje root)"));
        grid.add(healthRow(dotPort,     "Port VNC počúva (ss -tln)"));
        grid.add(healthRow(dotDisplay,  "DISPLAY :0 dostupný (xdpyinfo)"));
        grid.add(healthRow(dotIxrouter, "IXrouter odpovedá na ping"));

        return grid;
    }

    private JPanel healthRow(JLabel dot, String text) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(dot);
        row.add(Box.createRigidArea(new Dimension(8, 0)));
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.PLAIN, 12f));
        row.add(l);
        row.add(Box.createHorizontalGlue());
        return row;
    }

    private JLabel makeDot(String state) {
        JLabel l = new JLabel("●", SwingConstants.CENTER);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 18f));
        l.setPreferredSize(new Dimension(18, 18));
        setDot(l, state);
        return l;
    }

    private void setDot(JLabel dot, String state) {
        if (dot == null) return;
        switch (state) {
            case "ok":      dot.setForeground(COLOR_OK);      dot.setToolTipText("OK");      break;
            case "warning": dot.setForeground(COLOR_WARN);    dot.setToolTipText("warning"); break;
            case "fail":    dot.setForeground(COLOR_FAIL);    dot.setToolTipText("fail");    break;
            default:        dot.setForeground(COLOR_UNKNOWN); dot.setToolTipText("unknown"); break;
        }
    }

    // --- Sprint 3 (v3.0.0) builders ---------------------------------------

    /** C1 — TLS checkbox. Toggling updates both contribution and warning banner live. */
    private Component buildTlsCheckbox() {
        tlsEnabledBox = new JCheckBox("TLS šifrovanie (x11vnc -ssl SAVE, self-signed cert)");
        tlsEnabledBox.setToolTipText("Zapnuté = TLS-wrapped VNC session. Cert sa generuje pri prvom štarte.");
        tlsEnabledBox.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (updatingFromModel) return;
                boolean on = tlsEnabledBox.isSelected();
                contribution.setTlsEnabled(on);
                if (tlsWarningBanner != null) tlsWarningBanner.setVisible(!on);
            }
        });
        return tlsEnabledBox;
    }

    /** C1 — red banner shown only when TLS is OFF. */
    private Component buildTlsWarningBanner() {
        tlsWarningBanner = new JLabel(
                "<html>⚠ <b>VNC komunikácia je v plaintext-e.</b> Heslá, screen-capture aj keyboard eventy "
              + "sú viditeľné pre každého, kto má prístup na LAN medzi robotom a IXrouter-om. "
              + "Zapni <b>TLS šifrovanie</b> vyššie okrem prípadu, keď klient (napr. TightVNC 2.7) TLS nepodporuje.</html>");
        tlsWarningBanner.setOpaque(true);
        tlsWarningBanner.setBackground(new Color(0xFFEBEE)); // red 50
        tlsWarningBanner.setForeground(COLOR_FAIL);
        tlsWarningBanner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(COLOR_FAIL, 1),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        tlsWarningBanner.setAlignmentX(Component.LEFT_ALIGNMENT);
        tlsWarningBanner.setMaximumSize(new Dimension(640, 60));
        tlsWarningBanner.setVisible(false); // shown only when TLS off
        return tlsWarningBanner;
    }

    /** C1 — inline "Zobraziť cert fingerprint" button + truncated preview label. */
    private Component buildTlsFingerprintRow() {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        tlsFingerprintBtn = new JButton("Zobraziť cert fingerprint");
        tlsFingerprintBtn.setToolTipText("Zobrazí SHA-256 fingerprint self-signed certifikátu — pin-ni ho v klientovi.");
        tlsFingerprintBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) { showTlsFingerprintDialog(); }
        });
        row.add(tlsFingerprintBtn);

        row.add(Box.createRigidArea(new Dimension(10, 0)));

        tlsFingerprintLabel = new JLabel(" ");
        tlsFingerprintLabel.setFont(new Font("Monospaced", Font.PLAIN, 11));
        tlsFingerprintLabel.setForeground(COLOR_UNKNOWN);
        row.add(tlsFingerprintLabel);

        row.add(Box.createHorizontalGlue());
        return row;
    }

    private void showTlsFingerprintDialog() {
        final String fp = contribution.getTlsFingerprint();
        String msg;
        if (fp == null || fp.startsWith("(")) {
            msg = "<html><b>Fingerprint zatiaľ nie je k dispozícii.</b><br><br>"
                + "Súbor <code>/root/.vnc/certs/fingerprint.txt</code> sa vytvorí pri prvom<br>"
                + "štarte daemona s TLS_ENABLED=1 (post-install.sh to robí eagerly už pri<br>"
                + "inštalácii URCap-u, pokiaľ je dostupný <code>openssl</code>).<br><br>"
                + "Status: " + (fp == null ? "neznámy" : fp) + "</html>";
        } else {
            msg = "<html><b>SHA-256 fingerprint self-signed certifikátu:</b><br><br>"
                + "<code style='font-size:11pt'>" + fp + "</code><br><br>"
                + "Pin-ni tento fingerprint v <i>RealVNC Viewer</i> / <i>TigerVNC</i> klientovi pri prvom<br>"
                + "pripojení. Keď sa cert zmení (napr. po <code>rm server.pem</code> + regen), fingerprint<br>"
                + "sa zmení a klient ťa upozorní — ak regen si neurobil ty, je to potenciálny MITM.<br><br>"
                + "Cert žije v <code>/root/.vnc/certs/server.pem</code> (10-ročná platnosť).</html>";
        }
        JOptionPane.showMessageDialog(
                findParentWindow(),
                msg,
                "TLS cert fingerprint",
                JOptionPane.INFORMATION_MESSAGE);
    }

    /** C2 — idle timeout JSpinner, 0..120 min. Initial value is re-set by updateIdleTimeoutMin() on openView(). */
    private Component buildIdleTimeoutField() {
        idleTimeoutSpinner = new JSpinner(new SpinnerNumberModel(
                30,                                                            // initial (will be overwritten)
                VncInstallationNodeContribution.IDLE_TIMEOUT_MIN_MIN,
                VncInstallationNodeContribution.IDLE_TIMEOUT_MIN_MAX,
                5));
        idleTimeoutSpinner.setPreferredSize(new Dimension(100, 32));
        idleTimeoutSpinner.setMaximumSize(new Dimension(100, 32));
        idleTimeoutSpinner.addChangeListener(new ChangeListener() {
            @Override
            public void stateChanged(ChangeEvent e) {
                if (updatingFromModel) return;
                Object v = idleTimeoutSpinner.getValue();
                if (v instanceof Integer) {
                    int m = ((Integer) v).intValue();
                    if (!contribution.setIdleTimeoutMin(m)) {
                        updateStatus("✗ Idle timeout mimo rozsahu 0-120 min.", true);
                    }
                }
            }
        });
        return idleTimeoutSpinner;
    }

    /** C4 — max-clients JSpinner, 1..5. */
    private Component buildMaxClientsField() {
        maxClientsSpinner = new JSpinner(new SpinnerNumberModel(
                VncInstallationNodeContribution.MAX_CLIENTS_MIN,
                VncInstallationNodeContribution.MAX_CLIENTS_MIN,
                VncInstallationNodeContribution.MAX_CLIENTS_MAX,
                1));
        maxClientsSpinner.setPreferredSize(new Dimension(60, 32));
        maxClientsSpinner.setMaximumSize(new Dimension(60, 32));
        maxClientsSpinner.addChangeListener(new ChangeListener() {
            @Override
            public void stateChanged(ChangeEvent e) {
                if (updatingFromModel) return;
                Object v = maxClientsSpinner.getValue();
                if (v instanceof Integer) {
                    int n = ((Integer) v).intValue();
                    if (!contribution.setMaxClients(n)) {
                        updateStatus("✗ Max klientov mimo rozsahu 1-5.", true);
                    }
                }
            }
        });
        return maxClientsSpinner;
    }

    // --- Sprint 2 (v2.2.0) builders ---------------------------------------

    /** B2 (export diag) + B6 (test connection) button row. */
    private JPanel buildDiagRow() {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton diagBtn = new JButton("Exportovať diagnostiku");
        diagBtn.setToolTipText("Vytvorí /root/urcap-vnc-diag-*.tar.gz s logmi (redacted), "
                + "iptables-save, ss, ps, health-probe, MANIFEST a /var/lib/urcap-vnc.");
        diagBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                diagBtn.setEnabled(false);
                updateStatus("Generujem diagnostický tarball (2-4s)…", false);
                new SwingWorker<String, Void>() {
                    @Override
                    protected String doInBackground() { return contribution.runDiagBundle(); }
                    @Override
                    protected void done() {
                        diagBtn.setEnabled(true);
                        try {
                            String res = get();
                            if (res != null && res.startsWith("ERROR:")) {
                                updateStatus("✗ " + res, true);
                            } else {
                                updateStatus("✓ Diag bundle: " + res, false);
                                JOptionPane.showMessageDialog(
                                        findParentWindow(),
                                        "<html>Diagnostika uložená:<br><b>" + res + "</b><br><br>"
                                                + "Stiahni cez SSH:<br>"
                                                + "<code>scp root@robot:" + res + " ./</code></html>",
                                        "Diagnostika exportovaná",
                                        JOptionPane.INFORMATION_MESSAGE);
                            }
                        } catch (Exception ex) {
                            updateStatus("✗ " + ex.getMessage(), true);
                        }
                    }
                }.execute();
            }
        });
        row.add(diagBtn);

        row.add(Box.createRigidArea(new Dimension(8, 0)));

        JButton testBtn = new JButton("Test spojenia");
        testBtn.setToolTipText("RFB handshake probe na 127.0.0.1:<port>. "
                + "Overí že daemon naozaj odpovedá VNC protokolom.");
        testConnResultLabel = new JLabel(" ");
        testConnResultLabel.setFont(testConnResultLabel.getFont().deriveFont(Font.PLAIN, 12f));
        testBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                testBtn.setEnabled(false);
                testConnResultLabel.setText("Testujem…");
                testConnResultLabel.setForeground(COLOR_UNKNOWN);
                new SwingWorker<String, Void>() {
                    @Override
                    protected String doInBackground() { return contribution.testConnection(); }
                    @Override
                    protected void done() {
                        testBtn.setEnabled(true);
                        try {
                            String res = get();
                            testConnResultLabel.setText(res);
                            testConnResultLabel.setForeground(
                                    res != null && res.startsWith("✓") ? COLOR_OK : COLOR_FAIL);
                        } catch (Exception ex) {
                            testConnResultLabel.setText("✗ " + ex.getMessage());
                            testConnResultLabel.setForeground(COLOR_FAIL);
                        }
                    }
                }.execute();
            }
        });
        row.add(testBtn);

        row.add(Box.createRigidArea(new Dimension(12, 0)));
        row.add(testConnResultLabel);
        row.add(Box.createHorizontalGlue());

        return row;
    }

    /** B1 — live log tail panel with auto-refresh toggle. */
    private JPanel buildLogTailPanel() {
        JPanel wrap = new JPanel();
        wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));
        wrap.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Header row with checkbox + manual refresh
        JPanel hdr = new JPanel();
        hdr.setLayout(new BoxLayout(hdr, BoxLayout.X_AXIS));
        hdr.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel hdrLbl = new JLabel("Log tail (/var/log/urcap-vnc.log, posledných 20 riadkov):");
        hdrLbl.setFont(hdrLbl.getFont().deriveFont(Font.PLAIN, 12f));
        hdr.add(hdrLbl);
        hdr.add(Box.createRigidArea(new Dimension(12, 0)));
        logAutoRefreshBox = new JCheckBox("Auto-refresh (3s)");
        logAutoRefreshBox.setSelected(false);
        logAutoRefreshBox.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (logAutoRefreshBox.isSelected()) startLogTimer();
                else                                stopLogTimer();
            }
        });
        hdr.add(logAutoRefreshBox);
        hdr.add(Box.createRigidArea(new Dimension(8, 0)));
        logRefreshBtn = new JButton("Refresh");
        logRefreshBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) { refreshLogTail(); }
        });
        hdr.add(logRefreshBtn);
        hdr.add(Box.createHorizontalGlue());
        wrap.add(hdr);

        logTailArea = new JTextArea(8, 70);
        logTailArea.setEditable(false);
        logTailArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        logTailArea.setBackground(new Color(0xFAFAFA));
        logTailArea.setText("(log prázdny alebo sa ešte nenačítal — stlač Refresh)");
        JScrollPane sp = new JScrollPane(logTailArea,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        sp.setPreferredSize(new Dimension(640, 140));
        sp.setMaximumSize(new Dimension(640, 140));
        sp.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrap.add(sp);

        return wrap;
    }

    /** B3 — live table of active temp allowlist entries + "Pridať" button. */
    private JPanel buildTempAllowlistPanel() {
        JPanel wrap = new JPanel();
        wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));
        wrap.setAlignmentX(Component.LEFT_ALIGNMENT);

        tempAllowModel = new DefaultTableModel(
                new Object[] { "IP adresa", "Zostáva (min)", "Popis" }, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        tempAllowTable = new JTable(tempAllowModel);
        tempAllowTable.setFillsViewportHeight(true);
        tempAllowTable.setRowHeight(22);
        tempAllowTable.getColumnModel().getColumn(0).setPreferredWidth(140);
        tempAllowTable.getColumnModel().getColumn(1).setPreferredWidth(100);
        tempAllowTable.getColumnModel().getColumn(2).setPreferredWidth(340);
        JScrollPane sp = new JScrollPane(tempAllowTable);
        sp.setPreferredSize(new Dimension(640, 100));
        sp.setMaximumSize(new Dimension(640, 100));
        sp.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrap.add(sp);

        JPanel btnRow = new JPanel();
        btnRow.setLayout(new BoxLayout(btnRow, BoxLayout.X_AXIS));
        btnRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton addBtn = new JButton("+ Dočasná IP");
        addBtn.setToolTipText("Dočasne povolí VNC port pre ad-hoc IP (napr. servisný technik). "
                + "Automaticky sa odstráni po uplynutí TTL.");
        addBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) { showAddTempAllowDialog(); }
        });
        btnRow.add(addBtn);

        btnRow.add(Box.createRigidArea(new Dimension(8, 0)));

        JButton refreshBtn = new JButton("Obnoviť zoznam");
        refreshBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) { refreshTempAllowlist(); }
        });
        btnRow.add(refreshBtn);
        btnRow.add(Box.createHorizontalGlue());
        wrap.add(Box.createRigidArea(new Dimension(0, 4)));
        wrap.add(btnRow);

        return wrap;
    }

    private void showAddTempAllowDialog() {
        final JTextField ipField = new JTextField(16);
        final JTextField commentField = new JTextField(32);
        final ButtonGroup ttlGroup = new ButtonGroup();
        final JRadioButton ttl15 = new JRadioButton("15 min", true);
        final JRadioButton ttl30 = new JRadioButton("30 min");
        final JRadioButton ttl60 = new JRadioButton("60 min");
        ttlGroup.add(ttl15); ttlGroup.add(ttl30); ttlGroup.add(ttl60);

        JPanel ttlRow = new JPanel();
        ttlRow.setLayout(new BoxLayout(ttlRow, BoxLayout.X_AXIS));
        ttlRow.add(ttl15);
        ttlRow.add(ttl30);
        ttlRow.add(ttl60);

        Object[] fields = {
                "IP adresa vendora (IPv4):", ipField,
                "Dĺžka prístupu:", ttlRow,
                "Popis (napr. 'KUKA servis ticket #2342'):", commentField
        };
        int rc = JOptionPane.showConfirmDialog(
                findParentWindow(), fields, "Pridať dočasnú IP",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (rc != JOptionPane.OK_OPTION) return;

        int ttl = ttl60.isSelected() ? VncInstallationNodeContribution.TTL_60_MIN
                 : ttl30.isSelected() ? VncInstallationNodeContribution.TTL_30_MIN
                 :                      VncInstallationNodeContribution.TTL_15_MIN;
        final String ip      = ipField.getText().trim();
        final String comment = commentField.getText().trim();
        final int    ttlSec  = ttl;

        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                return contribution.addTempAllowlist(ip, ttlSec, comment);
            }
            @Override
            protected void done() {
                try {
                    String err = get();
                    if (err == null) {
                        updateStatus("✓ Dočasná IP " + ip + " povolená na " + (ttlSec / 60) + " min.", false);
                        refreshTempAllowlist();
                    } else {
                        updateStatus("✗ " + err, true);
                    }
                } catch (Exception ex) {
                    updateStatus("✗ " + ex.getMessage(), true);
                }
            }
        }.execute();
    }

    private void refreshLogTail() {
        if (logTailArea == null) return;
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() { return contribution.tailLog(20); }
            @Override
            protected void done() {
                try {
                    List<String> lines = get();
                    if (lines == null || lines.isEmpty()) {
                        logTailArea.setText("(log prázdny alebo /var/log/urcap-vnc.log ešte nebol vytvorený)");
                        return;
                    }
                    StringBuilder sb = new StringBuilder();
                    for (String l : lines) { sb.append(l).append('\n'); }
                    logTailArea.setText(sb.toString());
                    logTailArea.setCaretPosition(logTailArea.getDocument().getLength());
                } catch (Exception ex) {
                    logTailArea.setText("[refresh error] " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void refreshTempAllowlist() {
        if (tempAllowModel == null) return;
        new SwingWorker<List<String[]>, Void>() {
            @Override
            protected List<String[]> doInBackground() { return contribution.listTempAllowlist(); }
            @Override
            protected void done() {
                try {
                    List<String[]> rows = get();
                    tempAllowModel.setRowCount(0);
                    if (rows == null || rows.isEmpty()) return;
                    for (String[] r : rows) {
                        // r = [ip, expiry, remainSec, comment]
                        long remainSec = 0;
                        try { remainSec = Long.parseLong(r[2]); } catch (NumberFormatException ignored) { /* ok */ }
                        long remainMin = (remainSec + 59) / 60;
                        tempAllowModel.addRow(new Object[] { r[0], remainMin, r[3] });
                    }
                } catch (Exception ignored) { /* silent — no row = no rules */ }
            }
        }.execute();
    }

    private void startLogTimer() {
        stopLogTimer();
        logTimer = new Timer(true);
        logTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() { refreshLogTail(); }
                });
            }
        }, 0, 3000);
    }

    private void stopLogTimer() {
        if (logTimer != null) { logTimer.cancel(); logTimer = null; }
    }

    /** Called by the contribution on openView() to start temp-allow refresh. */
    public void startTempAllowlistTimer() {
        if (tempAllowTimer != null) return;
        tempAllowTimer = new Timer(true);
        tempAllowTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() { refreshTempAllowlist(); }
                });
            }
        }, 0, 10_000);
    }

    public void stopSprint2Timers() {
        stopLogTimer();
        if (tempAllowTimer != null) { tempAllowTimer.cancel(); tempAllowTimer = null; }
    }

    /** Best-effort parent window lookup for modal dialogs. */
    private Window findParentWindow() {
        if (logTailArea == null) return null;
        return SwingUtilities.getWindowAncestor(logTailArea);
    }

    private JPanel buildButtonRow() {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.add(Box.createHorizontalGlue());

        JButton apply = new JButton("Apply");
        apply.setToolTipText("Atomicky zapíše /var/lib/urcap-vnc/config a reštartuje daemon.");
        apply.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // Flush any pending password edit
                String pw = new String(passwordField.getPassword());
                contribution.setPassword(pw);
                String err = contribution.applyConfig();
                if (err == null) updateStatus("✓ Config zapísaný + daemon reštartnutý.", false);
                else             updateStatus("✗ " + err, true);
            }
        });
        row.add(apply);

        row.add(Box.createRigidArea(new Dimension(8, 0)));

        JButton start = new JButton("Start");
        start.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                contribution.setPassword(new String(passwordField.getPassword()));
                contribution.startDaemon();
            }
        });
        row.add(start);

        row.add(Box.createRigidArea(new Dimension(8, 0)));

        JButton stop = new JButton("Stop");
        stop.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                contribution.stopDaemon();
            }
        });
        row.add(stop);

        row.add(Box.createHorizontalGlue());
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        return row;
    }

    // --- Layout helpers -------------------------------------------------------

    private JPanel row(String label, Component c) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        JLabel l = new JLabel(label);
        l.setPreferredSize(new Dimension(160, 32));
        row.add(l);
        row.add(c);
        row.add(Box.createHorizontalGlue());
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        return row;
    }

    /**
     * C7 (Sprint 3) — same as row(), but appends a (?) info button
     * that shows an HTML modal with the given tooltip text.
     *
     * The button is also wired to the widget's setToolTipText so hover works
     * as a second channel (for operators who don't notice the button).
     */
    private JPanel rowWithInfo(String label, Component c, String html) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        JLabel l = new JLabel(label);
        l.setPreferredSize(new Dimension(160, 32));
        row.add(l);
        row.add(c);
        row.add(Box.createRigidArea(new Dimension(6, 0)));
        row.add(infoButton(html));
        row.add(Box.createHorizontalGlue());
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        return row;
    }

    /**
     * C7 — small round "(?)" button. Click → modal with the {@code html}
     * tooltip text. Also assigns the same text as a hover tooltip.
     */
    private JButton infoButton(final String html) {
        final JButton b = new JButton("?");
        b.setFont(b.getFont().deriveFont(Font.BOLD, 11f));
        b.setMargin(new java.awt.Insets(0, 4, 0, 4));
        b.setPreferredSize(new Dimension(22, 22));
        b.setMaximumSize(new Dimension(22, 22));
        b.setFocusable(false);
        b.setToolTipText("Klikni pre vysvetlenie");
        b.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                JOptionPane.showMessageDialog(
                        findParentWindow(),
                        html,
                        "Vysvetlivka",
                        JOptionPane.INFORMATION_MESSAGE);
            }
        });
        return b;
    }

    private JLabel header(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 16f));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private JLabel subtitle(String text) {
        JLabel l = new JLabel("<html><body style='width:520px'>" + text + "</body></html>");
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private JLabel boldLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }

    private JLabel feedbackLabel() {
        JLabel l = new JLabel(" ");
        l.setFont(l.getFont().deriveFont(Font.PLAIN, 11f));
        return l;
    }

    private void clearFeedback(JLabel l) {
        if (l != null) { l.setText(" "); l.setForeground(COLOR_UNKNOWN); }
    }

    private JSeparator separator() {
        JSeparator s = new JSeparator();
        s.setMaximumSize(new Dimension(640, 4));
        s.setAlignmentX(Component.LEFT_ALIGNMENT);
        return s;
    }
}
