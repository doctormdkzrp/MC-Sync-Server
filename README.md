# MC-Sync-Server（文件柜）

> Minecraft 整合包「一处改、处处生效」：**服务器是唯一真相，客户端进游戏前自动对齐。**
> 加个 mod、改个配置，在网页上点几下就行——不用再挨台机器、挨个目录手动放文件。

拿 GTNH 这种几百个 mod 的大整合包举例，原来加一个 mod 得干这些活：
服务端 mods 里放一份、文件柜里放一份、每个玩家的 `.minecraft/mods` 里放一份；
配置改了更麻烦，得挨台同步；而按键绑定、小地图这种**每人本来就该不一样**的东西，又不能让谁把谁覆盖了。

这个项目把这些活收敛成三件事：

1. 往**管理台网页**上传 mod、点一下类型、点「同步到服务端」；
2. 玩家客户端挂着 agent，进游戏前自动把本地对齐成服务端的样子；
3. 玩家自己改的按键、跑出来的地图，退出游戏时自动上交，服务端**合并**（不是覆盖）。

## 三块组成

| 组成 | 干什么 | 技术 |
|---|---|---|
| **服务端** | 给客户端发「清单」、按清单取文件、收客户端上交的「并集档」；顺带把管理台网页也发出来 | Node + TypeScript + Fastify |
| **管理台** | 网页。Mods 上传/标记/回收站、config 在线编辑、其它目录、版本历史、同步到 MC 服务端、重启实例、客户端 agent 包分发 | Vue 3 + Vite（构建产物由服务端一起发） |
| **客户端 agent** | 挂在 Minecraft 的 JVM 上：进服前照清单对齐本地文件，退出时上交并集档 | Java 17（`packages/agent`） |
| **自更新壳** | 几十行、永远不用换的小 javaagent。开机先从服务端问「有没有新版客户端」，有就自动拉下来再跑；断网就用上一次那份 | Java 17（`packages/agent-bootstrap`） |

一个服务干两件事（客户端同步接口 + 管理台），所以**服务地址 = 管理台地址**。

## 快速上手

### 1. 起服务端

```bash
cd packages/server
npm install
npm run build
node dist/server.js --config /path/to/config.json
```

配置照抄 `deploy/config.example.json`。三个地方按你的机器改：

- `repo_root`：**权威库**（文件柜）放哪，里面是 `mods/ config/ resourcepacks/ scripts/ …`
- `listen`：监听地址和端口
- `admin.token_file` / `submit_token_file`：**口令放文件里**，不写进配置、不进 git

生产环境用 Docker（`deploy/Dockerfile` + `deploy/docker-compose.yml`）或 systemd（`deploy/mc-sync-server.service`）都行，需要 Node ≥ 20。

### 2. 打开管理台

浏览器打开 `http://<你的地址>:<端口>/`，用管理台口令登录。四页：

- **Mods** —— 上传（名字/简介自动从 jar 里读）、标类型（两边都要 / 服务端也要 / 只有客户端）、编辑、删（进回收站）；
- **Config** —— 在线编辑（带行号、可搜索），保存前自动留旧版，随时能还原；
- **其它目录** —— 资源包、脚本这些也管上了，能看大小/时间、能上传/删除；
- **回收站** —— 删错的东西都在这儿，一键恢复；保留策略按天数 + 按大小双控。

改完 mod/配置，点 **「同步到 MC 服务端」**：它会先把「新增 X 个 / 覆盖 Y 个 / 删除 Z 个」摆给你看，你确认了才动手，被覆盖的旧文件都留底。改完 mod 要**重启服务端实例**才生效。

### 3. 客户端挂 agent

给玩家的 JVM 加一行参数（Prism / HMCL / 官方启动器都一样）：

```
-javaagent:<agent.jar 路径>=<本机配置 json 路径>
```

- **jar 全机器共用一份**；
- **配置 json 每台机器各自一份**（`instancePath` 不同，其余可照抄）；
- 多账号不用换 jar：agent 认启动器里当前账号，自动找对应档案。

想以后换新版不用手动发 jar，就把上面那行的 `agent.jar` 换成 `gtnh-sync-bootstrap.jar`（自更新壳），配置 json 还是原来那份。见 `packages/agent-bootstrap/README.md`。

## 日常怎么干活

| 想干的事 | 怎么做 |
|---|---|
| 加 / 换 mod | 管理台 Mods 页上传 → 点类型 → 「同步到 MC 服务端」→ 重启实例 |
| 改某个配置 | Config 页点开在线改 → 保存 → 「同步到 MC 服务端」→ 重启实例 |
| 换客户端 agent 新版 | 「Agent 包」上传新 jar（带版本号）→ 挂了自更新壳的客户端下次开机自动换 |
| 删错了东西 | 回收站一键恢复；改崩了配置就点「历史」还原 |
| 看谁没更新 | Agent 页显示每台机器的客户端版本和最后在线时间 |

## 目录结构

```
packages/
├── server/           服务端：同步接口 + 管理台 API + 静态前端
├── web/              管理台前端（Vue 3 + Vite）
├── agent/            客户端 Java agent
└── agent-bootstrap/  自更新壳
deploy/               Dockerfile、compose、systemd、配置样例
docs/                 ARCHITECTURE.md（接口契约）、ADMIN_API.md（管理台接口）、SPEC.md、PLAN.md
```

## 开发与测试

```bash
# 服务端
cd packages/server && npm install && npm test && npm run build

# 前端（构建产物给服务端发）
cd packages/web && npm install && npm run build

# 客户端 agent（需要 JDK 17）
cd packages/agent && gradle build
```

接口以文档为准，改接口先改文档：`docs/ARCHITECTURE.md`（客户端同步协议）、`docs/ADMIN_API.md`（管理台接口）。

## 许可

**GPL-3.0**，见 [`LICENSE`](LICENSE)。

本项目的客户端同步思路源自开源项目 [ServerSync](https://github.com/superzanti/ServerSync)（GPL-3.0）——
客户端 agent 的包名与协议形态沿用了它，按 GPL-3.0 的传染性要求，本仓库同样以 GPL-3.0 发布。在此向原作者致谢。

English README: [`README.en.md`](README.en.md)
