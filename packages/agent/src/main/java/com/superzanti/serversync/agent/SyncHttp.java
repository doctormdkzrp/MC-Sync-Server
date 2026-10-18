package com.superzanti.serversync.agent;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Minimal HTTP wrapper built on java.net.HttpURLConnection.
 *
 * We intentionally avoid java.net.http.HttpClient: the agent runs under GTNH's
 * RetroFuturaBootstrap system class loader (RfbSystemClassLoader) and whether
 * that loader can reliably resolve the HttpClient provider is not something we
 * want to gamble on.
 */
public class SyncHttp {
    private final AgentConfig config;
    private final AgentLog log;
    private volatile String lastResponseBody = "";

    public SyncHttp(AgentConfig config, AgentLog log) {
        this.config = config;
        this.log = log;
    }

    public Manifest fetchManifest() {
        return fetchManifest(null);
    }

    /**
     * Fetch /manifest for a profile. A null or empty profile omits the query
     * parameter, which keeps older servers working.
     */
    public Manifest fetchManifest(String profile) {
        HttpURLConnection connection = null;
        try {
            String suffix = "manifest";
            if (profile != null && !profile.isEmpty()) {
                suffix = suffix + "?profile=" + URLEncoder.encode(profile, "UTF-8");
            }
            // 顺手把「哪台机器 + 什么版本」报上去，管理台据此看谁还是旧版
            suffix = suffix + (suffix.indexOf('?') >= 0 ? "&" : "?")
                + "machine=" + URLEncoder.encode(machineName(), "UTF-8");
            URL url = new URL(joinUrl(config.serverUrl, suffix));
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(toMillis(config.connectTimeoutSeconds));
            connection.setReadTimeout(toMillis(config.readTimeoutSeconds));
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("X-Agent-Version", AgentVersion.VERSION);
            int status = connection.getResponseCode();
            if (status != 200) {
                log.warn("manifest request returned HTTP " + status);
                return null;
            }
            String body = readString(connection.getInputStream());
            Manifest manifest = Manifest.parse(body);
            if (manifest != null && manifest.protocol > 2) {
                log.warn("manifest protocol " + manifest.protocol
                    + " is newer than supported (2), continuing best-effort");
            }
            return manifest;
        } catch (Throwable t) {
            log.warn("manifest fetch failed: " + t);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Download /f/<path> into destPart. Returns true only when the response was
     * 200 and the body was fully written.
     */
    public boolean download(String relativePath, Path destPart) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(joinUrl(config.serverUrl, "f/" + encodePath(relativePath)));
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(toMillis(config.connectTimeoutSeconds));
            connection.setReadTimeout(toMillis(config.readTimeoutSeconds));
            int status = connection.getResponseCode();
            if (status != 200) {
                log.warn("download returned HTTP " + status + " for " + relativePath);
                return false;
            }
            Path parent = destPart.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (InputStream in = connection.getInputStream();
                 OutputStream out = Files.newOutputStream(destPart)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            }
            return true;
        } catch (Throwable t) {
            log.warn("download failed for " + relativePath + ": " + t);
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** 本机名（报给管理台，认得出是哪台机器）。 */
    private static String machineName() {
        try {
            String host = java.net.InetAddress.getLocalHost().getHostName();
            if (host != null && !host.isEmpty()) {
                return host;
            }
        } catch (Throwable ignored) {
            // 拿不到就换环境变量
        }
        String env = System.getenv("COMPUTERNAME");
        if (env == null || env.isEmpty()) {
            env = System.getenv("HOSTNAME");
        }
        return (env == null || env.isEmpty()) ? "unknown" : env;
    }

    private static String readString(InputStream in) throws Exception {
        try (InputStream stream = in) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Fetch a repository-relative path from /f/. Returns null on any non-200
     * status or I/O error; nothing here ever throws to the caller.
     */
    public byte[] fetchBytes(String repoRelativePath) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(joinUrl(config.serverUrl, "f/" + encodePath(repoRelativePath)));
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(toMillis(config.connectTimeoutSeconds));
            connection.setReadTimeout(toMillis(config.readTimeoutSeconds));
            int status = connection.getResponseCode();
            if (status != 200) {
                log.warn("fetch returned HTTP " + status + " for " + repoRelativePath);
                return null;
            }
            try (InputStream in = connection.getInputStream()) {
                return readBytes(in);
            }
        } catch (Throwable t) {
            log.warn("fetch failed for " + repoRelativePath + ": " + t);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * POST a gzip tar body to /submit?profile=<profile>. Returns the HTTP status
     * code, or -1 on failure. The response body is kept for the exit log.
     */
    public int submit(String profile, byte[] gzipTarBody) {
        HttpURLConnection connection = null;
        lastResponseBody = "";
        try {
            String actualProfile = (profile == null || profile.isEmpty()) ? "default" : profile;
            String query = "?profile=" + URLEncoder.encode(actualProfile, "UTF-8");
            URL url = new URL(joinUrl(config.serverUrl, "submit") + query);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(toMillis(config.connectTimeoutSeconds));
            connection.setReadTimeout(toMillis(config.readTimeoutSeconds));
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/gzip");
            if (config.submitToken != null && !config.submitToken.isEmpty()) {
                connection.setRequestProperty("X-Sync-Token", config.submitToken);
            }

            byte[] body = gzipTarBody == null ? new byte[0] : gzipTarBody;
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }

            int status = connection.getResponseCode();
            InputStream responseBody = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (responseBody != null) {
                try (InputStream in = responseBody) {
                    lastResponseBody = truncate(new String(readBytes(in), StandardCharsets.UTF_8), 512);
                }
            }
            return status;
        } catch (Throwable t) {
            log.warn("submit failed: " + t);
            return -1;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public String lastResponseBody() {
        return lastResponseBody;
    }

    private static byte[] readBytes(InputStream in) throws Exception {
        try (InputStream stream = in) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxChars);
    }

    static String joinUrl(String base, String suffix) {
        if (base == null) {
            base = "";
        }
        String trimmed = base;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + "/" + suffix;
    }

    static String encodePath(String path) {
        if (path == null) {
            return "";
        }
        String[] segments = path.replace('\\', '/').split("/");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                out.append('/');
            }
            try {
                out.append(URLEncoder.encode(segments[i], "UTF-8").replace("+", "%20"));
            } catch (Throwable t) {
                out.append(segments[i]);
            }
        }
        return out.toString();
    }

    private static int toMillis(int seconds) {
        if (seconds <= 0) {
            return 0;
        }
        long millis = (long) seconds * 1000L;
        return millis > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) millis;
    }
}
