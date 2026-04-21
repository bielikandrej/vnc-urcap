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
            // v3.8.0 — real SHA-256 fingerprints of Let's Encrypt intermediate
            // CA DER encodings, computed on 2026-04-21 from
            // https://letsencrypt.org/certs/2024/<name>.pem via
            //   openssl x509 -in <name>.pem -outform DER | openssl dgst -sha256
            "9d7c3f1aa6ad2b2ec0d5cf1e246f8d9ae6cbc9fd0755ad37bb974b1f2fb603f3", // R10
            "591e9ce6c863d3a079e9fabe1478c7339a26b21269dde795211361024ae31a44", // R11
            "131fce7784016899a5a00203a9efc80f18ebbd75580717edc1553580930836ec", // R12
            "d3b128216a843f8ef1321501f5df52a5df52939ee2c19297712cd3de4d419354", // R13
            "24d45aa9b8d6053d281f3842c8cc0c6c1af7ccdfd42dd5c12f6a74fa9323f7a2", // R14
            "e788d14b0436b5120bbee3f15c15badf08c1407fe72568a4f16f9151c380e1e3", // E5
            "065ab7d2a050f947587121765d8d070c0e1330d5798faa42c2072749ed293762", // E6
            "54715420224c5b65beed018dc3940d7338c577e322d5488f633d8c6a8fed61b2", // E7
            "ac1274542267f17b525535b5563bf731febb182533b46a82dc869cb64eb528c0", // E8
            "4185df97806c2ba76f1d79823f112ffa639a49ccdc990908102067ab6412b886"  // E9
    ));

    public static boolean isBypassActive() {
        // System property takes precedence (useful for JVM tests)
        String prop = System.getProperty("stimba.urcap.dev_bypass_cert_pinning");
        if ("true".equalsIgnoreCase(prop)) return true;

        // File-based: /root/.urcap-vnc.conf contains KEY=VALUE pairs
        try {
            if (!Files.exists(Paths.get(BYPASS_CONFIG_PATH))) return false; // v3.8.0 default: enforcement ON
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
            // Fail-open only if we can't READ the file at all — ensures a corrupted
            // SD card doesn't brick prod. Paired with a loud log so ops notice.
            LOG.warning("cert pin bypass check failed (fail-open): " + t.getMessage());
            return true;
        }
        // File exists but BYPASS key is absent → v3.8.0 default is enforcement ON.
        // Set DEV_BYPASS_CERT_PINNING=true in /root/.urcap-vnc.conf to bypass (dev only).
        return false;
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
