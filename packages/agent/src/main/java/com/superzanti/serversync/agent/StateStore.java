package com.superzanti.serversync.agent;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Reads and atomically writes the local sync state file.
 */
public class StateStore {
    public static class State {
        public int version = 0;
        public String alignedAt = "";
        public String profile = "";
        public int downloaded = 0;
        public int deleted = 0;
    }

    private final Path file;
    private final AgentLog log;

    public StateStore(Path file, AgentLog log) {
        this.file = file;
        this.log = log;
    }

    public State read() {
        State state = new State();
        if (file == null || !Files.isRegularFile(file)) {
            return state;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonValue rootValue = Json.parse(reader);
            if (rootValue == null || !rootValue.isObject()) {
                return state;
            }
            JsonObject root = rootValue.asObject();
            state.version = getInt(root, "version", 0);
            state.alignedAt = getString(root, "alignedAt", "");
            state.profile = getString(root, "profile", "");
            state.downloaded = getInt(root, "downloaded", 0);
            state.deleted = getInt(root, "deleted", 0);
            return state;
        } catch (Throwable t) {
            log.warn("state read failed: " + t);
            return new State();
        }
    }

    public void write(State state) {
        if (file == null) {
            return;
        }
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            JsonObject root = new JsonObject();
            root.add("version", state.version);
            root.add("alignedAt", state.alignedAt == null ? "" : state.alignedAt);
            root.add("profile", state.profile == null ? "" : state.profile);
            root.add("downloaded", state.downloaded);
            root.add("deleted", state.deleted);
            String json = root.toString();

            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable t) {
            log.warn("state write failed: " + t);
        }
    }

    private static int getInt(JsonObject object, String name, int fallback) {
        JsonValue value = object.get(name);
        if (value == null || value.isNull() || !value.isNumber()) {
            return fallback;
        }
        try {
            return value.asInt();
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static String getString(JsonObject object, String name, String fallback) {
        JsonValue value = object.get(name);
        if (value == null || value.isNull() || !value.isString()) {
            return fallback;
        }
        return value.asString();
    }
}
