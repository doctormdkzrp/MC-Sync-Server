# 管理台接口契约（/api/*）

> 前端与后端按此实现，谁也别自作主张改字段。认证：cookie（`mcsync_admin`），除 `/api/login`、`/api/session` 外都要带。
> 返回一律 JSON；出错 `{ "error": "..." }` + 相应状态码（400 参数错 / 401 未登录 / 404 没有 / 409 冲突）。

## 认证

| 方法 | 路径 | 请求 | 回应 |
|---|---|---|---|
| POST | `/api/login` | `{"token":"..."}` | `{"ok":true}`（Set-Cookie） |
| POST | `/api/logout` | — | `{"ok":true}` |
| GET | `/api/session` | — | `{"authed":true/false}` |

## 总览

| GET | `/api/overview` | — | `{"version":N,"mods":N,"configFiles":N,"repoBytes":N,"lastSubmit":{"at":..,"profile":"Fgod"},"recycle":{"count":N,"bytes":N},"instance":{"running":true}}` |

## Mods

| 方法 | 路径 | 请求 | 回应 |
|---|---|---|---|
| GET | `/api/mods` | `?q=&type=` | `{"items":[ item ]}` |
| PATCH | `/api/mods/:file` | `{"name"?,"desc"?,"type"?}` | `{"item": item}` |
| DELETE | `/api/mods/:file` | — | `{"ok":true,"recycleId":"..."}` |
| POST | `/api/mods/upload` | multipart: `file`(必), `name?`, `desc?`, `type?` | `{"item": item, "detected":{"name":..,"desc":..}}` |

`item` = `{"file":"AAA-1.0.jar","dir":"mods","name":"...","desc":"...","type":"both|server|client","size":N,"sha256":"...","mtime":"ISO","uploadedAt":"ISO","hasHistory":true}`

- 类型三档：`both`(两边都要,默认) / `server`(服务端也要) / `client`(只有客户端)。
- 上传不填 name/desc 时，后端尽力从 jar 里读（fabric.mod.json → mcmod.info → META-INF/MANIFEST.MF）。
- 「28 个纯客户端 mod」预置在服务端配置里，命中即默认 `type=client`。

## 其它目录

| 方法 | 路径 | 请求 | 回应 |
|---|---|---|---|
| GET | `/api/dirs` | — | `{"dirs":[{"path":"resourcepacks","tier":"authoritative","owner":"both","mode":"mirror","fileCount":N,"bytes":N}]}` |
| GET | `/api/tree` | `?dir=<dir>` | `{"dir":"...","entries":[{"path":"repo-相对路径","name":"x.png","size":N,"mtime":"ISO","sha256":"..."}]}` |
| POST | `/api/dirs/upload` | multipart: `dir`, `file`, `subdir?` | `{"ok":true,"path":"..."} ` |
| DELETE | `/api/dirs/file` | `?path=<repo相对路径>` | `{"ok":true,"recycleId":"..."}` |

## Config 在线编辑

| 方法 | 路径 | 请求 | 回应 |
|---|---|---|---|
| GET | `/api/config/tree` | — | `{"entries":[{"path":"config/x.cfg","name":"x.cfg","size":N,"mtime":..}]}` |
| GET | `/api/config/file` | `?path=config/x.cfg` | `{"path":..,"content":"...","size":N,"mtime":..,"binary":false}` |
| PUT | `/api/config/file` | `{"path":"config/x.cfg","content":"...","note"?}` | `{"ok":true,"historyId":"..."}` |

- 只允许改 `config/` 下的文本文件（含 service config 目录，见配置里的 `config_dirs`）。
- 保存前自动留旧版（进历史）；二进制/超大文件（>2MB）返回 `binary:true` 只读。

## 回收站

| 方法 | 路径 | 请求 | 回应 |
|---|---|---|---|
| GET | `/api/recycle` | — | `{"items":[{"id":..,"path":..,"kind":"mod|file","size":N,"deletedAt":..,"deletedBy":..,"note":..}],"settings":{"keepDays":30,"keepBytes":2147483648,"usedBytes":N}}` |
| POST | `/api/recycle/:id/restore` | — | `{"ok":true,"path":..}` |
| DELETE | `/api/recycle/:id` | — | `{"ok":true}` |
| POST | `/api/recycle/purge` | — | `{"ok":true,"removed":N}` |
| PUT | `/api/recycle/settings` | `{"keepDays":N,"keepBytes":N}` | `{"settings":{...}}` |

## 版本历史

| 方法 | 路径 | 请求 | 回应 |
|---|---|---|---|
| GET | `/api/history` | `?path=<repo相对>` | `{"items":[{"id":..,"path":..,"version":N,"size":N,"savedAt":..,"savedBy":..,"note":..}]}` |
| GET | `/api/history/:id/content` | — | 原文件字节（用于对比/预览） |
| POST | `/api/history/:id/restore` | — | `{"ok":true,"path":..}` |

## 清单刷新 / 同步到 MC 服务端 / 实例

| 方法 | 路径 | 请求 | 回应 |
|---|---|---|---|
| POST | `/api/manifest/refresh` | — | `{"ok":true,"version":N}` |
| GET | `/api/sync/preview` | — | `{"target":{...},"adds":[...],"updates":[...],"removes":[...],"unchanged":N,"configSkippedAdds":[...],"configSkippedRemoves":[...]}` |
| POST | `/api/sync/apply` | — | `{"ok":true,"added":N,"updated":N,"removed":N,"version":N}` |
| GET | `/api/instance/status` | — | `{"running":true,"instance":"...","mcVersion":"..."}` |
| POST | `/api/instance/restart` | — | `{"ok":true,"message":"..."}` |

- `sync/apply` 处理「两边都要 + 服务端也要」的 mod（新增/覆盖/删除），以及 `config/` 里**两边都有的文件**（只覆盖，不新增不删除）。
- 仓库 `config/` 是客户端全量（上万文件），服务端 `config/` 是它的子集 + 若干服务端专属文件；所以 config 不整树镜像：服务端没有的进 `configSkippedAdds`（不推），服务端独有的进 `configSkippedRemoves`（不动）。
- 被覆盖/删除的实例文件先留底（`mcs-backup/`）。

## Agent 包

| 方法 | 路径 | 请求 | 回应 |
|---|---|---|---|
| GET | `/api/agent` | — | `{"latest":{"version":"0.3.0","sha256":..,"size":N,"uploadedAt":..},"clients":[{"machine":"..","profile":"..","agentVersion":"..","lastSeen":..}]}` |
| POST | `/api/agent/upload` | multipart: `file`(jar), `version` | `{"ok":true,"latest":{...}}` |
| GET | `/api/agent/download` | — | 最新 agent jar 原始字节 |

客户端版本上报：agent 每次 `/manifest` 请求带 `X-Agent-Version` 头 + `?machine=<主机名>`，服务端记下（内存 + 落盘）。

## 静态与健康

- `GET /health`、`GET /manifest`、`GET /f/*`、`POST /submit` = 客户端同步接口（协议 2，见 ARCHITECTURE.md）。
- 管理台前端构建产物由本服务在 `/` 下发（SPA，未命中路径回退 index.html）。
