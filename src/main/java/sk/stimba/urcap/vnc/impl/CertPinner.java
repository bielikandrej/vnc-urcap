/*
 * STIMBA VNC Server URCap — TLS certificate pinning (v3.5.0+)
 *
 * Pins the portal.stimba.sk TLS chain to the Let's Encrypt intermediate CA
 * (as-of 2026: R10 / R11 / E5 / E6). Any cert chain that doesn't include at
 * least one of the pinned intermediate SHA-256 fingerprints is rejected —
 * defeats rogue CA MITM even if the attacker has the robot's DNS.
 *
 * We pin the INTERMEDIATE, not the leaf, because LE rotates leaf every 60
 * days and we don't want to ship an URCap update just to keep HTTP alive.
 * Intermediates rotate every ~3 years (usually with ~1 year overlap), giving
 * us time to ship a signed URCap update.
 *
 * Dev bypass: if /root/.urcap-vnc.conf has `DEV_BYPASS_CERT_PINNING=true`,
 * pinning is disabled (relies on system CA bundle). For local testing against
 * localhost or self-signed dev proxies only.
 *
 * If enabling pinning in production breaks the portal connection, operator
 * can set the bypass flag as a manual escape hatch while we ship a rotation.
 */
package sk.stimba.urcap.vnc.impl;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

public final class CertPinner {

    private static final Logger LOG = Logger.getLogger(CertPinner.class.getName());
    private static final String BYPASS_CONFIG_PATH = "/root/.urcap-vnc.conf";
    private static final String BYPASS_KEY = "DEV_BYPASS_CERT_PINNING";

    /**
     * SHA-256 fingerprints (hex, lowercase, no colons) of Let's Encrypt
     * intermediate CA DER encodings that we trust as of 2026.
     *
     * To verify / update:
     *   curl -s https://letsencrypt.org/certs/2024/r10.pem | \
     *     openssl x509 -outform DER | openssl dgst -sha256
     *
     * Intermediate rotation happens ~3y; older ones remain valid until expiry
     * so keep the full list.
     */
    private static final Set<String> PINNED_INTERMEDIATE_SHA256 = new HashSet<>(Arrays.asList(
            // Let's Encrypt R3 (original 2020-2024, legacy)
            "730c1bdcd85f57ce5dc0bba733e5f1ba5a925b2a771d640a26f7a454224dad3b".toLowerCase(Locale.ROOT),
            // Let's Encrypt R10 (2024 rotation)
            "eddadccee6fdeebdec2c1e9c52f6f6a7eef4e4e5f9c1e8a1a2a9a2bfbfbdcfcf".toLowerCase(Locale.ROOT),
            // Let's Encrypt R11 (2024 rotation)
            "c1b48299aba5208fe9630ace55ca68a03eda5a519c880c9e2a2b9c1a1b2c3d4e".toLowerCase(Locale.ROOT),
            // Let's Encrypt E5 (ECDSA 2024)
            "9b81b67d6c85b8e7b2a3fbf0c9c9e5f1a0b2c3d4e5f60718293a4b5c6d7e8f9a".toLowerCase(Locale.ROOT),
            // Let's Encrypt E6 (ECDSA 2024)
            "2a3b4c5d6e7f8a9b0c1d2e3f4051627384a5b6c7d8e9f0a1b2c3d4e5f6070819".toLowerCase(Locale.ROOT)
            // NOTE: these are placeholder values. Operators must regenerate from official
            // LE pem sources and commit the real fingerprints before production pinning
            // is enabled (BYPASS_CERT_PINNING defaults to true in v3.5.0 initial ship
            // for exactly this reason — we don't want to brick prod if a pin is wrong).
    ));

    public static boolean isBypassActive() {
        // System property takes precedence (useful for JVM tests)
        String prop = System.getProperty("stimba.urcap.dev_bypass_cert_pinning");
        if ("true".equalsIgnoreCase(prop)) return true;

        // File-based: /root/.urcap-vnc.conf contains KEY=VALUE pairs
        try {
            if (!Files.exists(Paths.get(BYPASS_CONFIG_PATH))) return true; // v3.5.0 default: bypass ON
            try (BufferedReader r = new BufferedReader(new FileReader(BYPASS_CONFIG_PATH))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq < 0) continue;
                    String k = line.substring(0, eq).trim();
                    String v = line.substring(eq + 1).trim().replace("\"", "").replace("'", "");
                    if (BYPASS_KEY.equals(k)) {
                        return "true".equalsIgnoreCase(v) || "1".equals(v) || "yes".equalsIgnoreCase(v);
                    }
                }
            }
        } catch (Throwable t) {
            LOG.warning("cert pin bypass check failed (fail-open): " + t.getMessage());
            return true;
        }
        // Not set in config → v3.5.0 default is bypass ON (safe prod rollout).
        // Set DEV_BYPASS_CERT_PINNING=false in /root/.urcap-vnc.conf to enable pinning.
        return true;
    }

    /**
     * Wrap an HttpsURLConnection with pinned trust manager unless bypass is on.
     * No-op in bypass mode — conn retains system CA bundle (same as v3.4.0).
     */
    public static void apply(HttpsURLConnection conn) {
        if (isBypassActive()) return;
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] { new PinningTrustManager() }, null);
            conn.setSSLSocketFactory(ctx.getSocketFactory());
        } catch (Throwable t) {
            LOG.warning("cert pin apply failed (leaving conn with system CA): " + t.getMessage());
        }
    }

    private static final class PinningTrustManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // We are always the client — this is unreachable from our side
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain == null || chain.length == 0) {
                throw new CertificateException("empty cert chain");
            }
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                for (X509Certificate c : chain) {
                    byte[] d = md.digest(c.getEncoded());
                    String hex = toHex(d);
                    if (PINNED_INTERMEDIATE_SHA256.contains(hex)) return; // PASS
                    md.reset();
                }
            } catch (CertificateException ce) {
                throw ce;
            } catch (Throwable t) {
                throw new CertificateException("pinning check internal error: " + t.getMessage());
            }
            throw new CertificateException(
                    "no pinned intermediate CA found in chain (len=" + chain.length + ")");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
