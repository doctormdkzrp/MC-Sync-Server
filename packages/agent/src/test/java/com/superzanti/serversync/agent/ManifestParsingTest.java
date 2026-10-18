package com.superzanti.serversync.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ManifestParsingTest {

    private static final String FULL = "{\n"
        + "  \"protocol\": 1,\n"
        + "  \"version\": 12,\n"
        + "  \"generated\": \"2026-10-14T01:40:00Z\",\n"
        + "  \"dirs\": [\n"
        + "    {\"path\":\"mods\",\"tier\":\"authoritative\",\"owner\":\"both\",\"mode\":\"mirror\"},\n"
        + "    {\"path\":\"journeymap\",\"tier\":\"union\",\"owner\":\"client\",\"mode\":\"union\"}\n"
        + "  ],\n"
        + "  \"files\": [\n"
        + "    {\"path\":\"mods/AAA-1.0.jar\",\"dir\":\"mods\",\"size\":12345,\n"
        + "     \"sha256\":\"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\",\n"
        + "     \"tier\":\"authoritative\",\"owner\":\"both\"}\n"
        + "  ]\n"
        + "}";

    private static final String PROTOCOL2 = "{\n"
        + "  \"protocol\": 2,\n"
        + "  \"version\": 7,\n"
        + "  \"profile\": \"Fgod\",\n"
        + "  \"generated\": \"2026-10-18T05:00:00Z\",\n"
        + "  \"dirs\": [\n"
        + "    {\"path\":\"mods\",\"repoPath\":\"mods\",\"localPath\":\"mods\",\n"
        + "     \"tier\":\"authoritative\",\"owner\":\"both\",\"mode\":\"mirror\"},\n"
        + "    {\"path\":\"journeymap\",\"repoPath\":\"profiles/Fgod/journeymap\",\n"
        + "     \"localPath\":\"journeymap\",\"tier\":\"union\",\"owner\":\"client\",\n"
        + "     \"mode\":\"union\",\"perAccount\":true},\n"
        + "    {\"path\":\"options.txt\",\"repoPath\":\"profiles/Fgod/options.key.txt\",\n"
        + "     \"localPath\":\"options.txt\",\"tier\":\"union\",\"owner\":\"client\",\n"
        + "     \"mode\":\"line-union\",\"linePrefix\":\"key_\",\"perAccount\":true}\n"
        + "  ],\n"
        + "  \"files\": [\n"
        + "    {\"path\":\"profiles/Fgod/journeymap/a.png\",\"dir\":\"journeymap\",\n"
        + "     \"localPath\":\"journeymap/a.png\",\"size\":9,\"sha256\":\"abc\",\n"
        + "     \"tier\":\"union\",\"owner\":\"client\"},\n"
        + "    {\"path\":\"profiles/Fgod/options.key.txt\",\"dir\":\"options.txt\",\n"
        + "     \"localPath\":\"options.txt\",\"size\":80,\"sha256\":\"def\",\n"
        + "     \"tier\":\"union\",\"owner\":\"client\",\"linePrefix\":\"key_\"}\n"
        + "  ]\n"
        + "}";

    @Test
    @DisplayName("Full manifest parses all fields")
    void parsesFullManifest() {
        Manifest manifest = Manifest.parse(FULL);
        assertNotNull(manifest);
        assertEquals(1, manifest.protocol);
        assertEquals(12, manifest.version);
        assertEquals("2026-10-14T01:40:00Z", manifest.generated);
        assertEquals(2, manifest.dirs.size());
        assertEquals("mods", manifest.dirs.get(0).path);
        assertTrue(manifest.dirs.get(0).isAuthoritativeMirror());
        assertEquals(1, manifest.files.size());
        ManifestFile file = manifest.files.get(0);
        assertEquals("mods/AAA-1.0.jar", file.path);
        assertEquals("mods", file.dir);
        assertEquals(12345L, file.size);
        assertEquals("both", file.owner);
        assertEquals(64, file.sha256.length());
    }

    @Test
    @DisplayName("Missing fields fall back to defaults without throwing")
    void missingFieldsUseDefaults() {
        Manifest manifest = Manifest.parse("{\"dirs\":[],\"files\":[]}");
        assertNotNull(manifest);
        assertEquals(0, manifest.protocol);
        assertEquals(0, manifest.version);
        assertEquals("", manifest.generated);
        assertTrue(manifest.dirs.isEmpty());
        assertTrue(manifest.files.isEmpty());
    }

    @Test
    @DisplayName("Missing sub-fields inside a file entry use defaults")
    void missingFileFieldsUseDefaults() {
        Manifest manifest = Manifest.parse("{\"files\":[{\"path\":\"mods/x.jar\"}]}");
        assertNotNull(manifest);
        assertEquals(1, manifest.files.size());
        ManifestFile file = manifest.files.get(0);
        assertEquals("mods/x.jar", file.path);
        assertEquals("", file.dir);
        assertEquals(-1L, file.size);
        assertEquals("", file.sha256);
        assertEquals("", file.owner);
    }

    @Test
    @DisplayName("Wrongly typed fields do not throw")
    void wrongTypesDoNotThrow() {
        Manifest manifest = Manifest.parse("{\"version\":\"nope\",\"files\":\"not-an-array\",\"dirs\":42}");
        assertNotNull(manifest);
        assertEquals(0, manifest.version);
        assertTrue(manifest.files.isEmpty());
        assertTrue(manifest.dirs.isEmpty());
    }

    @Test
    @DisplayName("Malformed or non-object JSON returns null")
    void malformedReturnsNull() {
        assertNull(Manifest.parse(null));
        assertNull(Manifest.parse("not json at all"));
        assertNull(Manifest.parse("[1,2,3]"));
        assertNull(Manifest.parse(""));
    }

    @Test
    @DisplayName("Protocol 2 manifest reads profile, localPath, perAccount and linePrefix")
    void parsesProtocol2Manifest() {
        Manifest manifest = Manifest.parse(PROTOCOL2);
        assertNotNull(manifest);
        assertEquals(2, manifest.protocol);
        assertEquals(7, manifest.version);
        assertEquals("Fgod", manifest.profile);
        assertEquals(3, manifest.dirs.size());

        DirRule mods = manifest.dirs.get(0);
        assertEquals("mods", mods.path);
        assertEquals("mods", mods.repoPath);
        assertEquals("mods", mods.localPath);
        assertTrue(mods.isAuthoritativeMirror());
        assertFalse(mods.isUnion());
        assertFalse(mods.perAccount);

        DirRule journeymap = manifest.dirs.get(1);
        assertEquals("profiles/Fgod/journeymap", journeymap.repoPath);
        assertEquals("journeymap", journeymap.localPath);
        assertEquals("journeymap", journeymap.path);
        assertTrue(journeymap.isUnion());
        assertTrue(journeymap.perAccount);
        assertEquals("", journeymap.linePrefix);

        DirRule options = manifest.dirs.get(2);
        assertEquals("profiles/Fgod/options.key.txt", options.repoPath);
        assertEquals("options.txt", options.localPath);
        assertTrue(options.isUnion());
        assertTrue(options.perAccount);
        assertEquals("key_", options.linePrefix);

        assertEquals(2, manifest.files.size());
        ManifestFile png = manifest.files.get(0);
        assertEquals("profiles/Fgod/journeymap/a.png", png.path);
        assertEquals("journeymap/a.png", png.localPath);
        assertEquals("journeymap/a.png", png.clientPath());
        assertEquals("", png.linePrefix);

        ManifestFile keys = manifest.files.get(1);
        assertEquals("profiles/Fgod/options.key.txt", keys.path);
        assertEquals("options.txt", keys.clientPath());
        assertEquals("key_", keys.linePrefix);
    }

    @Test
    @DisplayName("Protocol 1 manifest falls back to the default profile and repo paths")
    void protocol1ManifestUsesDefaults() {
        Manifest manifest = Manifest.parse(FULL);
        assertNotNull(manifest);
        assertEquals(1, manifest.protocol);
        assertEquals("default", manifest.profile);
        assertEquals("", manifest.files.get(0).localPath);
        assertEquals("mods/AAA-1.0.jar", manifest.files.get(0).clientPath());
        assertFalse(manifest.dirs.get(1).perAccount);
        assertTrue(manifest.dirs.get(1).isUnion());
    }
}
