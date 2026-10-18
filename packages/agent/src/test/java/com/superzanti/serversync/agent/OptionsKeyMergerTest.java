package com.superzanti.serversync.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OptionsKeyMergerTest {

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Missing local options.txt is created from server key lines only")
    void createsMissingFile(@TempDir Path dir) throws Exception {
        Path options = dir.resolve("options.txt");
        byte[] server = bytes("key_jump:key.space\nkey_attack:key.mouse.left\n");

        OptionsKeyMerger.Result result = OptionsKeyMerger.merge(options, server, "key_", null);

        assertTrue(result.created);
        assertTrue(result.changed);
        assertEquals(2, result.keyLines);
        assertEquals("key_jump:key.space\nkey_attack:key.mouse.left\n",
            new String(Files.readAllBytes(options), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Conflicting keys take the server value and local-only keys survive")
    void serverWinsAndLocalKeysSurvive(@TempDir Path dir) throws Exception {
        Path options = dir.resolve("options.txt");
        String local = "fov:0.5\n"
            + "key_attack:key.mouse.right\n"
            + "key_local:local.value\n"
            + "lang:en_US\n";
        Files.write(options, bytes(local));
        byte[] server = bytes("key_attack:key.mouse.left\nkey_new:key.n\n");

        OptionsKeyMerger.Result result = OptionsKeyMerger.merge(options, server, "key_", null);

        assertFalse(result.created);
        assertTrue(result.changed);
        assertEquals(3, result.keyLines);
        String expected = "fov:0.5\n"
            + "key_attack:key.mouse.left\n"
            + "key_local:local.value\n"
            + "lang:en_US\n"
            + "key_new:key.n\n";
        assertEquals(expected, new String(Files.readAllBytes(options), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Non-key lines keep their content and order")
    void nonKeyLinesUntouched(@TempDir Path dir) throws Exception {
        Path options = dir.resolve("options.txt");
        String local = "video:1\n"
            + "key_a:old\n"
            + "sound:0.5\n"
            + "lang:en_US\n"
            + "gamma:0.7\n";
        Files.write(options, bytes(local));
        byte[] server = bytes("key_a:new\n");

        OptionsKeyMerger.merge(options, server, "key_", null);

        String[] lines = new String(Files.readAllBytes(options), StandardCharsets.UTF_8).split("\n");
        assertEquals("video:1", lines[0]);
        assertEquals("key_a:new", lines[1]);
        assertEquals("sound:0.5", lines[2]);
        assertEquals("lang:en_US", lines[3]);
        assertEquals("gamma:0.7", lines[4]);
    }

    @Test
    @DisplayName("Empty server content leaves the local file alone")
    void emptyServerDoesNotWrite(@TempDir Path dir) throws Exception {
        Path options = dir.resolve("options.txt");
        String local = "fov:0.5\nkey_a:b\n";
        Files.write(options, bytes(local));

        OptionsKeyMerger.Result result = OptionsKeyMerger.merge(options, new byte[0], "key_", null);

        assertFalse(result.changed);
        assertFalse(result.created);
        assertEquals(1, result.keyLines);
        assertEquals(local, new String(Files.readAllBytes(options), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("CRLF line endings are preserved")
    void crlfPreserved(@TempDir Path dir) throws Exception {
        Path options = dir.resolve("options.txt");
        Files.write(options, bytes("fov:0.5\r\nkey_a:old\r\n"));
        byte[] server = bytes("key_a:new\n");

        OptionsKeyMerger.Result result = OptionsKeyMerger.merge(options, server, "key_", null);

        assertTrue(result.changed);
        byte[] written = Files.readAllBytes(options);
        assertEquals("fov:0.5\r\nkey_a:new\r\n", new String(written, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Server lines without a separator are discarded")
    void junkServerLinesDropped(@TempDir Path dir) throws Exception {
        Path options = dir.resolve("options.txt");
        byte[] server = bytes("key_good:value\nkey_broken\nsomething:else\n");

        OptionsKeyMerger.Result result = OptionsKeyMerger.merge(options, server, "key_", null);

        assertTrue(result.created);
        assertEquals(1, result.keyLines);
        assertEquals("key_good:value\n",
            new String(Files.readAllBytes(options), StandardCharsets.UTF_8));
    }
}
