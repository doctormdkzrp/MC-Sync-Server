package com.superzanti.serversync.agent;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Machine private configuration, read from the JSON file handed to the agent.
 * All fields have safe defaults; a missing or wrongly typed field never throws.
 */
public class AgentConfig {
    public String serverUrl = "";
    public String instancePath = "";
    public String submitToken = "";
    public int maxSeconds = 300;
    public int connectTimeoutSeconds = 3;
    public int readTimeoutSeconds = 60;
    public int downloadThreads = 4;
    public boolean fullCheck = false;
    public boolean dryRun = false;
    public String logFile = "gtnh-sync-agent.log";
    public String stateFile = "gtnh-sync-state.json";
    public List<String> ignoreGlobs = new ArrayList<>();
    public List<String> keepGlobs = new ArrayList<>();
    public Map<String, String> worldFolderMap = new LinkedHashMap<>();
    public Map<String, String> accounts = new LinkedHashMap<>();
    public String defaultProfile = "default";
    public String prismAccountsFile = "";
    public int usernameWaitSeconds = 15;

    /**
     * Load a config file. Returns null when the file is missing or unreadable.
     * Individual bad fields fall back to defaults rather than failing the load.
     */
    public static AgentConfig load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonValue rootValue = Json.parse(reader);
            if (rootValue == null || !rootValue.isObject()) {
                return null;
            }
            JsonObject root = rootValue.asObject();
            AgentConfig config = new AgentConfig();
            config.serverUrl = getString(root, "serverUrl", config.serverUrl);
            config.instancePath = getString(root, "instancePath", config.instancePath);
            config.submitToken = getString(root, "submitToken", config.submitToken);
            config.maxSeconds = getInt(root, "maxSeconds", config.maxSeconds);
            config.connectTimeoutSeconds = getInt(root, "connectTimeoutSeconds", config.connectTimeoutSeconds);
            config.readTimeoutSeconds = getInt(root, "readTimeoutSeconds", config.readTimeoutSeconds);
            config.downloadThreads = getInt(root, "downloadThreads", config.downloadThreads);
            config.fullCheck = getBoolean(root, "fullCheck", config.fullCheck);
            config.dryRun = getBoolean(root, "dryRun", config.dryRun);
            config.logFile = getString(root, "logFile", config.logFile);
            config.stateFile = getString(root, "stateFile", config.stateFile);
            config.ignoreGlobs = getStringList(root, "ignoreGlobs");
            config.keepGlobs = getStringList(root, "keepGlobs");
            config.worldFolderMap = getStringMap(root, "worldFolderMap");
            config.accounts = getStringMap(root, "accounts");
            config.defaultProfile = getString(root, "defaultProfile", config.defaultProfile);
            config.prismAccountsFile = getString(root, "prismAccountsFile", config.prismAccountsFile);
            config.usernameWaitSeconds = getInt(root, "usernameWaitSeconds", config.usernameWaitSeconds);
            return config;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Resolve a possibly relative path against the directory that holds the agent jar.
     */
    public static Path resolveRelative(Path baseDir, String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        Path path = Paths.get(value);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        if (baseDir == null) {
            return path.toAbsolutePath().normalize();
        }
        return baseDir.resolve(value).normalize();
    }

    private static String getString(JsonObject root, String name, String fallback) {
        JsonValue value = root.get(name);
        if (value == null || value.isNull() || !value.isString()) {
            return fallback;
        }
        return value.asString();
    }

    private static int getInt(JsonObject root, String name, int fallback) {
        JsonValue value = root.get(name);
        if (value == null || value.isNull() || !value.isNumber()) {
            return fallback;
        }
        try {
            return value.asInt();
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static boolean getBoolean(JsonObject root, String name, boolean fallback) {
        JsonValue value = root.get(name);
        if (value == null || value.isNull() || !value.isBoolean()) {
            return fallback;
        }
        return value.asBoolean();
    }

    private static List<String> getStringList(JsonObject root, String name) {
        List<String> out = new ArrayList<>();
        JsonValue value = root.get(name);
        if (value == null || value.isNull() || !value.isArray()) {
            return out;
        }
        JsonArray array = value.asArray();
        for (JsonValue entry : array.values()) {
            if (entry.isString()) {
                out.add(entry.asString());
            }
        }
        return out;
    }

    private static Map<String, String> getStringMap(JsonObject root, String name) {
        Map<String, String> out = new LinkedHashMap<>();
        JsonValue value = root.get(name);
        if (value == null || value.isNull() || !value.isObject()) {
            return out;
        }
        JsonObject object = value.asObject();
        for (String memberName : object.names()) {
            JsonValue memberValue = object.get(memberName);
            if (memberValue != null && memberValue.isString()) {
                out.put(memberName, memberValue.asString());
            }
        }
        return out;
    }
}
