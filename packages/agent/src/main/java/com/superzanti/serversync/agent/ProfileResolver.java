package com.superzanti.serversync.agent;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Maps a Minecraft username to a sync profile name. Exists so the agent can
 * pick a per-account profile without trusting unsanitized input: every result
 * either passes the server whitelist or falls back to a safe default.
 */
public final class ProfileResolver {
    private static final Pattern VALID_PROFILE = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");
    private static final String DEFAULT_PROFILE = "default";
    private static final int MAX_PARENT_LEVELS = 4;

    private ProfileResolver() {
    }

    public static boolean isValidProfile(String name) {
        return name != null && VALID_PROFILE.matcher(name).matches();
    }

    /**
     * Explicit accounts-map lookup only. Returns null when the username has no
     * mapping, so callers can distinguish "the user configured this" from
     * "nothing matched and we fell through".
     */
    public static String mapExplicit(AgentConfig config, String username) {
        if (config == null || config.accounts == null || username == null || username.isEmpty()) {
            return null;
        }
        try {
            String mapped = config.accounts.get(username);
            if (mapped != null && !mapped.isEmpty()) {
                return isValidProfile(mapped) ? mapped : null;
            }
        } catch (Throwable ignored) {
            // Treat any lookup error as "no mapping".
        }
        return null;
    }

    /**
     * Resolve the profile for a username: explicit mapping first, then the
     * username itself when it is a legal profile, then the configured fallback.
     */
    public static String mapUsername(AgentConfig config, String username) {
        String fallback = fallbackProfile(config);
        if (username == null || username.isEmpty()) {
            return fallback;
        }
        try {
            if (config != null && config.accounts != null) {
                String mapped = config.accounts.get(username);
                if (mapped != null && !mapped.isEmpty()) {
                    return isValidProfile(mapped) ? mapped : fallback;
                }
            }
            if (isValidProfile(username)) {
                return username;
            }
        } catch (Throwable t) {
            // Fall through to the safe default.
        }
        return fallback;
    }

    /**
     * Read the active account's profile name from a Prism accounts.json.
     * Returns null when the file is missing, malformed or has no active entry.
     */
    public static String readActiveUsername(Path prismAccountsFile) {
        if (prismAccountsFile == null || !Files.isRegularFile(prismAccountsFile)) {
            return null;
        }
        try {
            String text = new String(Files.readAllBytes(prismAccountsFile), StandardCharsets.UTF_8);
            JsonValue rootValue = Json.parse(text);
            if (rootValue == null || !rootValue.isObject()) {
                return null;
            }
            JsonValue accountsValue = rootValue.asObject().get("accounts");
            if (accountsValue == null || !accountsValue.isArray()) {
                return null;
            }
            for (JsonValue entry : accountsValue.asArray().values()) {
                if (!entry.isObject()) {
                    continue;
                }
                JsonObject account = entry.asObject();
                JsonValue active = account.get("active");
                if (active == null || !active.isBoolean() || !active.asBoolean()) {
                    continue;
                }
                JsonValue profile = account.get("profile");
                if (profile == null || !profile.isObject()) {
                    continue;
                }
                JsonValue name = profile.asObject().get("name");
                if (name != null && name.isString() && !name.asString().isEmpty()) {
                    return name.asString();
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Walk up from the instance directory (at most four parent levels) looking
     * for a directory that contains accounts.json (the Prism root).
     */
    public static Path findPrismAccountsFile(Path instancePath) {
        if (instancePath == null) {
            return null;
        }
        try {
            Path dir = instancePath.toAbsolutePath().normalize();
            for (int level = 0; level <= MAX_PARENT_LEVELS && dir != null; level++) {
                Path candidate = dir.resolve("accounts.json");
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
                dir = dir.getParent();
            }
        } catch (Throwable t) {
            // Treat any filesystem error as "not found".
        }
        return null;
    }

    private static String fallbackProfile(AgentConfig config) {
        if (config != null && isValidProfile(config.defaultProfile)) {
            return config.defaultProfile;
        }
        return DEFAULT_PROFILE;
    }
}
