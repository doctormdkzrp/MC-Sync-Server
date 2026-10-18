package com.superzanti.serversync.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SyncSessionTest {

    private static Path write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - 10_000L));
        return file;
    }

    @Test
    @DisplayName("Collects options.txt plus changed and new union files only")
    void collectsChangedAndNewFiles(@TempDir Path instance) throws Exception {
        Path options = write(instance.resolve("options.txt"), "key_attack:key.mouse.left\nfov:0.5\n");
        Path union = instance.resolve("journeymap");
        Path unchanged = write(union.resolve("unchanged.png"), "same");
        Path changed = write(union.resolve("changed.png"), "new-content");
        Path added = write(union.resolve("new.png"), "brand-new");
        Files.createDirectories(union);
        Files.write(union.resolve("empty.png"), new byte[0]);
        Files.setLastModifiedTime(union.resolve("empty.png"),
            FileTime.fromMillis(System.currentTimeMillis() - 10_000L));

        SyncSession session = new SyncSession(new AgentConfig(), null, null, instance, "Fgod");
        session.optionsRule = rule("options.txt", "line-union");
        session.unionRules.add(rule("journeymap", "union"));
        // Pretend the round started a minute ago: the backdated files above then
        // count as "written during this round".
        session.startedAtMillis = System.currentTimeMillis() - 60_000L;
        session.baseline.put("journeymap/unchanged.png", HashUtil.sha256Hex(unchanged));
        session.baseline.put("journeymap/changed.png", HashUtil.sha256Hex("old-content".getBytes(StandardCharsets.UTF_8)));

        List<TarGzWriter.Entry> entries = SyncSession.collectSubmissions(session);
        Set<String> names = new TreeSet<>();
        for (TarGzWriter.Entry entry : entries) {
            names.add(entry.name);
        }

        Set<String> expected = new TreeSet<>();
        expected.add("options.txt");
        expected.add("journeymap/changed.png");
        expected.add("journeymap/new.png");
        assertEquals(expected, names);
        assertTrue(Files.isRegularFile(options));
    }

    @Test
    @DisplayName("Server-owned paths are not re-uploaded from a stale local copy")
    void staleLocalCopyWithServerOwningPathIsSkipped(@TempDir Path instance) throws Exception {
        Path union = instance.resolve("journeymap");
        Path stale = write(union.resolve("stale.png"), "older-than-this-round");
        Path localOnly = write(union.resolve("local-only.png"), "never-on-server");

        SyncSession session = new SyncSession(new AgentConfig(), null, null, instance, "Fgod");
        session.unionRules.add(rule("journeymap", "union"));
        // Server owns stale.png with a different hash; local file predates this round.
        session.baseline.put("journeymap/stale.png", HashUtil.sha256Hex("server-content".getBytes(StandardCharsets.UTF_8)));

        Set<String> names = names(SyncSession.collectSubmissions(session));
        Set<String> expected = new TreeSet<>();
        expected.add("journeymap/local-only.png");
        assertEquals(expected, names);
        assertTrue(Files.isRegularFile(stale));
    }

    @Test
    @DisplayName("Recently written union files are skipped for this round")
    void skipsFilesStillBeingWritten(@TempDir Path instance) throws Exception {
        Path union = instance.resolve("journeymap");
        Files.createDirectories(union);
        Path recent = union.resolve("recent.png");
        Files.write(recent, "busy".getBytes(StandardCharsets.UTF_8));

        SyncSession session = new SyncSession(new AgentConfig(), null, null, instance, "Fgod");
        session.unionRules.add(rule("journeymap", "union"));

        assertTrue(SyncSession.collectSubmissions(session).isEmpty());
    }

    private static Set<String> names(List<TarGzWriter.Entry> entries) {
        Set<String> out = new TreeSet<>();
        for (TarGzWriter.Entry entry : entries) {
            out.add(entry.name);
        }
        return out;
    }

    private static DirRule rule(String path, String mode) {
        DirRule rule = new DirRule();
        rule.path = path;
        rule.localPath = path;
        rule.tier = "union";
        rule.mode = mode;
        return rule;
    }
}
