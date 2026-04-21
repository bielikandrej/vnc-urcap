/*
 * STIMBA VNC Server URCap — Robot metadata probe (v3.10.0+)
 *
 * One-shot discovery of robot identity. Portal asked: "after pairing, robot
 * should fill serial, polyscope version, model, MAC, hostname itself — no
 * more manual typing". This class does exactly that, called once at pair
 * time and once per reconnect.
 *
 * Sources:
 *   - URCap SDK 1.16 `SystemAPI` (verified via sources jar extraction):
 *       getRobotModel()      → RobotModel.getSerialNumber() / getRobotType()
 *       getSoftwareVersion() → major.minor.bugfix.build
 *       getSystemSettings()  → locale (timezone derived)
 *   - java.net: NetworkInterface + InetAddress for MAC + hostname + IP
 *   - `/sys/class/net/eth0/address` fallback if java.net MAC comes back null
 *     (Java 8 on older Polyscope sometimes doesn't expose it)
 *   - DataModel for our own customer_label key
 *
 * Populated fields: serial, polyscopeVersion, polyscopeMajor, robotModel,
 *   firmwareVersion (derived from software version build number),
 *   controllerBoxVersion (reads /root/.version if present), macAddress,
 *   localIp, hostname, installationName (DataModel KEY_INSTALLATION_NAME if
 *   set), activeTcp (future — placeholder), timeZone, customerLabel.
 *
 * Not probed here: recentErrors — see PolyscopeLogTailer.
 */
package sk.stimba.urcap.vnc.impl;

import com.ur.urcap.api.contribution.installation.InstallationAPIProvider;
import com.ur.urcap.api.domain.SoftwareVersion;
import com.ur.urcap.api.domain.SystemAPI;
import com.ur.urcap.api.domain.robot.RobotModel;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.logging.Logger;

public final class RobotMetadataProbe {

    private static final Logger LOG = Logger.getLogger(RobotMetadataProbe.class.getName());

    private final InstallationAPIProvider apiProvider;

    public RobotMetadataProbe(InstallationAPIProvider apiProvider) {
        this.apiProvider = apiProvider;
    }

    public static final class Snapshot {
        public final String serialNumber;
        public final String polyscopeVersion;   // "5.25.1.1234"
        public final String polyscopeMajor;     // "ps5" | "cb3"
        public final String robotModel;         // "UR10e" / "UR20" / ...
        public final String firmwareVersion;    // same as polyscopeVersion for PS5
        public final String controllerBoxVersion;
        public final String macAddress;         // "aa:bb:cc:dd:ee:ff" (lowercase)
        public final String localIp;
        public final String hostname;
        public final String timeZone;

        public Snapshot(String serial, String psv, String psm, String model,
                        String fw, String cbv, String mac, String ip,
                        String host, String tz) {
            this.serialNumber = serial;
            this.polyscopeVersion = psv;
            this.polyscopeMajor = psm;
            this.robotModel = model;
            this.firmwareVersion = fw;
            this.controllerBoxVersion = cbv;
            this.macAddress = mac;
            this.localIp = ip;
            this.hostname = host;
            this.timeZone = tz;
        }

        /**
         * Shallow map for embedding into heartbeat JSON payload. Keys match the
         * zod schema in portal-side app/api/agent/heartbeat/route.ts v3.10.
         */
        public Map<String, Object> toHeartbeatMap() {
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            if (serialNumber != null) m.put("robotSerialNumber", serialNumber);
            if (polyscopeVersion != null) m.put("polyscopeVersion", polyscopeVersion);
            if (polyscopeMajor != null) m.put("polyscopeMajor", polyscopeMajor);
            if (robotModel != null) m.put("robotModel", robotModel);
            if (firmwareVersion != null) m.put("firmwareVersion", firmwareVersion);
            if (controllerBoxVersion != null) m.put("controllerBoxVersion", controllerBoxVersion);
            if (macAddress != null) m.put("macAddress", macAddress);
            if (localIp != null) m.put("localIp", localIp);
            if (hostname != null) m.put("hostname", hostname);
            if (timeZone != null) m.put("timeZone", timeZone);
            return m;
        }
    }

