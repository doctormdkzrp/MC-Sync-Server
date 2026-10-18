package com.superzanti.serversync.agent;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonValue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Exit-time uploader. Builds a gzip tar of the options file plus locally
 * changed union files, POSTs it to /submit, and never retries or blocks the
 * JVM shutdown path on failure.
 */
public final class Submitter {
    private Submitter() {
    }

    public static void run(SyncSession session, AgentLog log) {
        Path temp = null;
        try {
            if (session == null) {
                return;
            }
            List<TarGzWriter.Entry> entries = SyncSession.collectSubmissions(session);
            if (entries.isEmpty()) {
                info(log, "nothing to submit");
                return;
            }

            temp = SyncAgent.agentJarDir().resolve("gtnh-sync-submit-" + currentPid() + ".tar.gz");
            TarGzWriter.write(temp, entries);
            byte[] body = Files.readAllBytes(temp);
            int status = session.http.submit(session.profile, body);
            String response = summarize(session.http.lastResponseBody());
            if (status == 200) {
                info(log, "submitted " + entries.size() + " entries: HTTP " + status + " " + response);
            } else {
                warn(log, "submit failed: HTTP " + status + " " + response);
            }
        } catch (Throwable t) {
            warn(log, "submit failed: " + t);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (Throwable ignored) {
                    // Swallow cleanup failures.
                }
            }
        }
    }

    private static String summarize(String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        try {
            JsonValue rootValue = Json.parse(body);
            if (rootValue != null && rootValue.isObject()) {
                com.eclipsesource.json.JsonObject root = rootValue.asObject();
                StringBuilder out = new StringBuilder();
                JsonValue version = root.get("version");
                if (version != null && version.isNumber()) {
                    out.append("version=").append(version.asInt());
                }
                JsonValue accepted = root.get("accepted");
                if (accepted != null && accepted.isArray()) {
                    if (out.length() > 0) {
                        out.append(' ');
                    }
                    out.append("accepted=").append(accepted.asArray().size());
                }
                JsonValue keyLines = root.get("keyLines");
                if (keyLines != null && keyLines.isNumber()) {
                    if (out.length() > 0) {
                        out.append(' ');
                    }
                    out.append("keyLines=").append(keyLines.asInt());
                }
                if (out.length() > 0) {
                    return out.toString();
                }
            }
        } catch (Throwable ignored) {
            // Fall through to the raw (already truncated) body.
        }
        return body;
    }

    private static long currentPid() {
        try {
            return ProcessHandle.current().pid();
        } catch (Throwable t) {
            return 0L;
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
}
