package com.superzanti.serversync.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.io.BufferedWriter;

/**
 * Append-only UTF-8 log writer. Every line is: <ISO8601> <LEVEL> <message>.
 * Never throws; logging failures are swallowed so the agent can always proceed.
 */
public class AgentLog {
    private final Object lock = new Object();
    private BufferedWriter writer;

    public AgentLog(Path file) {
        if (file == null) {
            return;
        }
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (Throwable t) {
            writer = null;
        }
    }

    public void info(String message) {
        log("INFO", message);
    }

    public void warn(String message) {
        log("WARN", message);
    }

    public void error(String message) {
        log("ERROR", message);
    }

    public void log(String level, String message) {
        String line = Instant.now().toString() + " " + level + " " + message;
        synchronized (lock) {
            if (writer == null) {
                return;
            }
            try {
                writer.write(line);
                writer.newLine();
                writer.flush();
            } catch (Throwable ignored) {
                // Swallow: logging must never break the agent.
            }
        }
    }

    public void close() {
        synchronized (lock) {
            if (writer == null) {
                return;
            }
            try {
                writer.close();
            } catch (Throwable ignored) {
                // Swallow.
            } finally {
                writer = null;
            }
        }
    }
}
