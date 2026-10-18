# gtnh-sync-agent（客户端 javaagent）

挂在游戏 JVM 上的 `-javaagent`，在游戏启动**前**把权威档（`mods` / `config` / `resourcepacks`）
与服务器清单对齐，启动时拉取并集档（地图 `journeymap` / 蓝图 `schematics` / `options.txt` 的 `key_` 行），
退出游戏时把本机新增或改过的并集文件上交。设计依据：`docs/GTNH_SYNC_IMPL_v0.2.md`。

## 一、构建

在仓库根目录执行：

```bat
gradlew.bat build
```

产物固定为：

```
build-agent\build\libs\gtnh-sync-agent.jar
```

（`build` 会自动带上 `shadowJar`，不用手动单独跑；这个 fat jar 里**不含** forge 的内容。）

## 二、挂到实例上

启动参数形如：

```
-javaagent:<jar 的绝对路径>=<本机配置 json 的绝对路径>
```

例如：

```
-javaagent:I:\Git\MINECRAFT\GTNH\ServerSync\build-agent\build\libs\gtnh-sync-agent.jar=D:\gtnh-private\gtnh-sync-agent.json
```

- `=` 后面是**本机私有配置**的绝对路径。
- 不传 `=` 参数时，默认取 **jar 同目录**下的 `gtnh-sync-agent.json`。
- 在 Prism 里要在 `instance.cfg` 里加这一行 —— **具体改法由你自己按 Prism 版本确认**（改之前先退出 Prism）。
- 配置不存在或读不出来 → 只写日志然后放行（不会拦住开游戏）。

## 三、本机私有配置（`gtnh-sync-agent.json`）

每台机器一份，**不随仓库分发**（样例见 `example-agent-config.json`）。字段：

| 字段 | 含义 |
|---|---|
| `serverUrl` | 服务端地址，如 `http://192.168.31.241:38080`。 |
| `instancePath` | **游戏目录**的绝对路径——`mods`/`config`/`resourcepacks` 就摆在它下面。Prism 实例里游戏目录是 `<实例>\.minecraft`，所以填 `<实例>\.minecraft`（别填实例根目录）。**两台机器不同**，各自填自己机器上的实际路径。 |
| `submitToken` | 上交口令：退出上交时放在 `X-Sync-Token` 请求头里。服务端没开校验时可留空。 |
| `maxSeconds` | 一轮同步的时间预算（秒）。超时只中止剩余动作，已完成的算数，下次启动接着来。 |
| `connectTimeoutSeconds` | 建连超时（秒）。 |
| `readTimeoutSeconds` | 读超时（秒）。并行下载时也用它估算等待收尾的上限。 |
| `downloadThreads` | 并行下载线程数，**1~16**（≤0 或 >16 按边界算），本版**真正生效**。 |
| `fullCheck` | `true` = 每次都做全量哈希比对；`false` = 版本号一致时只做体积巡检。 |
| `dryRun` | `true` = 只写日志不真下/真删，用来预演。 |
| `logFile` | 日志文件名。相对路径按 **jar 同目录**解析；也可写绝对路径。 |
| `stateFile` | 状态文件名（记「已对齐到第几号」）。相对路径按 **jar 同目录**解析。 |
| `ignoreGlobs` | 忽略通配：命中的本地文件**不删也不下**（如 `**/*.disabled`、`**/*.part`）。 |
| `keepGlobs` | 保留通配：命中的文件即使不在清单里也**不删**。 |
| `worldFolderMap` | 地图目录映射，目前**只解析不强用**，配置里保留即可。 |
| `accounts` | 游戏用户名 → 档案名。没写映射时，合法的用户名直接当档案名；都不行才用 `defaultProfile`。 |
| `defaultProfile` | 认不出账号时的兜底档案名，默认 `default`。 |
| `prismAccountsFile` | 留空 = 自动从 `instancePath` 向上最多 4 层找 Prism 根的 `accounts.json`；也可写绝对路径。 |
| `usernameWaitSeconds` | 等 Prism 从 stdin 喂 `--username` 的上限秒数（默认 15）。超时就改走 `accounts.json`。 |

> 日志与状态文件默认写在本 agent jar 的同目录下（相对路径以 jar 所在目录为基准）。

## 四、行为要点

- **连不上服务器 → 直接放行**：连不上、超时、非 200、JSON 坏了，统统只写日志然后返回，绝不让同步拦住开游戏。
- **版本号与本地状态一致 → 只做「体积巡检」**（快路径）：只比对文件大小和缺失/多余，通常一两秒完成。
  - ⚠️ **注意**：同体积但内容被改过的文件，快路径**查不出来**；要 `fullCheck: true` 才会做全量 SHA-256 比对。
- **权威档（`tier=authoritative` + `mode=mirror`）是全量对齐**：缺的补、哈希不符的重下、**本地多出来的文件（不在忽略名单里）会被删掉** —— 这是故意的。
- `owner=server` 的文件**不下载**（客户端会跳过）。
- **服务端清单会跳过 `.` 开头的文件与目录**（服务端有意如此）。所以客户端要么把 `**/.*` 放进 `ignoreGlobs`（不删不下），要么放进 `keepGlobs`（即使不在清单里也不删）——否则本地那些隐藏文件（例如 `config/euphoria_patcher/.data.json`）会被当成「多余」清掉。
- 一轮同步结束后会清掉实例里的临时目录 `.gtnh-sync-tmp`（残留的 `.part`、以及变空的目录），只动这个目录，不碰实例里别的东西。
- **下载真并行**：`downloadThreads`（1~16）个 `gtnh-sync-dl-*` 线程同时拉取；删除/跳过仍按顺序。
  每个文件仍是 `.part` → SHA-256 校验 → 原子 move，单个失败重试一次后只记 `failed`，不影响别人。
