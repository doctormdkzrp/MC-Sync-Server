package com.superzanti.serversync.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SyncPlannerTest {

    private static ManifestFile file(String path, String dir, long size, String sha, String owner) {
        ManifestFile f = new ManifestFile();
        f.path = path;
        f.dir = dir;
        f.size = size;
        f.sha256 = sha;
        f.tier = "authoritative";
        f.owner = owner;
        return f;
    }

    private static SyncPlanner.LocalFile local(String path, long size, String sha) {
        return new SyncPlanner.LocalFile(path, size, sha);
    }

    private static ActionEntry only(List<ActionEntry> plan) {
        assertEquals(1, plan.size(), "expected exactly one entry, got " + plan);
        return plan.get(0);
    }

    @Test
    @DisplayName("Local in sync yields Skip(up-to-date) on both quick and full checks")
    void upToDate() {
        List<ManifestFile> expected = Collections.singletonList(file("mods/a.jar", "mods", 100, "aaaa", "both"));
        List<SyncPlanner.LocalFile> local = Collections.singletonList(local("mods/a.jar", 100, "aaaa"));

        ActionEntry full = only(SyncPlanner.plan("mods", expected, local, null, null, true));
        assertEquals(EAction.SKIP, full.action);
        assertEquals("up-to-date", full.reason);

        ActionEntry quick = only(SyncPlanner.plan("mods", expected, local, null, null, false));
        assertEquals(EAction.SKIP, quick.action);
        assertEquals("up-to-date", quick.reason);
    }

    @Test
    @DisplayName("Missing local file yields Download(missing)")
    void missingFile() {
        List<ManifestFile> expected = Collections.singletonList(file("mods/a.jar", "mods", 100, "aaaa", "both"));
        ActionEntry entry = only(SyncPlanner.plan("mods", expected, new ArrayList<>(), null, null, true));
        assertEquals(EAction.DOWNLOAD, entry.action);
        assertEquals("missing", entry.reason);
        assertEquals("mods/a.jar", entry.path);
        assertEquals("mods/a.jar", entry.remotePath);
    }

    @Test
    @DisplayName("Quick check with wrong size yields Download(size-mismatch)")
    void sizeMismatch() {
        List<ManifestFile> expected = Collections.singletonList(file("mods/a.jar", "mods", 100, "aaaa", "both"));
        List<SyncPlanner.LocalFile> local = Collections.singletonList(local("mods/a.jar", 99, null));
        ActionEntry entry = only(SyncPlanner.plan("mods", expected, local, null, null, false));
        assertEquals(EAction.DOWNLOAD, entry.action);
        assertEquals("size-mismatch", entry.reason);
    }

    @Test
    @DisplayName("Extra local file yields Delete(extra)")
    void extraFile() {
        List<SyncPlanner.LocalFile> local = Collections.singletonList(local("mods/extra.jar", 10, null));
        ActionEntry entry = only(SyncPlanner.plan("mods", new ArrayList<>(), local, null, null, true));
        assertEquals(EAction.DELETE, entry.action);
        assertEquals("extra", entry.reason);
        assertEquals("mods/extra.jar", entry.path);
    }

    @Test
    @DisplayName("Extra file matching ignoreGlobs or keepGlobs yields Skip(ignored)")
    void ignoredExtra() {
        List<SyncPlanner.LocalFile> local = Arrays.asList(
            local("mods/foo.jar.disabled", 10, null),
            local("mods/bar.disabled", 20, null));

        List<String> ignore = Collections.singletonList("**/*.disabled");
        List<String> keep = Collections.singletonList("mods/*.jar.disabled");

        List<ActionEntry> plan = SyncPlanner.plan("mods", new ArrayList<>(), local, ignore, keep, true);
        assertEquals(2, plan.size());
        for (ActionEntry entry : plan) {
            assertEquals(EAction.SKIP, entry.action);
            assertEquals("ignored", entry.reason);
        }
    }

    @Test
    @DisplayName("Server-owned entries never appear in the plan")
    void serverOwnedNeverPlanned() {
        List<ManifestFile> expected = Arrays.asList(
            file("mods/server-only.jar", "mods", 100, "aaaa", "server"),
            file("mods/shared.jar", "mods", 200, "bbbb", "both"));
        List<SyncPlanner.LocalFile> local = Collections.singletonList(local("mods/shared.jar", 200, "bbbb"));

        List<ActionEntry> plan = SyncPlanner.plan("mods", expected, local, null, null, true);
        for (ActionEntry entry : plan) {
            assertTrue(!entry.path.equals("mods/server-only.jar"),
                "server-owned path must not appear in the plan");
        }
        assertEquals(1, plan.size());
        assertEquals("mods/shared.jar", plan.get(0).path);
        assertEquals(EAction.SKIP, plan.get(0).action);
    }

    @Test
    @DisplayName("Server-owned local file is neither downloaded nor deleted")
    void serverOwnedLocalIsLeftAlone() {
        List<ManifestFile> expected = Collections.singletonList(
            file("mods/server-only.jar", "mods", 100, "aaaa", "server"));
        List<SyncPlanner.LocalFile> local = Collections.singletonList(local("mods/server-only.jar", 100, "aaaa"));
        List<ActionEntry> plan = SyncPlanner.plan("mods", expected, local, null, null, true);
        assertTrue(plan.isEmpty(), "expected no actions, got " + plan);
    }

    @Test
    @DisplayName("Differing hash yields Download(hash-mismatch)")
    void hashMismatch() {
        List<ManifestFile> expected = Collections.singletonList(file("mods/a.jar", "mods", 100, "bbbb", "both"));
        List<SyncPlanner.LocalFile> local = Collections.singletonList(local("mods/a.jar", 100, "aaaa"));
        ActionEntry entry = only(SyncPlanner.plan("mods", expected, local, null, null, true));
        assertEquals(EAction.DOWNLOAD, entry.action);
        assertEquals("hash-mismatch", entry.reason);
        assertEquals("bbbb", entry.sha256);
    }

    @Test
    @DisplayName("Entries from other dirs are ignored")
    void otherDirsIgnored() {
        List<ManifestFile> expected = Arrays.asList(
            file("mods/a.jar", "mods", 100, "aaaa", "both"),
            file("config/b.cfg", "config", 100, "aaaa", "both"));
        List<SyncPlanner.LocalFile> local = Collections.singletonList(local("mods/a.jar", 100, "aaaa"));
        List<ActionEntry> plan = SyncPlanner.plan("mods", expected, local, null, null, true);
        assertEquals(1, plan.size());
        assertEquals("mods/a.jar", plan.get(0).path);
    }

    private static ManifestFile unionFile(String repoPath, String localPath, String sha, String owner) {
        ManifestFile f = new ManifestFile();
        f.path = repoPath;
        f.dir = "journeymap";
        f.localPath = localPath;
        f.size = 10;
        f.sha256 = sha;
        f.tier = "union";
        f.owner = owner;
        return f;
    }

    @Test
    @DisplayName("Union: server file missing locally yields Download(missing)")
    void unionMissing() {
        List<ManifestFile> expected = Collections.singletonList(
            unionFile("profiles/Fgod/journeymap/a.png", "journeymap/a.png", "aaaa", "client"));
        ActionEntry entry = only(SyncPlanner.planUnion("journeymap", expected, new ArrayList<>()));
        assertEquals(EAction.DOWNLOAD, entry.action);
        assertEquals("missing", entry.reason);
        assertEquals("journeymap/a.png", entry.path);
        assertEquals("profiles/Fgod/journeymap/a.png", entry.remotePath);
        assertTrue(!entry.path.equals(entry.remotePath),
            "union download must land and fetch at different paths");
        assertEquals("aaaa", entry.sha256);
    }

    @Test
    @DisplayName("Union: differing hash yields Download(server-wins) into the client path")
    void unionServerWins() {
        List<ManifestFile> expected = Collections.singletonList(
            unionFile("profiles/Fgod/journeymap/a.png", "journeymap/a.png", "bbbb", "client"));
        List<SyncPlanner.LocalFile> local = Collections.singletonList(local("journeymap/a.png", 10, "aaaa"));
        ActionEntry entry = only(SyncPlanner.planUnion("journeymap", expected, local));
        assertEquals(EAction.DOWNLOAD, entry.action);
        assertEquals("server-wins", entry.reason);
        assertEquals("journeymap/a.png", entry.path);
        assertEquals("profiles/Fgod/journeymap/a.png", entry.remotePath);
    }

    @Test
    @DisplayName("Union: identical hash yields Skip(up-to-date)")
    void unionUpToDate() {
        List<ManifestFile> expected = Collections.singletonList(
            unionFile("profiles/Fgod/journeymap/a.png", "journeymap/a.png", "aaaa", "client"));
        List<SyncPlanner.LocalFile> local = Collections.singletonList(local("journeymap/a.png", 10, "aaaa"));
        ActionEntry entry = only(SyncPlanner.planUnion("journeymap", expected, local));
        assertEquals(EAction.SKIP, entry.action);
        assertEquals("up-to-date", entry.reason);
    }

    @Test
    @DisplayName("Union: local-only file is skipped, never deleted")
    void unionLocalOnly() {
        List<SyncPlanner.LocalFile> local = Collections.singletonList(local("journeymap/mine.png", 10, "cccc"));
        ActionEntry entry = only(SyncPlanner.planUnion("journeymap", new ArrayList<>(), local));
        assertEquals(EAction.SKIP, entry.action);
        assertEquals("local-only", entry.reason);
        assertEquals("journeymap/mine.png", entry.path);
    }

    @Test
    @DisplayName("Union: server-owned entries are skipped entirely")
    void unionServerOwnedSkipped() {
        List<ManifestFile> expected = Collections.singletonList(
            unionFile("profiles/Fgod/journeymap/server.png", "journeymap/server.png", "aaaa", "server"));
        List<SyncPlanner.LocalFile> local = Collections.singletonList(local("journeymap/server.png", 10, "aaaa"));
        List<ActionEntry> plan = SyncPlanner.planUnion("journeymap", expected, local);
        assertTrue(plan.isEmpty(), "expected no actions, got " + plan);
    }

    @Test
    @DisplayName("Union: entries from other dirs are ignored")
    void unionOtherDirsIgnored() {
        ManifestFile other = unionFile("profiles/Fgod/schematics/x.schematic", "schematics/x.schematic", "aaaa", "client");
        other.dir = "schematics";
        List<ManifestFile> expected = Collections.singletonList(other);
        List<ActionEntry> plan = SyncPlanner.planUnion("journeymap", expected, new ArrayList<>());
        assertTrue(plan.isEmpty(), "expected no actions, got " + plan);
    }
}
