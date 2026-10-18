package com.superzanti.serversync.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgentConfigTest {

    @Test
    @DisplayName("Config round trips field values")
    void parsesConfig(@TempDir Path dir) throws Exception {
        String json = "{\n"
            + "  \"serverUrl\": \"http://10.0.0.1:38080\",\n"
            + "  \"instancePath\": \"D:\\\\instances\\\\GTNH\",\n"
            + "  \"submitToken\": \"secret\",\n"
            + "  \"maxSeconds\": 42,\n"
            + "  \"connectTimeoutSeconds\": 7,\n"
            + "  \"readTimeoutSeconds\": 8,\n"
            + "  \"downloadThreads\": 2,\n"
            + "  \"fullCheck\": true,\n"
            + "  \"dryRun\": true,\n"
            + "  \"logFile\": \"agent.log\",\n"
            + "  \"stateFile\": \"state.json\",\n"
            + "  \"ignoreGlobs\": [\"**/*.disabled\", \"**/*.part\"],\n"
            + "  \"keepGlobs\": [\"mods/*.jar.disabled\"],\n"
            + "  \"worldFolderMap\": {\"a\": \"b\"}\n"
            + "}";
        Path file = dir.resolve("config.json");
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));

        AgentConfig config = AgentConfig.load(file);
        assertNotNull(config);
        assertEquals("http://10.0.0.1:38080", config.serverUrl);
        assertEquals("D:\\instances\\GTNH", config.instancePath);
        assertEquals("secret", config.submitToken);
        assertEquals(42, config.maxSeconds);
        assertEquals(7, config.connectTimeoutSeconds);
        assertEquals(8, config.readTimeoutSeconds);
        assertEquals(2, config.downloadThreads);
        assertTrue(config.fullCheck);
        assertTrue(config.dryRun);
        assertEquals("agent.log", config.logFile);
        assertEquals("state.json", config.stateFile);
        assertEquals(2, config.ignoreGlobs.size());
        assertEquals(1, config.keepGlobs.size());
        assertEquals("b", config.worldFolderMap.get("a"));
    }

    @Test
    @DisplayName("Account profile fields parse")
    void parsesAccountFields(@TempDir Path dir) throws Exception {
        String json = "{\n"
            + "  \"accounts\": {\"Fgod\": \"alt\", \"AltName\": \"alt2\"},\n"
            + "  \"defaultProfile\": \"fallback\",\n"
            + "  \"prismAccountsFile\": \"C:\\\\Prism\\\\accounts.json\",\n"
            + "  \"usernameWaitSeconds\": 30\n"
            + "}";
        Path file = dir.resolve("config.json");
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));

        AgentConfig config = AgentConfig.load(file);
        assertNotNull(config);
        assertEquals("alt", config.accounts.get("Fgod"));
        assertEquals("alt2", config.accounts.get("AltName"));
        assertEquals("fallback", config.defaultProfile);
        assertEquals("C:\\Prism\\accounts.json", config.prismAccountsFile);
        assertEquals(30, config.usernameWaitSeconds);
    }

    @Test
    @DisplayName("Missing fields fall back to defaults")
    void missingFieldsUseDefaults(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.json");
        Files.write(file, "{}".getBytes(StandardCharsets.UTF_8));
        AgentConfig config = AgentConfig.load(file);
        assertNotNull(config);
        assertEquals("", config.serverUrl);
        assertEquals(300, config.maxSeconds);
        assertEquals(3, config.connectTimeoutSeconds);
        assertEquals(60, config.readTimeoutSeconds);
        assertFalse(config.fullCheck);
        assertEquals("gtnh-sync-agent.log", config.logFile);
        assertTrue(config.ignoreGlobs.isEmpty());
        assertTrue(config.accounts.isEmpty());
        assertEquals("default", config.defaultProfile);
        assertEquals("", config.prismAccountsFile);
        assertEquals(15, config.usernameWaitSeconds);
    }

    @Test
    @DisplayName("Wrongly typed fields fall back to defaults")
    void wrongTypesUseDefaults(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.json");
        Files.write(file, "{\"maxSeconds\":\"lots\",\"fullCheck\":1,\"ignoreGlobs\":\"nope\"}".getBytes(StandardCharsets.UTF_8));
        AgentConfig config = AgentConfig.load(file);
        assertNotNull(config);
        assertEquals(300, config.maxSeconds);
        assertFalse(config.fullCheck);
        assertTrue(config.ignoreGlobs.isEmpty());
    }

    @Test
    @DisplayName("Missing config file returns null")
    void missingFileReturnsNull(@TempDir Path dir) {
        assertNull(AgentConfig.load(dir.resolve("does-not-exist.json")));
        assertNull(AgentConfig.load(null));
    }

    @Test
    @DisplayName("Relative paths resolve against the jar directory, absolute paths stay put")
    void resolveRelativePaths(@TempDir Path dir) {
        Path relative = AgentConfig.resolveRelative(dir, "logs/agent.log");
        assertEquals(dir.resolve("logs/agent.log").normalize(), relative);

        Path absolute = dir.resolve("absolute.log").toAbsolutePath();
        assertEquals(absolute, AgentConfig.resolveRelative(dir, absolute.toString()));
    }
}