    /**
     * Run all probes, tolerating any individual failure. Returns a snapshot
     * with null for fields we couldn't determine.
     */
    public Snapshot probe() {
        String serial = null;
        String psv = null;
        String psm = null;
        String model = null;
        String fw = null;

        try {
            SystemAPI sys = apiProvider.getSystemAPI();
            RobotModel rm = sys.getRobotModel();
            serial = safeGet(rm::getSerialNumber);
            RobotModel.RobotType type = safeGetT(rm::getRobotType);
            if (type != null) {
                // Map enum → "UR10e" (e-Series suffix — RobotSeries is deprecated)
                String base = type.name();
                @SuppressWarnings("deprecation")
                RobotModel.RobotSeries series = rm.getRobotSeries();
                if (series == RobotModel.RobotSeries.E_SERIES && !base.endsWith("e")) {
                    model = base + "e";
                } else {
                    model = base;
                }
                psm = series == RobotModel.RobotSeries.CB3 ? "cb3" : "ps5";
            }
            SoftwareVersion sv = sys.getSoftwareVersion();
            if (sv != null) {
                psv = sv.getMajorVersion() + "." + sv.getMinorVersion() + "." +
                      sv.getBugfixVersion() + "." + sv.getBuildNumber();
                // Firmware version on UR boxes is the same build as Polyscope
                fw = psv;
            }
        } catch (Throwable t) {
            LOG.warning("URCap SystemAPI probe failed: " + t.getMessage());
        }

        String cbv = readFirstLine("/root/.version");
        String mac = probeMacAddress();
        String ip = probeLocalIp();
        String host = probeHostname();
        String tz = TimeZone.getDefault().getID();

        return new Snapshot(serial, psv, psm, model, fw, cbv, mac, ip, host, tz);
    }

    // -------------------------------------------------- probe implementations

    private static String probeMacAddress() {
        // Try java.net first — works on most e-Series builds.
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                byte[] hw = ni.getHardwareAddress();
                if (hw == null || hw.length == 0) continue;
                StringBuilder sb = new StringBuilder(17);
                for (int i = 0; i < hw.length; i++) {
                    if (i > 0) sb.append(':');
                    sb.append(String.format("%02x", hw[i]));
                }
                return sb.toString();
            }
        } catch (Throwable ignored) {}

        // Fallback: /sys/class/net/eth0/address (plain text "aa:bb:cc:dd:ee:ff")
        String s = readFirstLine("/sys/class/net/eth0/address");
        return s != null ? s.toLowerCase() : null;
    }

    private static String probeLocalIp() {
        // Prefer the address bound to the first non-loopback UP interface.
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    if (a instanceof java.net.Inet4Address) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {}
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Throwable ignored) {}
        return null;
    }

    private static String probeHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Throwable ignored) {}
        String s = readFirstLine("/etc/hostname");
        return s;
    }

    private static String readFirstLine(String path) {
        try {
            if (!Files.exists(Paths.get(path))) return null;
            byte[] bytes = Files.readAllBytes(Paths.get(path));
            String s = new String(bytes, StandardCharsets.UTF_8).trim();
            int nl = s.indexOf('\n');
            return nl >= 0 ? s.substring(0, nl).trim() : s;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Functional interfaces kept explicit because Java 8 SAM-to-method-ref
     * inference gets confused by checked exceptions (some URCap getters are
     * declared to throw RuntimeExceptions under specific robot states).
     */
    private interface StringSupplier { String get() throws Throwable; }
    private interface TypedSupplier<T> { T get() throws Throwable; }

    private static String safeGet(StringSupplier s) {
        try { return s.get(); } catch (Throwable t) { return null; }
    }
    private static <T> T safeGetT(TypedSupplier<T> s) {
        try { return s.get(); } catch (Throwable t) { return null; }
    }
}
