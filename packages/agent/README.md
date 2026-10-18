# mc-sync-agent（客户端）

挂在 Minecraft JVM 上的 javaagent：进游戏前照服务端清单对齐本地文件，退出时把并集档（按键 / 地图）上交。

## 依赖

- JDK 17（编译）
- Gradle 8.x（或用仓库自带 wrapper）

## 构建

```bash
cd packages/agent
gradle build        # 产出 build/libs/gtnh-sync-agent.jar（含单测）
```

## 用法（Prism / 任意启动器）

JVM 参数加一行：

```
-javaagent:<agent jar 路径>=<本机配置 json 路径>
```

例如：

```
-javaagent:I:/gtnh-sync/gtnh-sync-agent.jar=I:/gtnh-sync/gtnh-sync-agent.json
```

- jar 全机器共用同一份（跟着发布包走）；
- **配置 json 每台机器各自一份**（`instancePath` 不同）；
- 多账号不用换 jar：agent 按 stdin `--username` → 启动器 accounts.json 的当前档 → `defaultProfile` 三路认账号。

> 文件名保持 `gtnh-sync-agent.jar`（沿用旧名，客户端 `-javaagent:` 一行不用改）。
> 包名 `com.superzanti.serversync.agent` 沿用上游 ServerSync 命名，见仓库根 README 的致谢。

## 自更新（可选，见 packages/agent-bootstrap）

不想每次换新版都手动发 jar 的话，用 `gtnh-sync-bootstrap.jar` 当 `-javaagent:` 的目标：
它每次开机先问服务端有没有新版，有就自动拉下来，再用拉到的 agent 跑同步；断网就用上一次那份。

## 配置字段

见 `example-agent-config.json`。关键项：`serverUrl`、`submitToken`、`instancePath`、`defaultProfile`、`dirs`（目录规则来自服务端清单，本地不用重复配）。
