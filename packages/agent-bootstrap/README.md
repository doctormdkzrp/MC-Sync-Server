# mc-sync-bootstrap（自更新壳）

一个几十行、**永远不用换**的小 javaagent。放在每台机器一个固定路径，
`-javaagent:` 指向它；它每次开机先从服务端拉最新 agent 再跑。

## 为什么要有它

javaagent 是**开机那一刻**从本地路径读 jar 的，读不了网上的东西。
所以想「以后再也不用手动换 jar」，就得有个固定的壳留在地面上负责换。

## 构建

```bash
cd packages/agent-bootstrap
gradle jar      # 产出 build/libs/gtnh-sync-bootstrap.jar（无外部依赖）
```

## 用法

原来 Prism 里那行是：

```
-javaagent:I:/gtnh-sync/gtnh-sync-agent.jar=I:/gtnh-sync/gtnh-sync-agent.json
```

改成指向壳（配置 json 不动）：

```
-javaagent:I:/gtnh-sync/gtnh-sync-bootstrap.jar=I:/gtnh-sync/gtnh-sync-agent.json
```

壳按配置 json 所在目录找 `gtnh-sync-agent.jar`，会自动拉新版并替换（旧的留 `.bak`）。

## 行为

- 服务端没发布过 agent 包 → 用本地那份；
- 本地 sha256 与最新一致 → 直接用，不下载；
- 断网 / 下载失败 → 用上一次拉到的那份；
- 全都失败 → 什么都不做，**游戏照常启动**。
