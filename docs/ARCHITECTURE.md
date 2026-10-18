# 架构与接口契约

> 施工口径。改这里 = 改协议，客户端/服务端要一起改并重跑测试。

## 一、一句话

一个 Node 服务干两件事：① 给客户端发「清单」+ 收「并集档」（同步，协议 2）；② 挂一个网页管理台（人用的）。前端 Vue 3 构建成静态文件由同一个服务发出去。**服务器是唯一真相，客户端照抄。**

## 二、数据布局

```
repo_root/                          # 权威库（文件柜）
├── mods/  config/  resourcepacks/  scripts/ …   # 权威档（authoritative，人/管理台改）
├── profiles/<档案>/
│   ├── journeymap/  schematics/ …               # 并集档（union，客户端上交）
│   └── options.key.txt                          # 按键行并集（line-union）
└── state.json                      # 版本号
snapshots/                          # 每轮上交的 tar.gz + manifest.json（keep N）
```

- `tier=authoritative`：只有人/管理台能改，客户端只下。
- `tier=union`：客户端上交，服务端合并（并集）；`owner=server` 的 union 不接收上交。
- `mode=line-union`：单文件规则，上交的是「行」，服务端按键名合并（Minecraft `options.txt` 的 `key_` 行）。

## 三、客户端同步接口（协议 2）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | `{"ok":true,"version":N}` |
| GET | `/manifest?profile=<档案>&refresh=1` | 清单 JSON（缓存，`refresh` 强制重扫） |
| GET | `/f/<path>` | 返回仓库内该相对路径的原始字节（仅清单里出现过的路径） |
| POST | `/submit?profile=<档案>` | 上交 tar.gz，头 `X-Sync-Token` |
| GET | `/agent/version` | 最新 agent 包信息 `{version,sha256,size,uploadedAt}`（公开，自更新壳读） |
| GET | `/agent/latest.jar` | 最新 agent 包字节（公开） |

拉清单时客户端会带 `X-Agent-Version` 头和 `&machine=<主机名>`，管理台据此显示「哪台机器还在跑旧版」。

### 清单（manifest）结构

```jsonc
{
  "protocol": 2,
  "version": 7,
  "profile": "Fgod",
  "generated": "2026-10-18T00:00:00Z",
  "dirs": [
    { "path": "mods", "repoPath": "mods", "localPath": "mods",
      "tier": "authoritative", "owner": "both", "mode": "mirror" },
    { "path": "journeymap", "repoPath": "profiles/Fgod/journeymap", "localPath": "journeymap",
      "tier": "union", "owner": "client", "mode": "union", "perAccount": true },
    { "path": "options.txt", "repoPath": "profiles/Fgod/options.key.txt", "localPath": "options.txt",
      "tier": "union", "owner": "client", "mode": "line-union", "linePrefix": "key_", "perAccount": true }
  ],
  "files": [
    { "path": "mods/AAA-1.0.jar", "dir": "mods", "localPath": "mods/AAA-1.0.jar",
      "size": 123, "sha256": "…", "tier": "authoritative", "owner": "both" }
  ]
}
```

- `path` = 仓库内路径（客户端下载用）；`localPath` = 客户端实例内相对路径（落盘用）。
- 账号档案：`perAccount` 规则的 `repoPath` 前缀 `profiles/<档案>/`，`localPath` 不带前缀。

### 上交（submit）语义

- tar.gz 成员按「客户端实例内相对路径」命名，服务端按 union 规则映射到仓库路径。
- 权威档成员（如 `mods/hack.jar`）→ 拒绝，原因 `not-union-tier`；路径穿越/绝对路径 → 拒绝，绝不落盘。
- `line-union` 成员：按 `linePrefix` 取键名合并，上交的赢；无有效键行 → `no-key-lines`。
- 成功后版本号 +1、落快照、清清单缓存。

### 安全

- 客户端接口只认 `X-Sync-Token`；管理台另用一套口令（cookie）。
- `/f/` 只服务清单里出现过的路径，否则 404（不暴露存在性）；路径穿越 403/404。
- 面板不暴露公网，仅 Tailscale/内网可达。

## 四、管理台

已实现。**接口清单以 `docs/ADMIN_API.md` 为准**（最全；改接口先改它）。要点：

- 认证：`POST /api/login` → cookie `mcsync_admin`；除 `/api/login`、`/api/session` 外都要带。
- 出错统一 `{"error":"…"}` + 400（参数）/ 401（未登录）/ 404（找不到）/ 409（冲突）。
- 「同步到 MC 服务端」分两步：先 `POST /api/sync-to-server`（mode=preview）拿 diff，人确认后再带 `confirm` 执行。
- 前端源码 `packages/web`（Vue 3 + Vite），构建产物 `dist/` 由同一个服务静态发；所以服务地址 = 管理台地址。

## 五、配置（config.json）

```jsonc
{
  "repo_root": "/srv/gtnh-sync/repo",
  "state_file": "/var/lib/gtnh-sync/state.json",
  "snapshot_dir": "/var/lib/gtnh-sync/snapshots",
  "snapshot_keep": 20,
  "listen": { "host": "0.0.0.0", "port": 38080 },
  "submit_token_file": "/etc/gtnh-sync/submit.token",
  "dirs": [ /* 见测试里的 DIRS_PROFILE */ ],
  "admin": {
    "token_file": "/etc/gtnh-sync/admin.token",
    "recycle_keep_days": 30,
    "recycle_keep_bytes": 2147483648,
    "mc_instance_mods": "/opt/mcsmanager/…/mods",
    "mc_instance_config": "/opt/mcsmanager/…/config"
  }
}
```

## 六、部署

- Docker（`deploy/Dockerfile` + compose，待补）；systemd 直跑亦可。
- E3 上 Node 20.12.2（MCSManager 自带），目标 `engines: node>=20`。
