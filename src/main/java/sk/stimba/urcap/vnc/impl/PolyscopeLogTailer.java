/*
 * STIMBA VNC Server URCap — Polyscope log tailer (v3.10.0+)
 *
 * Polls `/var/log/urcontrol.log` and `/var/log/PolyscopeLog.log` for new
 * WARN/ERROR/FATAL lines and makes them available via `recent(int)` so the
 * heartbeat runner can attach them to the next POST to /api/agent/heartbeat.
 *
 * Design notes:
 *   - One-shot per heartbeat, not a continuous stream. Portal de-dupes on its
 *     side using (ts, source, message) semantic identity — we just make sure
 *     we don't resend the same line twice by tracking last-offset per file.
 *   - Caps at 200 bytes per line — UR stack traces can be huge and we don't
 *     want to fill device_error_log.
 *   - Ring buffer of latest 20 entries, oldest dropped when full. If the
 *     portal misses a heartbeat, we lose at most what fell out of the buffer.
 *
 * Heuristic parsing: UR logs are not structured. We look for these prefixes
 * (case-insensitive, per UR Polyscope 5 reference logs):
 *   - "WARNING", "WARN"  → WARN
 *   - "ERROR", "Err"      → ERROR
 *   - "FATAL", "CRITICAL", "Protective Stop", "Safety: STOP" → FATAL
 * Other lines are ignored to keep the stream actionable.
 */
package sk.stimba.urcap.vnc.impl;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

public final class PolyscopeLogTailer {

    private static final Logger LOG = Logger.getLogger(PolyscopeLogTailer.class.getName());

    private static final Path[] DEFAULT_LOGS = {
        Paths.get("/var/log/urcontrol.log"),
        Paths.get("/var/log/PolyscopeLog.log")
    };
    private static final int MAX_MESSAGE_BYTES = 200;
    private static final int BUFFER_SIZE = 20;
    private static final int MAX_BYTES_PER_TICK = 64 * 1024;

    private final Path[] logs;
    private final Map<Path, Long> lastOffset = new LinkedHashMap<>();
    private final Deque<Entry> buffer = new ArrayDeque<>(BUFFER_SIZE);
    private final ReentrantLock lock = new ReentrantLock();

    public static final class Entry {
        public final String ts;       // ISO-8601
        public final String level;    // WARN | ERROR | FATAL
        public final String source;   // filename without dir
        public final String message;

        public Entry(String ts, String level, String source, String message) {
            this.ts = ts;
            this.level = level;
            this.source = source;
            this.message = message;
        }

        public Map<String, Object> toMap() {
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            m.put("ts", ts);
            m.put("level", level);
            m.put("source", source);
            m.put("message", message);
            return m;
        }
    }

    public PolyscopeLogTailer() {
        this(DEFAULT_LOGS);
    }

    public PolyscopeLogTailer(Path[] logs) {
        this.logs = logs;
        // Start reading from the END — we don't want to flood portal with
        // historical errors on URCap start. Forward-only from here.
        for (Path p : logs) {
            try {
                if (Files.exists(p)) {
                    lastOffset.put(p, Files.size(p));
                } else {
                    lastOffset.put(p, 0L);
                }
            } catch (IOException e) {
                lastOffset.put(p, 0L);
            }
        }
    }

    /**
     * Scan each log for new WARN/ERROR/FATAL lines. Safe to call from the
     * heartbeat runner's single-thread scheduler.
     */
    public void tick() {
        for (Path p : logs) {
            try {
                pollOne(p);
            } catch (Throwable t) {
                LOG.fine("tail of " + p + " failed: " + t.getMessage());
            }
        }
    }

    private void pollOne(Path path) throws IOException {
        if (!Files.exists(path)) return;
        long size = Files.size(path);
        long offset = lastOffset.getOrDefault(path, size);
        // Log rotation detection — if file shrunk, read from the top.
        if (size < offset) offset = 0L;
        // Cap how much we read per tick — big bursts are sus.
        long readFrom = offset;
        long readTo = Math.min(size, offset + MAX_BYTES_PER_TICK);
        if (readTo <= readFrom) return;

        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            raf.seek(readFrom);
            byte[] chunk = new byte[(int) (readTo - readFrom)];
            raf.readFully(chunk);
            String s = new String(chunk, java.nio.charset.StandardCharsets.UTF_8);
            for (String line : s.split("\r?\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                Entry e = classify(trimmed, path.getFileName().toString());
                if (e != null) offer(e);
            }
            lastOffset.put(path, readTo);
        }
    }

    private static Entry classify(String line, String source) {
        String upper = line.toUpperCase();
        String level = null;
        if (upper.contains("FATAL") || upper.contains("CRITICAL") ||
                upper.contains("PROTECTIVE STOP") || upper.contains("SAFETY: STOP")) {
            level = "FATAL";
        } else if (upper.startsWith("ERROR") || upper.contains(" ERROR ") ||
                upper.contains("ERR:") || upper.contains("EXCEPTION")) {
            level = "ERROR";
        } else if (upper.startsWith("WARN") || upper.contains("WARNING") ||
                upper.contains(" WARN ")) {
            level = "WARN";
        }
        if (level == null) return null;

        String msg = line.length() > MAX_MESSAGE_BYTES
                ? line.substring(0, MAX_MESSAGE_BYTES) + "…"
                : line;
        return new Entry(Instant.now().toString(), level, source, msg);
    }

    private void offer(Entry e) {
        lock.lock();
        try {
            if (buffer.size() >= BUFFER_SIZE) buffer.pollFirst();
            buffer.offerLast(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Drain the current buffer and return the entries in time order.
     * The buffer is reset so the same entries don't appear in subsequent
     * heartbeats. Portal has 90-day TTL on its side, so losing a duplicate
     * is fine — but losing a unique event would be silent data loss.
     */
    public List<Entry> drain() {
        lock.lock();
        try {
            List<Entry> out = new ArrayList<>(buffer);
            buffer.clear();
            return out;
        } finally {
            lock.unlock();
        }
    }
}