- **认账号三条路**（按顺序）：stdin 里 Prism 喂的 `--username` → Prism 根目录 `accounts.json` 里
  `active:true` 的账号名 → `defaultProfile`。日志会写清最终档案名和来源（`stdin` / `accounts.json` / `defaultProfile`）。
- **并集档**（`tier=union` + `mode=union`，如 `journeymap` / `schematics`）：
  - 服务器有、本机没有 → 下载；
  - 服务器有、本机有但哈希不同 → **服务器赢，覆盖本机**；
  - 本机有、服务器没有 → 不动，退出时上交。
  - 启动对齐后把本机并集目录的哈希记成基线，退出上交只收「新增或改过」的文件。
- **`options.txt` 只搬 `key_` 行**（`mode=line-union`）：画面、声音、语言等本机设置保持原样；
  本机没有 `options.txt` 时用服务器的 `key_` 行补一份。
- **退出上交**：JVM 正常退出时（关游戏）打包 `options.txt` + 变动的并集文件，`POST /submit?profile=<档案>`。
  提交失败只记 WARN，不重试、不弹窗；临时 tar 用完即删。
  **强杀进程（任务管理器结束 / `kill -9`）不会触发上交**，这轮改动留到下次正常退出。

## 四点一、认账号与并行下载（补充）

- `usernameWaitSeconds` 默认 15 秒：premain 立刻返回，工作线程在后台等 stdin；超时就换下一条路，绝不拖住游戏。
- `prismAccountsFile` 留空时会从 `instancePath` 向上最多 4 层找 `accounts.json`，适配
  `<Prism>/instances/<实例>/.minecraft` 的布局。
- `downloadThreads` ≤0 按 1 算，>16 按 16 算。

## 四点五、手动跑一轮（不开游戏时）

不启动游戏也能对齐一轮：拿一个 JVM 直接把 agent 挂上跑 `-version` 即可（agent 在 premain 里做完同步，JVM 随即退出）。
在 Prism 的 RFB 启动链下真机验证过的命令（`java`/`rfb` 路径按本机改）：

```powershell
$java = 'I:\PrismLauncher\java\java-runtime-epsilon\bin\java.exe'
$rfb  = 'I:\PrismLauncher\instances\GT_New_Horizons_2.9.0-RC-2_Java_17-26\libraries\lwjgl3ify-3.0.37-forgePatches.jar'
& $java -cp $rfb "-Djava.system.class.loader=com.gtnewhorizons.retrofuturabootstrap.RfbSystemClassLoader" `
  "-javaagent:I:\gtnh-sync\gtnh-sync-agent.jar=I:\gtnh-sync\gtnh-sync-agent.json" -version
Get-Content 'I:\gtnh-sync\gtnh-sync-agent.log' -Tail 3
```

日志末行形如
`sync done: version=1 profile=Fgod mode=quick authoritative(planned=15999 downloaded=0 deleted=0 failed=0) union(rules=2 planned=3 skipped=3 downloaded=0 failed=0)`：
`mode=full` 是全量哈希对齐（版本号变了或 `fullCheck=true`），`mode=quick` 是版本号一致的体积巡检。
premain 只是把同步丢到后台线程，所以 `-version` 场景下 JVM 会先等最多 20 秒让工作线程收尾，再跑退出上交。

## 五、已知限制

- **强杀不触发上交**：只有正常退出（JVM shutdown hook 能跑）才会 `POST /submit`。
- **并集目录只增不删**：本机多出来的地图/蓝图保留并在退出时上交；服务端不会因为清单里没有就删本机文件。
- **`options.txt` 不整文件上传**：服务端只收 `key_` 行，画面/声音/语言等设置始终以本机为准。
- 下载仍是单文件失败重试一次；网络整体不可用时只记日志，下次启动再来。

## 六、代码位置

```
build-agent/
  build.gradle
  example-agent-config.json
  README.md                              # 本文件
  src/main/java/com/superzanti/serversync/agent/
    SyncAgent.java                       # premain / agentmain 入口
    AgentConfig.java                     # 读配置
    AgentLog.java                        # 日志
    SyncHttp.java                        # HTTP 封装
    Manifest.java / ManifestFile.java / DirRule.java
    SyncPlanner.java                     # 纯逻辑：算行动计划（权威档 / 并集档）
    ActionEntry.java / EAction.java
    SyncRunner.java                      # 执行计划（并行下载/预算/校验/原子落盘/清临时目录）
    StdinReplayStream.java               # System.in 边读边照抄 + 认 --username
    ProfileResolver.java                 # 用户名 -> 档案名 / 找 Prism accounts.json
    OptionsKeyMerger.java                # 只合并 options.txt 的 key_ 行
    SyncSession.java                     # 本轮快照 + 退出上交候选收集
    Submitter.java                       # 退出时打包并 POST /submit
    TarGzWriter.java                     # 纯 JDK ustar + gzip
    StateStore.java                      # 读写状态（含 profile）
  src/test/java/...                      # JUnit Jupiter 单测
```
