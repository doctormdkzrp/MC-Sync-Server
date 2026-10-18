package com.superzanti.serversync.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure planning logic, decoupled from both the network and the file system.
 *
 * Input: a set of expected manifest entries plus a snapshot of the local files
 * (relative POSIX path, size, optional sha256) for one authoritative mirror dir.
 * Output: a list of ActionEntry (Download / Delete / Skip) with reasons.
 */
public final class SyncPlanner {
    private SyncPlanner() {
    }

    public static final class LocalFile {
        public final String relPath;
        public final long size;
        public final String sha256;

        public LocalFile(String relPath, long size, String sha256) {
            this.relPath = relPath;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    /**
     * @param dirPath     the authoritative mirror directory being planned (POSIX, no trailing slash)
     * @param allFiles    every manifest file entry (all dirs); entries outside dirPath are ignored
     * @param localFiles  files currently present under dirPath
     * @param ignoreGlobs patterns that protect extra local files from deletion
     * @param keepGlobs   extra patterns that protect extra local files from deletion
     * @param hashCheck   true for a full check (compare sha256), false for a size-only quick pass
     */
    public static List<ActionEntry> plan(
        String dirPath,
        List<ManifestFile> allFiles,
        List<LocalFile> localFiles,
        List<String> ignoreGlobs,
        List<String> keepGlobs,
        boolean hashCheck) {

        List<ActionEntry> plan = new ArrayList<>();

        Map<String, LocalFile> localByPath = new LinkedHashMap<>();
        if (localFiles != null) {
            for (LocalFile local : localFiles) {
                localByPath.put(local.relPath, local);
            }
        }

        Map<String, ManifestFile> expectedByPath = new LinkedHashMap<>();
        Set<String> serverOwnedPaths = new LinkedHashSet<>();
        if (allFiles != null) {
            for (ManifestFile file : allFiles) {
                if (file.isServerOwned()) {
                    serverOwnedPaths.add(file.path);
                    continue;
                }
                if (dirPath != null && !dirPath.equals(file.dir)) {
                    continue;
                }
                expectedByPath.put(file.path, file);
            }
        }

        for (ManifestFile expected : expectedByPath.values()) {
            LocalFile local = localByPath.get(expected.path);
            if (local == null) {
                plan.add(ActionEntry.download(expected.path, expected.dir, "missing", expected.sha256, expected.size));
            } else if (hashCheck) {
                if (local.sha256 != null && !local.sha256.isEmpty()
                    && local.sha256.equalsIgnoreCase(expected.sha256)) {
                    plan.add(ActionEntry.skip(expected.path, "up-to-date"));
                } else {
                    plan.add(ActionEntry.download(expected.path, expected.dir, "hash-mismatch", expected.sha256, expected.size));
                }
            } else {
                if (local.size == expected.size) {
                    plan.add(ActionEntry.skip(expected.path, "up-to-date"));
                } else {
                    plan.add(ActionEntry.download(expected.path, expected.dir, "size-mismatch", expected.sha256, expected.size));
                }
            }
        }

        if (localFiles != null) {
            for (LocalFile local : localFiles) {
                if (expectedByPath.containsKey(local.relPath)) {
                    continue;
                }
                if (serverOwnedPaths.contains(local.relPath)) {
                    // Server-only file: never download it, and never delete a local copy because of it.
                    continue;
                }
                if (GlobMatch.matchesAny(local.relPath, ignoreGlobs) || GlobMatch.matchesAny(local.relPath, keepGlobs)) {
                    plan.add(ActionEntry.skip(local.relPath, "ignored"));
                } else {
                    plan.add(ActionEntry.delete(local.relPath, dirPath, "extra"));
                }
            }
        }

        return plan;
    }

    /**
     * Plan a union rule (journeymap / schematics): server wins conflicts, local
     * extras are never deleted (they are submitted on exit). Keys are the
     * client-instance-relative paths so the caller can resolve them in place.
     */
    public static List<ActionEntry> planUnion(
        String rulePath,
        List<ManifestFile> allFiles,
        List<LocalFile> localFiles) {

        List<ActionEntry> plan = new ArrayList<>();

        Map<String, LocalFile> localByPath = new LinkedHashMap<>();
        if (localFiles != null) {
            for (LocalFile local : localFiles) {
                if (local == null || local.relPath == null || local.relPath.isEmpty()) {
                    continue;
                }
                localByPath.put(local.relPath, local);
            }
        }

        Map<String, ManifestFile> expectedByPath = new LinkedHashMap<>();
        Set<String> serverOwnedPaths = new LinkedHashSet<>();
        if (allFiles != null) {
            for (ManifestFile file : allFiles) {
                if (file == null) {
                    continue;
                }
                if (rulePath != null && !rulePath.equals(file.dir)) {
                    continue;
                }
                String key = file.clientPath();
                if (file.isServerOwned()) {
                    if (key != null && !key.isEmpty()) {
                        serverOwnedPaths.add(key);
                    }
                    continue;
                }
                if (key == null || key.isEmpty()) {
                    continue;
                }
                expectedByPath.put(key, file);
            }
        }

        for (Map.Entry<String, ManifestFile> expectedEntry : expectedByPath.entrySet()) {
            String key = expectedEntry.getKey();
            ManifestFile expected = expectedEntry.getValue();
            LocalFile local = localByPath.get(key);
            if (local == null) {
                plan.add(ActionEntry.download(key, expected.path, rulePath, "missing", expected.sha256, expected.size));
            } else if (local.sha256 == null || local.sha256.isEmpty()
                || !local.sha256.equalsIgnoreCase(expected.sha256)) {
                plan.add(ActionEntry.download(key, expected.path, rulePath, "server-wins", expected.sha256, expected.size));
            } else {
                plan.add(ActionEntry.skip(key, "up-to-date"));
            }
        }

        for (LocalFile local : localByPath.values()) {
            if (expectedByPath.containsKey(local.relPath) || serverOwnedPaths.contains(local.relPath)) {
                continue;
            }
            plan.add(ActionEntry.skip(local.relPath, "local-only"));
        }

        return plan;
    }
}
