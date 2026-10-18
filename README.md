# mc_sync_server

一套「服务器说了算」的 Minecraft modpack 配置同步与发布管理台。

- **服务端**（Node + TypeScript + Fastify）：给客户端发清单/取文件/收并集档，同时挂一个网页管理台。
- **客户端**（Java agent）：用 `-javaagent:` 挂进 JVM，进服前照清单对齐、退出时上交并集档（按键/地图）。
- **管理台**（Vue 3）：Mods 带标记管理、上传/回收站、同步到 MC 服务端、config 在线编辑、其它目录、版本历史、agent 包管理。

> 状态：**施工中**。当前已完成服务端同步核心（协议 2，与 Python v0.2 行为一致，24 单测全绿）。管理台前端与后端尚待实现。
>
> 上游致谢：本项目的客户端同步思路源自开源项目 **ServerSync**（GPL-3.0），特此致谢。本仓库为独立实现，仅保留思路与协议兼容。

## 目录

```
mc_sync_server/
├── packages/
│   ├── server/   # 服务端：同步接口 + 管理台 API + 静态前端（Node + TS + Fastify）
│   ├── web/      # 管理台前端（Vue 3 + Vite）
│   └── agent/    # 客户端 Java agent（-javaagent）
├── docs/         # 架构、接口契约、需求说明书、计划
└── deploy/       # Docker、systemd、配置样例
```

## 快速开始（服务端，开发态）

```bash
cd packages/server
npm install
npm test          # 24 个单测
npm run build     # tsc 编译到 dist/
node dist/server.js --config path/to/config.json
```

生产部署走 Docker（见 `deploy/`，待补）。

## 文档

- `docs/ARCHITECTURE.md` — 架构与接口契约（施工口径）。
- `docs/SPEC.md` — 需求说明书（含拍板结果）。
- `docs/PLAN.md` — 里程碑。
