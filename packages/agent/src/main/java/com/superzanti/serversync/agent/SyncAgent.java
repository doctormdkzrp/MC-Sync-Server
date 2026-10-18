package com.superzanti.serversync.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Java agent entry point.
 *
 * premain / agentmain must never throw and must never stop the game JVM:
 * every failure is caught, written to the log, and then we simply return
 * (fail open). The startup sync runs on a daemon worker; premain itself only
 * installs the stdin tee, starts that worker and registers the exit hook.
 */
public class SyncAgent {
    private static final String DEFAULT_CONFIG_NAME = "gtnh-sync-agent.json";
    private static final String DEFAULT_LOG_NAME = "gtnh-sync-agent.log";
    private static final long WORKER_JOIN_MILLIS = 20_000L;

    private static volatile SyncSession session;
    private static volatile Thread workerThread;
    private static volatile boolean hookRegistered;

    public static void premain(String args, Instrumentation inst) {
        run(args);
    }

    public static void agentmain(String args, Instrumentation inst) {
        run(args);
    }

    private SyncAgent() {
    }

    private static void run(String args) {
        AgentLog log = null;
        boolean started = false;
        try {
            Path jarDir = agentJarDir();
            Path configPath = resolveConfigPath(args, jarDir);
            AgentConfig config = AgentConfig.load(configPath);

            String logName = (config != null && config.logFile != null && !config.logFile.isEmpty())
                ? config.logFile : DEFAULT_LOG_NAME;
            Path logPath = AgentConfig.resolveRelative(jarDir, logName);
            log = new AgentLog(logPath);

            if (config == null) {
                log.warn("config missing or invalid, sync skipped: " + configPath);
                System.out.println("[gtnh-sync-agent] sync skipped: config not found");
                return;
            }

            StdinReplayStream stdin = StdinReplayStream.install(log);
            started = true;
            startWorker(config, jarDir, log, stdin);
        } catch (Throwable t) {
            try {
                if (log != null) {
                    log.error("unexpected failure: " + t);
                }
            } catch (Throwable ignored) {
                // Swallow.
            }
            try {
                System.out.println("[gtnh-sync-agent] sync skipped: unexpected error");
            } catch (Throwable ignored) {
                // Swallow.
            }
        } finally {
            if (!started && log != null) {
                log.close();
            }
        }
    }

    private static void startWorker(AgentConfig config, Path jarDir, AgentLog log, StdinReplayStream stdin) {
        Thread worker = new Thread(() -> {
            try {
                sync(config, jarDir, log, stdin);
            } catch (Throwable t) {
                log.error("sync worker failed: " + t);
            }
        }, "gtnh-sync-worker");
        worker.setDaemon(true);
        workerThread = worker;
        registerShutdownHook(log);
        worker.start();
    }

    private static synchronized void registerShutdownHook(AgentLog log) {
        if (hookRegistered) {
            return;
        }
        hookRegistered = true;
        Thread hook = new Thread(() -> {
            try {
                Thread worker = workerThread;
                if (worker != null && worker.isAlive()) {
                    worker.join(WORKER_JOIN_MILLIS);
                }
                SyncSession current = session;
                if (current == null) {
                    log.info("shutdown: no sync session, nothing to submit");
                    return;
                }
                Submitter.run(current, log);
            } catch (Throwable t) {
                try {
                    log.warn("shutdown hook failed: " + t);
                } catch (Throwable ignored) {
                    // Swallow.
                }
            } finally {
                log.close();
            }
        }, "gtnh-sync-shutdown");
        try {
            Runtime.getRuntime().addShutdownHook(hook);
        } catch (Throwable t) {
            log.warn("cannot register shutdown hook: " + t);
        }
    }

