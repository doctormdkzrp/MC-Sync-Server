package com.superzanti.serversync.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StateStoreTest {

    @Test
    @DisplayName("State round trips through an atomic write")
    void roundTrip(@TempDir Path dir) {
        Path file = dir.resolve("state.json");
        StateStore store = new StateStore(file, new AgentLog(null));

        StateStore.State missing = store.read();
        assertEquals(0, missing.version);

        StateStore.State state = new StateStore.State();
        state.version = 12;
        state.alignedAt = "2026-10-14T01:40:00Z";
        state.downloaded = 3;
        state.deleted = 1;
        store.write(state);

        assertTrue(Files.isRegularFile(file));
        assertFalse(Files.exists(dir.resolve("state.json.tmp")));

        StateStore.State readBack = store.read();
        assertEquals(12, readBack.version);
        assertEquals("2026-10-14T01:40:00Z", readBack.alignedAt);
        assertEquals(3, readBack.downloaded);
        assertEquals(1, readBack.deleted);
    }

    @Test
    @DisplayName("Corrupt state falls back to defaults")
    void corruptState(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("state.json");
        Files.write(file, "definitely not json".getBytes("UTF-8"));
        StateStore store = new StateStore(file, new AgentLog(null));
        assertEquals(0, store.read().version);
    }
}
