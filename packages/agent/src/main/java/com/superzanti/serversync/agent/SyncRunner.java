package com.superzanti.serversync.agent;

import java.io.File;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Executes a plan: downloads into a temp .part file, verifies the raw sha256,
 * then moves atomically into place. Deletes only files (empty dirs afterwards).
 * Downloads run on a bounded pool (downloadThreads); deletes stay sequential.
 * One retry per download, a hard time budget, and dry-run support.
 */
public class SyncRunner {
    private static final AtomicInteger DOWNLOAD_THREAD_SEQUENCE = new AtomicInteger(0);

    private final AgentConfig config;
    private final AgentLog log;
    private final SyncHttp http;
    private final Path instanceRoot;
    private final Path tmpRoot;
    private final long deadlineNanos;

    private final AtomicInteger downloaded = new AtomicInteger(0);
    private int deleted = 0;
    private final AtomicInteger failed = new AtomicInteger(0);

    public SyncRunner(AgentConfig config, AgentLog log, SyncHttp http, Path instanceRoot, long deadlineNanos) {
        this.config = config;
        this.log = log;
        this.http = http;
        this.instanceRoot = instanceRoot;
        this.tmpRoot = instanceRoot.resolve(".gtnh-sync-tmp");
        this.deadlineNanos = deadlineNanos;
    }

    public boolean isBudgetExceeded() {
        return System.nanoTime() - deadlineNanos > 0;
    }

    public int getDownloaded() {
        return downloaded.get();
    }

    public int getDeleted() {
        return deleted;
    }

    public int getFailed() {
        return failed.get();
    }

    public void execute(List<ActionEntry> plan) {
        if (plan == null || plan.isEmpty()) {
            return;
        }
        int threads = clampThreads(config == null ? 1 : config.downloadThreads);
        ExecutorService pool = Executors.newFixedThreadPool(threads, downloadThreadFactory());
        try {
            for (ActionEntry entry : plan) {
                if (isBudgetExceeded()) {
                    log.warn("budget exceeded, aborting");
                    break;
                }
                if (entry.action == EAction.DOWNLOAD) {
                    pool.execute(() -> doDownload(entry));
                } else if (entry.action == EAction.DELETE) {
                    doDelete(entry);
                }
                // SKIP: nothing to do.
            }
        } finally {
            pool.shutdown();
            awaitPool(pool);
        }
    }

