package com.mcsync.bootstrap;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自更新壳（每台机器只放这一个，永远不用换）。
 *
 * <p>开机（premain）流程：</p>
 * <ol>
 *   <li>读本机配置 json，拿 <code>serverUrl</code>；</li>
 *   <li>问服务端 <code>/agent/version</code> 最新 agent 的 sha256；</li>
 *   <li>本地 jar 已经是这个 sha256 → 直接用；否则从 <code>/agent/latest.jar</code> 拉一份，
 *       校验 sha256 后原子替换（旧的留 <code>.bak</code>）；</li>
 *   <li>用独立的类加载器把真正的 agent（<code>com.superzanti.serversync.agent.SyncAgent</code>）
 *       加载起来，调用它的 <code>premain</code>。</li>
 * </ol>
 *
 * <p>任何一步失败都退回「上一次拉到的那份」；再失败就什么都不做——
 * <b>绝不因为同步的事拖累游戏启动</b>。</p>
 *
 * <p>用法：<code>-javaagent:I:/gtnh-sync/gtnh-sync-bootstrap.jar=I:/gtnh-sync/gtnh-sync-agent.json</code>
 * （原来是直接指向 agent jar，改成指这个壳即可；配置 json 还是原来那份）</p>
 */
public final class BootstrapAgent {

    private static final String REAL_AGENT_CLASS = "com.superzanti.serversync.agent.SyncAgent";
    private static final String AGENT_JAR_NAME = "gtnh-sync-agent.jar";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 30000;

    private BootstrapAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        try {
            Path configPath = Paths.get(cleanArgs(args));
            Path home = configPath.toAbsolutePath().getParent();
            Path agentJar = home == null ? Paths.get(AGENT_JAR_NAME) : home.resolve(AGENT_JAR_NAME);
            log("启动，配置=" + configPath + " agent=" + agentJar);

            String serverUrl = readJsonString(configPath, "serverUrl");
            if (serverUrl != null && !serverUrl.trim().isEmpty()) {
                updateIfNeeded(serverUrl.trim(), agentJar);
            } else {
                log("配置里没有 serverUrl，跳过自更新");
            }

            if (!Files.isRegularFile(agentJar)) {
                log("没找到 agent jar：" + agentJar + "（本轮不同步）");
                return;
            }
            runRealAgent(agentJar, configPath.toString(), inst);
        } catch (Throwable t) {
            log("壳里出错（已忽略，不影响游戏）：" + t);
        }
    }

    // ---- 自更新 ----
    private static void updateIfNeeded(String serverUrl, Path agentJar) {
        try {
            String versionJson = httpGetString(join(serverUrl, "/agent/version"));
            if (versionJson == null) {
                log("问版本失败，用本地这份");
                return;
            }
            String remoteSha = jsonField(versionJson, "sha256");
            String remoteVer = jsonField(versionJson, "version");
            if (remoteSha == null || remoteSha.isEmpty()) {
                log("服务端还没发布 agent 包");
                return;
            }
            if (Files.isRegularFile(agentJar) && remoteSha.equalsIgnoreCase(sha256File(agentJar))) {
                log("已是最新（" + remoteVer + "）");
                return;
            }
            byte[] jar = httpGetBytes(join(serverUrl, "/agent/latest.jar"));
            if (jar == null || jar.length == 0) {
                log("下载失败，用本地这份");
                return;
            }
            if (!remoteSha.equalsIgnoreCase(sha256Bytes(jar))) {
                log("sha256 对不上，拒绝替换");
                return;
            }
            Path tmp = agentJar.resolveSibling(AGENT_JAR_NAME + ".new");
            Files.write(tmp, jar, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            if (Files.isRegularFile(agentJar)) {
                Files.copy(agentJar, agentJar.resolveSibling(AGENT_JAR_NAME + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp, agentJar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, agentJar, StandardCopyOption.REPLACE_EXISTING);
            }
            log("已更新到 " + remoteVer + "（" + remoteSha.substring(0, 12) + "）");
        } catch (Throwable t) {
            log("自更新跳过：" + t);
        }
    }

    // ---- 跑真正的 agent ----
    private static void runRealAgent(Path agentJar, String configArgs, Instrumentation inst) throws Exception {
        URLClassLoader loader = new URLClassLoader(
            new URL[]{agentJar.toUri().toURL()}, ClassLoader.getSystemClassLoader());
        Class<?> cls = Class.forName(REAL_AGENT_CLASS, true, loader);
        Method premain = cls.getMethod("premain", String.class, Instrumentation.class);
        premain.invoke(null, configArgs, inst);
        log("已交给真正 agent：" + cls.getName());
    }

    // ---- 小工具 ----
    private static String cleanArgs(String args) {
        if (args == null) {
            throw new IllegalArgumentException("必须给配置 json 的路径：" + "-javaagent:<bootstrap.jar>=<config.json>");
        }
        String value = args.trim();
        int eq = value.indexOf('=');
        if (eq >= 0) {
            value = value.substring(eq + 1).trim();
        }
        if (value.isEmpty()) {
            throw new IllegalArgumentException("配置 json 路径是空的");
        }
        return value;
    }

    private static String join(String base, String path) {
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return b + path;
    }

    private static String httpGetString(String url) {
        byte[] body = httpGetBytes(url);
        return body == null ? null : new String(body, StandardCharsets.UTF_8);
    }

    private static byte[] httpGetBytes(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            int status = connection.getResponseCode();
            if (status != 200) {
                log("HTTP " + status + " ← " + url);
                return null;
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) > 0) {
                    out.write(chunk, 0, read);
                }
                return out.toByteArray();
            }
        } catch (Throwable t) {
            log("请求失败 " + url + "：" + t);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** 从配置文件里抠一个字符串字段（壳不想引 JSON 库，够用就行）。 */
    private static String readJsonString(Path configPath, String key) {
        try {
            String text = new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8);
            return jsonField(text, key);
        } catch (Throwable t) {
            log("读配置失败：" + t);
            return null;
        }
    }

    private static String jsonField(String json, String key) {
        Matcher m = Pattern.compile(
                "\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(json);
        if (!m.find()) {
            return null;
        }
        return m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String sha256File(Path path) {
        try {
            return sha256Bytes(Files.readAllBytes(path));
        } catch (Throwable t) {
            return null;
        }
    }

    private static String sha256Bytes(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void log(String message) {
        System.out.println("[mc-sync-bootstrap] " + message);
    }
}