    private static void sync(AgentConfig config, Path jarDir, AgentLog log, StdinReplayStream stdin) {
        if (config.serverUrl == null || config.serverUrl.isEmpty()) {
            log.warn("serverUrl not configured, sync skipped");
            System.out.println("[gtnh-sync-agent] sync skipped: no serverUrl");
            return;
        }
        Path instance = resolveInstance(config);
        if (instance == null || !Files.isDirectory(instance)) {
            log.warn("instance path not found, sync skipped: " + config.instancePath);
            System.out.println("[gtnh-sync-agent] sync skipped: instance not found");
            return;
        }

        String profile = resolveProfile(config, jarDir, instance, stdin, log);

        SyncHttp http = new SyncHttp(config, log);
        Manifest manifest = http.fetchManifest(profile);
        if (manifest == null) {
            log.warn("cannot reach server or manifest invalid, sync skipped");
            System.out.println("[gtnh-sync-agent] sync skipped: cannot reach server");
            return;
        }

        SyncSession current = new SyncSession(config, log, http, instance, profile);
        current.manifestVersion = manifest.version;
        collectRules(current, manifest);
        session = current;

        StateStore stateStore = new StateStore(AgentConfig.resolveRelative(jarDir, config.stateFile), log);
        StateStore.State state = stateStore.read();

        boolean full = config.fullCheck || state.version != manifest.version;

        long deadline = System.nanoTime() + (long) config.maxSeconds * 1_000_000_000L;
        SyncRunner runner = new SyncRunner(config, log, http, instance, deadline);

        int authoritativePlanned = 0;
        for (DirRule rule : manifest.dirs) {
            if (rule == null || !rule.isAuthoritativeMirror()) {
                continue;
            }
            String localName = localRoot(rule);
            if (localName == null || localName.isEmpty()) {
                log.warn("skipping authoritative rule with empty local path");
                continue;
            }
            Path dirRoot = resolveLocal(instance, localName);
            List<SyncPlanner.LocalFile> localFiles = listLocalFiles(instance, dirRoot, full, log);
            List<ActionEntry> plan = SyncPlanner.plan(
                rule.path, manifest.files, localFiles, config.ignoreGlobs, config.keepGlobs, full);
            authoritativePlanned += plan.size();
            runner.execute(plan);
            // Empty-dir cleanup belongs to authoritative dirs only.
            runner.cleanupEmptyDirs(dirRoot);
            if (runner.isBudgetExceeded()) {
                break;
            }
        }
        int authoritativeDownloaded = runner.getDownloaded();
        int authoritativeDeleted = runner.getDeleted();
        int authoritativeFailed = runner.getFailed();

        int unionPlanned = 0;
        int unionSkipped = 0;
        for (DirRule rule : manifest.dirs) {
            if (rule == null || !rule.isUnion() || !"union".equalsIgnoreCase(rule.mode)) {
                continue;
            }
            String localName = localRoot(rule);
            if (localName == null || localName.isEmpty()) {
                log.warn("skipping union rule with empty local path");
                continue;
            }
            Path dirRoot = resolveLocal(instance, localName);
            List<SyncPlanner.LocalFile> localFiles = listLocalFiles(instance, dirRoot, true, log);
            List<ActionEntry> plan = SyncPlanner.planUnion(rule.path, manifest.files, localFiles);
            unionPlanned += plan.size();
            for (ActionEntry entry : plan) {
                if (entry.action == EAction.SKIP) {
                    unionSkipped++;
                }
            }
            runner.execute(plan);
            if (runner.isBudgetExceeded()) {
                break;
            }
        }
        // The exit submitter compares against what the server owns, so take the
        // baseline straight from the manifest (used by Submitter on shutdown).
        recordBaseline(current, manifest, log);
        int unionDownloaded = runner.getDownloaded() - authoritativeDownloaded;
        int unionFailed = runner.getFailed() - authoritativeFailed;

        mergeOptions(current, manifest, instance, http, log);

        runner.cleanupTemp();

        state.version = manifest.version;
        state.alignedAt = Instant.now().toString();
        state.profile = profile;
        state.downloaded = runner.getDownloaded();
        state.deleted = runner.getDeleted();
        stateStore.write(state);

        current.manifestVersion = manifest.version;

        log.info(String.format(
            "sync done: version=%d profile=%s mode=%s authoritative(planned=%d downloaded=%d deleted=%d failed=%d)"
                + " union(rules=%d planned=%d skipped=%d downloaded=%d failed=%d)",
            manifest.version, profile, full ? "full" : "quick",
            authoritativePlanned, authoritativeDownloaded, authoritativeDeleted, authoritativeFailed,
            current.unionRules.size(), unionPlanned, unionSkipped, unionDownloaded, unionFailed));
        System.out.println("[gtnh-sync-agent] version=" + manifest.version
            + " profile=" + profile
            + " downloaded=" + runner.getDownloaded()
            + " deleted=" + runner.getDeleted());
    }

