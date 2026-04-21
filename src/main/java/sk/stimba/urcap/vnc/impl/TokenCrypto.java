/*
 * STIMBA VNC Server URCap — Token-at-rest AES-256-GCM wrapper (v3.5.0+)
 *
 * Why: URCap v3.4.0 stored the portal ptk_ token plaintext in Polyscope's
 * DataModel (`portal.token` key) which writes to /root/installations/*.installation.
 * That file is root-chmod-600, but:
 *   - Polyscope backups copy installations to USB
 *   - URCap uninstall may leave orphan DataModel files
 *   - Incident response on a compromised robot needs the blast radius to be
 *     "decrypt this, and only when you re-pair" rather than "plaintext token
 *     in a backup tarball from 2024"
 *
 * Design:
 *   - AES-256-GCM (authenticated encryption, not just encryption)
 *   - Key is derived from robot serial + URCap install timestamp via
 *     PBKDF2WithHmacSHA256 (100k iterations) — if URCap is reinstalled the
 *     key changes and any persisted ciphertext becomes unreadable (safe
 *     failure — operator just re-pairs)
 *   - Ciphertext is stored as "v1.<base64(nonce)>.<base64(ct)>" — versioned
 *     so v3.6 can rotate the KDF without breaking deserialization
 *   - Unwrap returns Optional.empty() on any failure (bad tag / wrong key /
 *     corrupt blob) instead of throwing, so callers can fall back to "not
 *     paired, ask operator to enter claim code again"
 *
 * Java 8 compatible (UR e-Series ships Java 8). Uses only javax.crypto stdlib.
 */
package sk.stimba.urcap.vnc.impl;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.logging.Logger;

public final class TokenCrypto {

    private static final Logger LOG = Logger.getLogger(TokenCrypto.class.getName());
    private static final String CIPHER_ALG = "AES/GCM/NoPadding";
    private static final String KEY_SPEC_ALG = "AES";
    private static final String KDF_ALG = "PBKDF2WithHmacSHA256";
    private static final int KDF_ITERATIONS = 100_000;
    private static final int KEY_BITS = 256;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String VERSION_PREFIX = "v1.";

    private final SecureRandom random = new SecureRandom();
    private final SecretKey key;

    /**
     * Build the key from robot-specific material so different robots / installs
     * can't decrypt each other's ciphertext.
     */
    public TokenCrypto(String robotSerial, String installSalt) {
        String saltString = (installSalt == null || installSalt.isEmpty())
                ? "stimba-urcap-default-salt" : installSalt;
        String pwdString = (robotSerial == null || robotSerial.isEmpty())
                ? "stimba-urcap-default-serial" : robotSerial;
        this.key = deriveKey(pwdString.toCharArray(), saltString.getBytes(StandardCharsets.UTF_8));
    }

    /** Convenience: pre-constructed random key (not recommended for persistence). */
    public static TokenCrypto ephemeral() {
        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);
        return new TokenCrypto(Base64.getEncoder().encodeToString(seed), "ephemeral");
    }

    /** Wrap plaintext, return versioned Base64-ish string safe for DataModel. */
    public String wrap(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher c = Cipher.getInstance(CIPHER_ALG);
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return VERSION_PREFIX
                    + Base64.getEncoder().encodeToString(nonce)
                    + "."
                    + Base64.getEncoder().encodeToString(ct);
        } catch (Throwable t) {
            LOG.warning("wrap failed: " + t.getClass().getSimpleName() + " " + t.getMessage());
            return null;
        }
    }

    /** Unwrap — returns Optional.empty() on any failure (bad tag, wrong key, corrupt). */
    public Optional<String> unwrap(String wrapped) {
        if (wrapped == null || wrapped.isEmpty()) return Optional.empty();
        if (!wrapped.startsWith(VERSION_PREFIX)) return Optional.empty();
        String body = wrapped.substring(VERSION_PREFIX.length());
        String[] parts = body.split("\\.", 2);
        if (parts.length != 2) return Optional.empty();
        try {
            byte[] nonce = Base64.getDecoder().decode(parts[0]);
            byte[] ct    = Base64.getDecoder().decode(parts[1]);
            Cipher c = Cipher.getInstance(CIPHER_ALG);
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] pt = c.doFinal(ct);
            return Optional.of(new String(pt, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            LOG.warning("unwrap failed: " + t.getClass().getSimpleName() + " " + t.getMessage());
            return Optional.empty();
        }
    }

    private static SecretKey deriveKey(char[] password, byte[] salt) {
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance(KDF_ALG);
            PBEKeySpec spec = new PBEKeySpec(password, salt, KDF_ITERATIONS, KEY_BITS);
            return new SecretKeySpec(f.generateSecret(spec).getEncoded(), KEY_SPEC_ALG);
        } catch (Throwable t) {
            throw new RuntimeException("PBKDF2 key derivation failed", t);
        }
    }

    /** SHA-256 hex helper — used by heartbeat to send vncPasswordHash without plaintext. */
    public static String sha256Hex(String s) {
        if (s == null) return null;
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