    private void awaitPool(ExecutorService pool) {
        long readTimeout = config == null ? 0L : config.readTimeoutSeconds;
        long waitSeconds = Math.max(5L, readTimeout * 2L + 5L);
        try {
            if (!pool.awaitTermination(waitSeconds, TimeUnit.SECONDS)) {
                log.warn("download pool did not drain within " + waitSeconds + "s, moving on");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static int clampThreads(int value) {
        if (value <= 0) {
            return 1;
        }
        return Math.min(value, 16);
    }

    private static ThreadFactory downloadThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable,
                "gtnh-sync-dl-" + DOWNLOAD_THREAD_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private void doDownload(ActionEntry entry) {
        Path target = resolveAgainstInstance(entry.path);
        if (target == null) {
            log.warn("refusing unsafe path: " + entry.path);
            failed.incrementAndGet();
            return;
        }
        if (config.dryRun) {
            log.info("[dryRun] would download " + entry.path + " (" + entry.reason + ")");
            return;
        }

        String remotePath = entry.remotePath == null || entry.remotePath.isEmpty()
            ? entry.path : entry.remotePath;
        Path part = tmpRoot.resolve(entry.path.replace('/', File.separatorChar) + ".part");
        boolean ok = attemptDownload(remotePath, part);
        if (!ok) {
            ok = attemptDownload(remotePath, part);
        }
        if (!ok) {
            deleteQuietly(part);
            failed.incrementAndGet();
            log.warn("giving up on " + entry.path + " after retry");
            return;
        }

        String actual;
        try {
            actual = HashUtil.sha256Hex(part);
        } catch (Throwable t) {
            deleteQuietly(part);
            failed.incrementAndGet();
            log.warn("hash failed for " + entry.path + ": " + t);
            return;
        }
        if (actual == null || !actual.equalsIgnoreCase(entry.sha256)) {
            deleteQuietly(part);
            failed.incrementAndGet();
            log.warn("hash mismatch after download, skipping " + entry.path);
            return;
        }

        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            moveIntoPlace(part, target);
            downloaded.incrementAndGet();
            log.info("downloaded " + entry.path);
        } catch (Throwable t) {
            deleteQuietly(part);
            failed.incrementAndGet();
            log.warn("move failed for " + entry.path + ": " + t);
        }
    }

    private boolean attemptDownload(String relativePath, Path part) {
        try {
            if (Files.exists(part)) {
                Files.deleteIfExists(part);
            }
            return http.download(relativePath, part);
        } catch (Throwable t) {
            log.warn("download attempt failed for " + relativePath + ": " + t);
            return false;
        }
    }

    private void moveIntoPlace(Path part, Path target) throws Exception {
        try {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void doDelete(ActionEntry entry) {
        Path target = resolveAgainstInstance(entry.path);
        if (target == null) {
            log.warn("refusing unsafe delete: " + entry.path);
            return;
        }
        if (config.dryRun) {
            log.info("[dryRun] would delete " + entry.path);
            return;
        }
        try {
            if (Files.isDirectory(target)) {
                // Never delete directories here; only files.
                log.warn("skipping directory delete: " + entry.path);
                return;
            }
            if (Files.deleteIfExists(target)) {
                deleted++;
                log.info("deleted " + entry.path);
            }
        } catch (Throwable t) {
            log.warn("delete failed for " + entry.path + ": " + t);
        }
    }

    /**
     * Delete empty directories inside the given authoritative dir only.
     * The root itself and anything outside it are never touched.
     */
    public void cleanupEmptyDirs(Path dirRoot) {
        if (config.dryRun || dirRoot == null || !Files.isDirectory(dirRoot)) {
            return;
        }
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dirRoot)) {
            stream.filter(Files::isDirectory).forEach(dirs::add);
        } catch (Throwable t) {
            log.warn("empty-dir scan failed: " + t);
            return;
        }
        dirs.sort(Comparator.comparingInt((Path p) -> p.getNameCount()).reversed());
        for (Path dir : dirs) {
            if (dir.equals(dirRoot)) {
                continue;
            }
            try (Stream<Path> children = Files.list(dir)) {
                if (!children.findAny().isPresent()) {
                    Files.deleteIfExists(dir);
                }
            } catch (Throwable ignored) {
                // Not empty or in use; leave it.
            }
        }
    }

    /**
     * Delete the download scratch directory leave-behinds at the end of a round:
     * residual .part files first, then empty directories bottom-up, and finally
     * .gtnh-sync-tmp itself if the whole tree became empty. Only ever touches
     * tmpRoot; nothing else in the instance is affected.
     */
    public void cleanupTemp() {
        if (config.dryRun || tmpRoot == null || !Files.isDirectory(tmpRoot)) {
            return;
        }
        List<Path> all = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(tmpRoot)) {
            stream.forEach(all::add);
        } catch (Throwable t) {
            log.warn("temp cleanup scan failed: " + t);
            return;
        }
        for (Path path : all) {
            try {
                if (Files.isRegularFile(path)
                        && path.getFileName().toString().endsWith(".part")) {
                    Files.deleteIfExists(path);
                }
            } catch (Throwable ignored) {
                // Leave it for next time.
            }
        }
        List<Path> dirs = new ArrayList<>();
        for (Path path : all) {
            if (Files.isDirectory(path)) {
                dirs.add(path);
            }
        }
        dirs.sort(Comparator.comparingInt((Path p) -> p.getNameCount()).reversed());
        for (Path dir : dirs) {
            try (Stream<Path> children = Files.list(dir)) {
                if (!children.findAny().isPresent()) {
                    Files.deleteIfExists(dir);
                }
            } catch (Throwable ignored) {
                // Not empty or in use; leave it.
            }
        }
    }

    private Path resolveAgainstInstance(String relativePosixPath) {
        if (relativePosixPath == null || relativePosixPath.isEmpty()) {
            return null;
        }
        Path resolved = instanceRoot.resolve(relativePosixPath.replace('/', File.separatorChar)).normalize();
        if (!resolved.startsWith(instanceRoot.normalize())) {
            return null;
        }
        return resolved;
    }

    private void deleteQuietly(Path path) {
        try {
            if (path != null) {
                Files.deleteIfExists(path);
            }
        } catch (Throwable ignored) {
            // Swallow.
        }
    }
}
