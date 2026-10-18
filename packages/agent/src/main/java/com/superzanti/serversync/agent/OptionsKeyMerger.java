package com.superzanti.serversync.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges the server copy of the "key_" lines into the local options.txt.
 * Everything that is not a key binding (video / audio / language) is left
 * byte-for-byte in place, and the file is only rewritten when it really changed.
 */
public final class OptionsKeyMerger {

    public static final class Result {
        public int keyLines;
        public boolean changed;
        public boolean created;
    }

    private OptionsKeyMerger() {
    }

    public static Result merge(Path localOptions, byte[] serverKeyFile, String prefix, AgentLog log) {
        Result result = new Result();
        try {
            if (localOptions == null) {
                warn(log, "options merge skipped: no local options path");
                return result;
            }
            String actualPrefix = (prefix == null || prefix.isEmpty()) ? "key_" : prefix;
            // Latin-1 round-trips arbitrary bytes; key lines themselves are ASCII.
            Map<String, String> serverLines = parseKeyLines(serverKeyFile, actualPrefix);

            boolean exists = Files.exists(localOptions);
            if (!exists) {
                if (serverLines.isEmpty()) {
                    return result;
                }
                List<String> createdLines = new ArrayList<>(serverLines.values());
                String content = join(createdLines, "\n", false) + "\n";
                writeAtomic(localOptions, content.getBytes(StandardCharsets.ISO_8859_1));
                result.keyLines = createdLines.size();
                result.changed = true;
                result.created = true;
                info(log, "created options.txt with " + result.keyLines + " server key lines");
                return result;
            }

            String text = new String(Files.readAllBytes(localOptions), StandardCharsets.ISO_8859_1);
            String separator = text.contains("\r\n") ? "\r\n" : "\n";
            TextLines parsed = split(text);

            List<String> merged = new ArrayList<>();
            Map<String, String> remainingServer = new LinkedHashMap<>(serverLines);
            for (String line : parsed.lines) {
                if (isKeyLine(line, actualPrefix)) {
                    String key = keyName(line, actualPrefix);
                    String serverLine = remainingServer.remove(key);
                    if (serverLine != null) {
                        merged.add(serverLine);
                        continue;
                    }
                }
                merged.add(line);
            }
            merged.addAll(remainingServer.values());

            String content = join(merged, separator, parsed.trailingNewline);
            result.keyLines = countKeyLines(merged, actualPrefix);
            if (content.equals(text)) {
                return result;
            }

            writeAtomic(localOptions, content.getBytes(StandardCharsets.ISO_8859_1));
            result.changed = true;
            info(log, "merged options.txt, " + result.keyLines + " key lines");
            return result;
        } catch (Throwable t) {
            warn(log, "options key merge failed: " + t);
            return new Result();
        }
    }

    private static Map<String, String> parseKeyLines(byte[] serverKeyFile, String prefix) {
        Map<String, String> out = new LinkedHashMap<>();
        if (serverKeyFile == null || serverKeyFile.length == 0) {
            return out;
        }
        String text = new String(serverKeyFile, StandardCharsets.ISO_8859_1);
        String[] parts = text.split("\n", -1);
        for (String part : parts) {
            String line = part;
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            String key = keyName(line, prefix);
            if (key != null) {
                out.put(key, line);
            }
        }
        return out;
    }

    private static boolean isKeyLine(String line, String prefix) {
        return keyName(line, prefix) != null;
    }

    /**
     * A key line starts with the prefix and has the key name before the first
     * ':' or '='. Lines whose separator is at or before the prefix are dropped.
     */
    private static String keyName(String line, String prefix) {
        if (line == null || prefix == null || prefix.isEmpty() || !line.startsWith(prefix)) {
            return null;
        }
        int colon = line.indexOf(':');
        int equals = line.indexOf('=');
        int cut;
        if (colon < 0) {
            cut = equals;
        } else if (equals < 0) {
            cut = colon;
        } else {
            cut = Math.min(colon, equals);
        }
        if (cut <= 0) {
            return null;
        }
        return line.substring(0, cut);
    }

    private static int countKeyLines(List<String> lines, String prefix) {
        int count = 0;
        for (String line : lines) {
            if (isKeyLine(line, prefix)) {
                count++;
            }
        }
        return count;
    }

    private static TextLines split(String text) {
        TextLines out = new TextLines();
        if (text.isEmpty()) {
            return out;
        }
        String[] parts = text.split("\n", -1);
        for (String part : parts) {
            String line = part;
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            out.lines.add(line);
        }
        if (!out.lines.isEmpty() && out.lines.get(out.lines.size() - 1).isEmpty()) {
            out.lines.remove(out.lines.size() - 1);
            out.trailingNewline = true;
        }
        return out;
    }

    private static String join(List<String> lines, String separator, boolean trailingNewline) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                out.append(separator);
            }
            out.append(lines.get(i));
        }
        if (trailingNewline && !lines.isEmpty()) {
            out.append(separator);
        }
        return out.toString();
    }

    private static void writeAtomic(Path target, byte[] data) throws Exception {
        Path absolute = target.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = Files.createTempFile(parent, ".options-", ".tmp");
        boolean moved = false;
        try {
            Files.write(tmp, data);
            try {
                Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (Throwable ignored) {
                    // Swallow cleanup failures.
                }
            }
        }
    }

    private static void info(AgentLog log, String message) {
        if (log != null) {
            log.info(message);
        }
    }

    private static void warn(AgentLog log, String message) {
        if (log != null) {
            log.warn(message);
        }
    }

    private static final class TextLines {
        final List<String> lines = new ArrayList<>();
        boolean trailingNewline;
    }
}