    private static String resolveProfile(AgentConfig config, Path jarDir, Path instance,
                                         StdinReplayStream stdin, AgentLog log) {
        String fromStdin = null;
        try {
            long waitMillis = Math.max(0, config.usernameWaitSeconds) * 1000L;
            fromStdin = stdin == null ? null : stdin.awaitUsername(waitMillis);
        } catch (Throwable t) {
            fromStdin = null;
        }

        // (1) 配置里 accounts 显式映射的账号最可信——对应关系是人手写的。
        if (fromStdin != null && !fromStdin.isEmpty()) {
            String mapped = ProfileResolver.mapExplicit(config, fromStdin);
            if (mapped != null) {
                log.info("profile=" + mapped + " from=accounts-map user=" + fromStdin);
                return mapped;
            }
            // 没在映射里就别拿它当档案名：真实启动时这个值可能是个无意义的占位串
            // （本机实测见过 "param"），一旦当档案用，所有人的并集档会串进同一个档案。
            log.info("stdin 的账号 " + fromStdin + " 不在 accounts 映射里，改看 Prism 账号库");
        }

        Path accountsFile = null;
        try {
            if (config.prismAccountsFile != null && !config.prismAccountsFile.isEmpty()) {
                accountsFile = AgentConfig.resolveRelative(jarDir, config.prismAccountsFile);
            } else {
                accountsFile = ProfileResolver.findPrismAccountsFile(instance);
            }
        } catch (Throwable ignored) {
            accountsFile = null;
        }
        String active = ProfileResolver.readActiveUsername(accountsFile);
        if (active != null && !active.isEmpty()) {
            String mapped = ProfileResolver.mapExplicit(config, active);
            String profile = mapped != null
                ? mapped
                : (ProfileResolver.isValidProfile(active)
                    ? active
                    : ProfileResolver.mapUsername(config, null));
            log.info("profile=" + profile + " from=accounts.json user=" + active
                + " file=" + accountsFile);
            return profile;
        }

        // (3) 账号库也没有（非 Prism 启动器）：退回 stdin 那个原始账号名。
        if (fromStdin != null && !fromStdin.isEmpty() && ProfileResolver.isValidProfile(fromStdin)) {
            log.info("profile=" + fromStdin + " from=stdin-raw user=" + fromStdin);
            return fromStdin;
        }

        String profile = ProfileResolver.mapUsername(config, null);
        log.info("profile=" + profile + " from=defaultProfile");
        return profile;
    }

    private static void collectRules(SyncSession session, Manifest manifest) {
        for (DirRule rule : manifest.dirs) {
            if (rule == null) {
                continue;
            }
            if (rule.isUnion() && "union".equalsIgnoreCase(rule.mode)) {
                session.unionRules.add(rule);
            } else if ("line-union".equalsIgnoreCase(rule.mode)) {
                if (session.optionsRule == null) {
                    session.optionsRule = rule;
                }
            }
        }
    }

    private static void mergeOptions(SyncSession session, Manifest manifest, Path instance,
                                     SyncHttp http, AgentLog log) {
        DirRule rule = session.optionsRule;
        if (rule == null) {
            return;
        }
        ManifestFile keyFile = null;
        for (ManifestFile file : manifest.files) {
            if (file != null && file.dir != null && file.dir.equals(rule.path)) {
                keyFile = file;
                break;
            }
        }
        if (keyFile == null) {
            log.info("options key file not present in manifest, skipped");
            return;
        }
        byte[] bytes = http.fetchBytes(keyFile.path);
        if (bytes == null) {
            log.warn("options key file download failed, skipped: " + keyFile.path);
            return;
        }
        String localName = localRoot(rule);
        if (localName == null || localName.isEmpty()) {
            log.warn("options local path is empty, skipped");
            return;
        }
        Path local = resolveLocal(instance, localName);
        if (local == null) {
            log.warn("options local path is unsafe, skipped: " + localName);
            return;
        }
        OptionsKeyMerger.Result result = OptionsKeyMerger.merge(local, bytes, rule.linePrefix, log);
        if (result.created) {
            log.info("options.txt created with " + result.keyLines + " server key lines");
        } else if (result.changed) {
            log.info("options.txt merged, " + result.keyLines + " key lines");
        } else {
            log.info("options.txt unchanged (" + result.keyLines + " key lines)");
        }
    }

