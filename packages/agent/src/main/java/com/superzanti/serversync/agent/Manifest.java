package com.superzanti.serversync.agent;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Parsed manifest. Parsing is defensive: missing fields keep defaults and
 * malformed input yields null (or an object with defaults) instead of throwing.
 */
public class Manifest {
    public int protocol = 0;
    public int version = 0;
    public String profile = "default";
    public String generated = "";
    public List<DirRule> dirs = new ArrayList<>();
    public List<ManifestFile> files = new ArrayList<>();

    public static Manifest parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            JsonValue rootValue = Json.parse(json);
            if (rootValue == null || !rootValue.isObject()) {
                return null;
            }
            JsonObject root = rootValue.asObject();
            Manifest manifest = new Manifest();
            manifest.protocol = getInt(root, "protocol", 0);
            manifest.version = getInt(root, "version", 0);
            manifest.profile = getString(root, "profile", manifest.profile);
            manifest.generated = getString(root, "generated", "");

            JsonValue dirsValue = root.get("dirs");
            if (dirsValue != null && dirsValue.isArray()) {
                for (JsonValue entry : dirsValue.asArray().values()) {
                    if (entry.isObject()) {
                        manifest.dirs.add(parseDir(entry.asObject()));
                    }
                }
            }

            JsonValue filesValue = root.get("files");
            if (filesValue != null && filesValue.isArray()) {
                for (JsonValue entry : filesValue.asArray().values()) {
                    if (entry.isObject()) {
                        manifest.files.add(parseFile(entry.asObject()));
                    }
                }
            }
            return manifest;
        } catch (Throwable t) {
            return null;
        }
    }

    private static DirRule parseDir(JsonObject object) {
        DirRule rule = new DirRule();
        rule.path = getString(object, "path", "");
        rule.repoPath = getString(object, "repoPath", "");
        rule.localPath = getString(object, "localPath", "");
        rule.tier = getString(object, "tier", "");
        rule.owner = getString(object, "owner", "");
        rule.mode = getString(object, "mode", "");
        rule.perAccount = getBoolean(object, "perAccount", false);
        rule.linePrefix = getString(object, "linePrefix", "");
        return rule;
    }

    private static ManifestFile parseFile(JsonObject object) {
        ManifestFile file = new ManifestFile();
        file.path = getString(object, "path", "");
        file.dir = getString(object, "dir", "");
        file.localPath = getString(object, "localPath", "");
        file.linePrefix = getString(object, "linePrefix", "");
        file.size = getLong(object, "size", -1);
        file.sha256 = getString(object, "sha256", "");
        file.tier = getString(object, "tier", "");
        file.owner = getString(object, "owner", "");
        return file;
    }

    private static String getString(JsonObject object, String name, String fallback) {
        JsonValue value = object.get(name);
        if (value == null || value.isNull() || !value.isString()) {
            return fallback;
        }
        return value.asString();
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

    private static boolean getBoolean(JsonObject object, String name, boolean fallback) {
        JsonValue value = object.get(name);
        if (value == null || value.isNull() || !value.isBoolean()) {
            return fallback;
        }
        return value.asBoolean();
    }

    private static long getLong(JsonObject object, String name, long fallback) {
        JsonValue value = object.get(name);
        if (value == null || value.isNull() || !value.isNumber()) {
            return fallback;
        }
        try {
            return value.asLong();
        } catch (Throwable t) {
            try {
                return (long) value.asDouble();
            } catch (Throwable t2) {
                return fallback;
            }
        }
    }
}
