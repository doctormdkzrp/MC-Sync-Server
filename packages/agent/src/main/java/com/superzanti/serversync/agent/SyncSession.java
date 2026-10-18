package com.superzanti.serversync.agent;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Snapshot of one startup sync round handed to the JVM shutdown hook. The
 * baseline is the server's own view of the union dirs (instance-relative
 * path -> sha256 taken from the startup manifest), so the exit submitter can
 * tell local edits / local-only files apart from files the server already
 * owns. An empty baseline (server unreachable) means "upload everything".
 */
public final class SyncSession {
    private static final long RECENT_WRITE_MILLIS = 2000L;

    public final AgentConfig config;
    public final AgentLog log;
    public final SyncHttp http;
    public final Path instance;
    public final String profile;
    /** Instance-relative path -> sha256, as owned by the server at startup. */
    public final Map<String, String> baseline = new LinkedHashMap<>();
    /** When this sync round started; older union files count as the server's. */
    public long startedAtMillis = System.currentTimeMillis();
    public final List<DirRule> unionRules = new ArrayList<>();
    public DirRule optionsRule;
    public int manifestVersion;

    public SyncSession(AgentConfig config, AgentLog log, SyncHttp http, Path instance, String profile) {
        this.config = config;
        this.log = log;
        this.http = http;
        this.instance = instance;
        this.profile = profile;
    }

    /**
     * Collect the entries to submit on exit: options.txt in full, plus union
     * files that are new or whose hash differs from the startup baseline.
     * Zero-byte and very recently written files are skipped for this round.
     */
    public static List<TarGzWriter.Entry> collectSubmissions(SyncSession s) throws Exception {
        List<TarGzWriter.Entry> entries = new ArrayList<>();
        if (s == null || s.instance == null) {
            return entries;
        }
        Set<String> added = new LinkedHashSet<>();

        if (s.optionsRule != null) {
            String rel = localRelativePath(s.optionsRule);
            Path options = resolveLocal(s.instance, rel);
            if (options != null && Files.isRegularFile(options)) {
                if (added.add(rel)) {
                    entries.add(new TarGzWriter.Entry(rel, Files.readAllBytes(options)));
                }
            }
        }

        for (DirRule rule : s.unionRules) {
            if (rule == null) {
                continue;
            }
            String relRoot = localRelativePath(rule);
            Path root = resolveLocal(s.instance, relRoot);
            if (root == null || !Files.isDirectory(root)) {
                continue;
            }
            int skippedRecent = 0;
            List<Path> files = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile).forEach(files::add);
            }
            for (Path file : files) {
                try {
                    long size = Files.size(file);
                    if (size == 0L) {
                        skippedRecent++;
                        continue;
                    }
                    long mtime = Files.getLastModifiedTime(file).toMillis();
                    if (System.currentTimeMillis() - mtime < RECENT_WRITE_MILLIS) {
                        skippedRecent++;
                        continue;
                    }
                    String rel = toPosix(s.instance.relativize(file));
                    String hash = HashUtil.sha256Hex(file);
                    String baselineHash;
                    synchronized (s) {
                        baselineHash = s.baseline.get(rel);
                    }
                    if (baselineHash != null && baselineHash.equalsIgnoreCase(hash)) {
                        continue;
                    }
                    if (baselineHash != null && mtime <= s.startedAtMillis) {
                        // The server already owns this path and this round did not
                        // write the local copy (e.g. a failed download): server wins.
                        continue;
                    }
                    if (added.add(rel)) {
                        entries.add(new TarGzWriter.Entry(rel, Files.readAllBytes(file)));
                    }
                } catch (Throwable t) {
                    warn(s.log, "submit candidate failed: " + file + ": " + t);
                }
            }
            if (skippedRecent > 0 && s.log != null) {
                s.log.log("DEBUG", "submit scan skipped " + skippedRecent
                    + " busy/empty file(s) under " + relRoot);
            }
        }
        return entries;
    }

    static String localRelativePath(DirRule rule) {
        if (rule == null) {
            return "";
        }
        String path = rule.localPath;
        if (path == null || path.isEmpty()) {
            path = rule.path;
        }
        return path == null ? "" : path.replace('\\', '/');
    }

    static Path resolveLocal(Path instance, String relativePosixPath) {
        if (instance == null || relativePosixPath == null || relativePosixPath.isEmpty()) {
            return null;
        }
        try {
            Path resolved = instance.resolve(relativePosixPath.replace('/', File.separatorChar)).normalize();
            if (!resolved.startsWith(instance.normalize())) {
                return null;
            }
            return resolved;
        } catch (Throwable t) {
            return null;
        }
    }

    static String toPosix(Path path) {
        return path.toString().replace(File.separatorChar, '/');
    }

    private static void warn(AgentLog log, String message) {
        if (log != null) {
            log.warn(message);
        }
    }
}