    /**
     * Record what the server already owns for the union dirs, straight from the
     * startup manifest: instance-relative path -> sha256. Files the manifest
     * does not list (or lists without a hash) stay unknown on purpose, so the
     * exit submitter uploads them.
     */
    private static void recordBaseline(SyncSession session, Manifest manifest, AgentLog log) {
        int count = 0;
        synchronized (session) {
            for (ManifestFile file : manifest.files) {
                if (file == null || file.isServerOwned()) {
                    continue;
                }
                boolean unionFile = false;
                for (DirRule rule : session.unionRules) {
                    if (rule != null && rule.path != null && rule.path.equals(file.dir)) {
                        unionFile = true;
                        break;
                    }
                }
                if (!unionFile) {
                    continue;
                }
                String key = file.clientPath();
                if (key == null || key.isEmpty() || file.sha256 == null || file.sha256.isEmpty()) {
                    continue;
                }
                session.baseline.put(key, file.sha256);
                count++;
            }
        }
        log.log("DEBUG", "baseline recorded from server manifest: " + count + " file(s)");
    }

    private static String localRoot(DirRule rule) {
        if (rule == null) {
            return "";
        }
        if (rule.localPath != null && !rule.localPath.isEmpty()) {
            return rule.localPath;
        }
        return rule.path;
    }

    private static Path resolveLocal(Path instance, String relativePosixPath) {
        if (relativePosixPath == null || relativePosixPath.isEmpty()) {
            return instance;
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

    private static List<SyncPlanner.LocalFile> listLocalFiles(Path instance, Path dirRoot, boolean withHash, AgentLog log) {
        List<SyncPlanner.LocalFile> out = new ArrayList<>();
        if (dirRoot == null || !Files.isDirectory(dirRoot)) {
            return out;
        }
        try (Stream<Path> stream = Files.walk(dirRoot)) {
            Iterator<Path> iterator = stream.filter(Files::isRegularFile).iterator();
            while (iterator.hasNext()) {
                Path file = iterator.next();
                try {
                    String rel = instance.relativize(file).toString().replace(File.separatorChar, '/');
                    long size = Files.size(file);
                    String hash = null;
                    if (withHash) {
                        try {
                            hash = HashUtil.sha256Hex(file);
                        } catch (Throwable t) {
                            hash = null;
                        }
                    }
                    out.add(new SyncPlanner.LocalFile(rel, size, hash));
                } catch (Throwable t) {
                    log.warn("failed to inspect local file " + file + ": " + t);
                }
            }
        } catch (Throwable t) {
            log.warn("local scan failed for " + dirRoot + ": " + t);
        }
        return out;
    }

    private static Path resolveInstance(AgentConfig config) {
        if (config.instancePath == null || config.instancePath.isEmpty()) {
            return null;
        }
        try {
            Path path = Paths.get(config.instancePath);
            return path.isAbsolute() ? path.normalize() : path.toAbsolutePath().normalize();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Path resolveConfigPath(String args, Path jarDir) {
        if (args != null) {
            String value = args.trim();
            if (value.startsWith("=")) {
                value = value.substring(1).trim();
            }
            if (!value.isEmpty()) {
                return Paths.get(value);
            }
        }
        if (jarDir == null) {
            return Paths.get(DEFAULT_CONFIG_NAME).toAbsolutePath().normalize();
        }
        return jarDir.resolve(DEFAULT_CONFIG_NAME);
    }

    static Path agentJarDir() {
        try {
            URL location = SyncAgent.class.getProtectionDomain().getCodeSource().getLocation();
            Path path = Paths.get(location.toURI());
            if (Files.isRegularFile(path)) {
                Path parent = path.getParent();
                if (parent != null) {
                    return parent;
                }
            }
            return path;
        } catch (Throwable t) {
            return Paths.get(".").toAbsolutePath().normalize();
        }
    }
}
