package com.superzanti.serversync.agent;

/**
 * 客户端 agent 版本号。
 *
 * <p>发布新版时改这里；agent 每次拉清单会把这个版本报给服务端，
 * 管理台据此显示「哪台机器还在跑旧版」。</p>
 */
public final class AgentVersion {
    public static final String VERSION = "0.3.0";

    private AgentVersion() {
    }
}
