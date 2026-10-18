package com.superzanti.serversync.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProfileResolverTest {

    @Test
    @DisplayName("Mapped username returns the mapped profile")
    void mappedUsername() {
        AgentConfig config = new AgentConfig();
        config.accounts.put("AltName", "alt");
        assertEquals("alt", ProfileResolver.mapUsername(config, "AltName"));
    }

    @Test
    @DisplayName("Unmapped but valid username becomes the profile")
    void unmappedValidUsername() {
        AgentConfig config = new AgentConfig();
        assertEquals("Fgod", ProfileResolver.mapUsername(config, "Fgod"));
    }

    @Test
    @DisplayName("Invalid usernames fall back to defaultProfile")
    void invalidUsernameFallsBack() {
        AgentConfig config = new AgentConfig();
        config.defaultProfile = "fallback";
        assertEquals("fallback", ProfileResolver.mapUsername(config, "\u73a9\u5bb6"));
        assertEquals("fallback", ProfileResolver.mapUsername(config, "bad name"));
        assertEquals("fallback", ProfileResolver.mapUsername(config, "a".repeat(33)));
    }

    @Test
    @DisplayName("Null or empty username falls back to defaultProfile")
    void emptyUsernameFallsBack() {
        AgentConfig config = new AgentConfig();
        config.defaultProfile = "fallback";
        assertEquals("fallback", ProfileResolver.mapUsername(config, null));
        assertEquals("fallback", ProfileResolver.mapUsername(config, ""));
    }

    @Test
    @DisplayName("Invalid defaultProfile falls back to default")
    void invalidDefaultProfileFallsBackToDefault() {
        AgentConfig config = new AgentConfig();
        config.defaultProfile = "not valid!";
        assertEquals("default", ProfileResolver.mapUsername(config, null));
    }

    @Test
    @DisplayName("Profile name whitelist matches the server rules")
    void profileWhitelist() {
        assertTrue(ProfileResolver.isValidProfile("Fgod"));
        assertTrue(ProfileResolver.isValidProfile("alt_1-x"));
        assertFalse(ProfileResolver.isValidProfile(""));
        assertFalse(ProfileResolver.isValidProfile(null));
        assertFalse(ProfileResolver.isValidProfile("bad name"));
        assertFalse(ProfileResolver.isValidProfile("../etc"));
        assertFalse(ProfileResolver.isValidProfile("a".repeat(33)));
    }

    @Test
    @DisplayName("Active account name is read from Prism accounts.json")
    void readsActiveAccount(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("accounts.json");
        String json = "{\"accounts\":["
            + "{\"active\":false,\"profile\":{\"name\":\"Other\"}},"
            + "{\"active\":true,\"profile\":{\"name\":\"Fgod\"}}"
            + "]}";
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        assertEquals("Fgod", ProfileResolver.readActiveUsername(file));
    }

    @Test
    @DisplayName("Missing or malformed accounts.json returns null")
    void malformedAccountsFileReturnsNull(@TempDir Path dir) throws Exception {
        assertNull(ProfileResolver.readActiveUsername(dir.resolve("missing.json")));
        assertNull(ProfileResolver.readActiveUsername(null));

        Path bad = dir.resolve("accounts.json");
        Files.write(bad, "not json".getBytes(StandardCharsets.UTF_8));
        assertNull(ProfileResolver.readActiveUsername(bad));

        Files.write(bad, "{\"accounts\":[{\"active\":true,\"profile\":{}}]}".getBytes(StandardCharsets.UTF_8));
        assertNull(ProfileResolver.readActiveUsername(bad));
    }

    @Test
    @DisplayName("Prism accounts.json is found by walking up from the instance")
    void findsPrismAccountsFile(@TempDir Path dir) throws Exception {
        Path prismRoot = dir.resolve("Prism");
        Path minecraft = prismRoot.resolve("instances").resolve("x").resolve(".minecraft");
        Files.createDirectories(minecraft);
        Path accounts = prismRoot.resolve("accounts.json");
        Files.write(accounts, "{\"accounts\":[]}".getBytes(StandardCharsets.UTF_8));

        assertEquals(accounts, ProfileResolver.findPrismAccountsFile(minecraft));
        assertNull(ProfileResolver.findPrismAccountsFile(dir.resolve("elsewhere")));
    }
}
