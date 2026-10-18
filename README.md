# MC Sync Server

[English](README.en.md) | 简体中文

[![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![Node](https://img.shields.io/badge/node-%3E%3D%2020-informational.svg)](https://nodejs.org/)

面向 Minecraft 整合包的同步服务：**服务端持有唯一权威副本，客户端在启动前自动对齐，退出时上交个人数据。**
项目内置 Web 管理台，新增 mod、修改配置、下发到正在运行的服务端实例、重启实例等操作均可在一个页面上完成。

> [!IMPORTANT]
> **本仓库代码全部由 AI 生成** —— 需求提出、方案审阅与真机验证由人工完成，代码本身由 AI 编写。
> 请将其视为实验性实现：部署到生产环境前，请自行审阅代码并做必要的测试。

## 概述

以数百个 mod 的大型整合包（如 GTNH）为例，新增一个 mod 通常需要分别维护服务端、文件仓库和每台玩家客户端中的多份副本，配置文件更需要逐台同步；与此同时，按键绑定、小地图一类玩家数据必须保留各自差异，不能被他人覆盖。

本项目把上述流程收敛为三步：

1. 在管理台上传 mod 或编辑配置，点击「同步到 MC 服务端」；
2. 玩家客户端挂载 agent，启动游戏前自动将本地实例对齐至服务端状态；
3. 玩家个人的按键绑定、地图数据在退出游戏时自动上交，由服务端**合并**（而非覆盖）。

## 主要特性

- **权威库集中管理**：mods、config、resourcepacks、scripts 等目录在网页端完成上传、标记、编辑与删除。
- **差异化的同步策略**：mod 按类型过滤，纯客户端 mod 不下发；config 仅覆盖两端同名的文件，不新增、不删除服务端专有文件。
- **变更预览与备份**：同步前先列出「新增 / 覆盖 / 删除」的数量与清单，确认后执行，被覆盖的旧文件留有备份。
- **可恢复的操作**：删除的文件进入回收站可一键恢复，配置文件保留历史版本可随时回滚。
- **客户端自更新**：客户端 agent 包由服务端分发并校验哈希，无需逐台手动替换 jar。
- **实例重启**：管理台可通过 MCSManager 面板 API 重启 MC 服务端实例。

## 组成

| 组件 | 职责 | 技术栈 |
| --- | --- | --- |
| **服务端** | 下发同步清单、按清单提供文件、接收客户端上交的并集档；同时提供管理台 API 与静态页面 | Node.js + TypeScript + Fastify |
| **管理台** | Web 界面：Mods 管理、config 在线编辑、其它目录、版本历史、回收站、同步到 MC 服务端、实例重启、客户端 agent 包分发 | Vue 3 + Vite（构建产物由服务端托管） |
| **客户端 agent** | 以 `-javaagent` 形式挂载在客户端 JVM：启动前按清单对齐本地文件，退出时上交并集档 | Java 17 |
| **自更新壳** | 体量很小、长期无需变更的引导 javaagent：启动时向服务端查询版本，自动替换为最新 agent；离线时沿用本地副本 | Java 17 |

同步接口与管理台由同一进程提供，因此**服务地址与管理台地址一致**。

## 项目状态

同步接口、管理台、客户端 agent 与自更新壳均已端到端可用，并在真实的 250 mod GTNH 实例上验证通过。
里程碑进度以 [`docs/PLAN.md`](docs/PLAN.md) 为准。

## 环境要求

- Node.js >= 20（服务端）
- JDK 17（仅在自行构建客户端 agent 时需要）
- Docker 或 systemd（生产部署，可选）
- MCSManager 面板（「重启实例」功能，可选）

## 快速开始

### 1. 构建并启动服务端

```bash
cd packages/server
npm install
npm run build
node dist/server.js --config /path/to/config.json
```

配置示例见 [`deploy/config.example.json`](deploy/config.example.json)。通常需要按实际情况修改以下字段：

| 字段 | 说明 |
| --- | --- |
| `repo_root` | 权威库根目录，内含 `mods/ config/ resourcepacks/ scripts/ …` |
| `listen` | 监听地址与端口 |
| `admin.token_file` | 管理台口令所在的文件 |
| `submit_token_file` | 客户端提交口令所在的文件 |

口令一律以文件形式提供，不写入配置、不进入版本库。启动日志会打印实际绑定的地址与端口。

### 2. 登录管理台

浏览器打开 `http://<地址>:<端口>/`，使用管理台口令登录。管理台包含四个页面：

| 页面 | 功能 |
| --- | --- |
| **Mods** | 上传 mod（名称与简介从 jar 中读取）、标记类型（两端均需 / 仅服务端 / 仅客户端）、编辑、删除（进入回收站） |
| **Config** | 在线编辑配置文件（行号、搜索），保存前自动留存旧版本，可随时还原 |
| **其它目录** | 管理资源包、脚本等目录，查看文件大小与修改时间，支持上传与删除 |
| **回收站** | 恢复已删除的文件；保留策略按天数与总容量双重控制 |

修改 mod 或配置后，点击 **「同步到 MC 服务端」**：界面会先列出「新增 / 覆盖 / 删除」的数量，确认后执行，被覆盖的旧文件留有备份。改动 mod 后需要**重启服务端实例**才会生效。

### 3. 接入客户端 agent

在客户端的 JVM 参数中追加一行（Prism Launcher / HMCL / 官方启动器一致）：

```
-javaagent:<agent.jar 路径>=<本机配置 JSON 路径>
```

- **agent.jar 可在所有机器间共用**；
- **配置 JSON 每台机器各一份**（`instancePath` 不同，其余字段可照抄）；
- 同一台机器切换账号无需更换 jar，agent 会读取启动器当前账号并匹配对应档案。

若希望后续版本无需手动分发 jar，可将上述路径中的 agent jar 替换为自更新壳 `gtnh-sync-bootstrap.jar`，配置文件保持不变。详见 [`packages/agent-bootstrap/README.md`](packages/agent-bootstrap/README.md)。

## 常用操作

| 目标 | 操作步骤 |
| --- | --- |
| 新增 / 替换 mod | 管理台 Mods 页上传 → 标记类型 → 同步到 MC 服务端 → 重启实例 |
| 修改配置 | Config 页在线编辑 → 保存 → 同步到 MC 服务端 → 重启实例 |
| 发布新版客户端 agent | 在 Agent 页上传带版本号的新 jar；挂载自更新壳的客户端下次启动时自动更新 |
| 恢复误删或改错的内容 | 回收站一键恢复；配置改错则在版本历史中还原 |
| 查看客户端版本情况 | Agent 页显示每台机器的客户端版本与最后在线时间 |

## 部署

生产环境可使用 Docker（[`deploy/Dockerfile`](deploy/Dockerfile) + [`deploy/docker-compose.yml`](deploy/docker-compose.yml)）或 systemd（[`deploy/mc-sync-server.service`](deploy/mc-sync-server.service)）。两种方式均要求 Node.js >= 20。

## 目录结构

```
packages/
├── server/           服务端：同步接口 + 管理台 API + 静态前端
├── web/              管理台前端（Vue 3 + Vite）
├── agent/            客户端 Java agent
└── agent-bootstrap/  自更新壳
deploy/               Dockerfile、docker-compose、systemd 单元、配置示例
docs/                 ARCHITECTURE.md（接口契约）、ADMIN_API.md（管理台接口）、SPEC.md、PLAN.md
```

## 开发与测试

```bash
# 服务端
cd packages/server && npm install && npm test && npm run build

# 管理台前端（构建产物由服务端托管）
cd packages/web && npm install && npm run build

# 客户端 agent（需要 JDK 17）
cd packages/agent && gradle build
```

接口以文档为准，修改接口前先更新文档：[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)（客户端同步协议）、[`docs/ADMIN_API.md`](docs/ADMIN_API.md)（管理台接口）。

## 许可证

本项目以 **GPL-3.0** 发布，详见 [`LICENSE`](LICENSE)。

客户端同步方案源自开源项目 [ServerSync](https://github.com/superzanti/ServerSync)（GPL-3.0），客户端 agent 的包名与协议形态沿用了该项目。按照 GPL-3.0 的要求，本仓库同样以 GPL-3.0 发布，在此向原作者致谢。
