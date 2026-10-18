# 里程碑

> 打勾 = 已做并验证。每步做完在对应行填日期 + 结果。

## M1 仓库骨架 + 架构/接口契约
- [x] 新建 `mc_sync_server` 仓库、目录、LICENSE（GPL-3.0）、README、.gitignore
- [x] `docs/ARCHITECTURE.md`（接口契约）、`docs/SPEC.md`（需求说明书）、`docs/PLAN.md`

## M2 服务端同步核心（Python → Node 迁移）
- [x] `packages/server`：config / core（manifest + submit + keyline 合并 + 快照 + 路径防护）/ server（Fastify 路由）
- [x] 24 单测全绿（tsx --test）
- [x] `tsc` 编译通过
- [x] 本地端到端冒烟：起真进程打真接口，`/health` `/manifest` `/f/` 200/404/403 全对（顺带修掉「启动完就 process.exit」的入口 bug；补了 CLI 集成测试，现 25 单测全绿）
- [x] 真机冒烟比对（用真实 GTNH 仓库 + 真 agent，与旧 Python 服务逐条比对 /manifest、/f/、/submit）——2026-10-18：清单 15996 文件零差异；真 agent `/submit` 实测 `HTTP 200 {"ok":true,...,"accepted":[...]}`

## M3 管理台后端
- [x] 元数据存储（mods 字段；预标 28 个纯客户端 mod 走 `admin.client_only_mods`）
- [x] 上传（mods 自动读 jar 名字/简介，读 mcmod.info / fabric.mod.json）、删除→回收站、类型三档
- [x] 刷新清单、同步到 MC 服务端（mods+config，先给 diff 再执行；复制保留时间戳）
- [x] config 在线编辑（留旧版可还原）
- [x] 其它目录浏览/上传/删除
- [x] 回收站（按时间 + 按大小配置化）、版本历史/还原
- [x] agent 包管理（上传/版本/公开取包端点）+ 客户端版本上报
- [x] 口令登录（cookie）
- [x] 重启 MC 服务端实例（MCSManager API）
- [x] 14 单测（与同步 26 个合计 40 全绿）

## M4 管理台前端（Vue 3）
- [x] 四页（Mods / Config / 其它目录 / 回收站）+ 动作（上传/同步/重启/历史）+ 登录页
- [x] Element Plus + CodeMirror；Vite 构建成静态文件由服务端发；真浏览器联调四页全通过

## M5 客户端 Java agent
- [x] 迁入 `packages/agent`（独立 Gradle 工程，只依赖 minimal-json）
- [x] 自更新壳 `packages/agent-bootstrap`（B 方案，断网兜底）+ 版本上报（`X-Agent-Version` / `?machine=`）
- [x] 在 PC 上构建：agent 编译 + 67 测试全绿；自更新壳 javac 通过
- [x] 真机冒烟：台式机已挂自更新壳跑真同步（2026-10-18：壳自动升到 0.3.0、同步 + `/submit` 200；断网兜底也验了：连不上服务端就用本地 jar 照常进游戏）。笔记本离线，待开机
- [x] 0.3.1：修档案名判定（stdin 里不认识的账号值不再当档案名用，改以 Prism 账号库为准）——真实启动实测 stdin 那个位置是占位垃圾值 `param`，曾导致两台机器串进同一档案

## M6 部署与验收
- [x] Dockerfile + compose + systemd unit + 配置样例（`deploy/`）
- [x] E3 并排试跑（38081）：真机比对清单逐条一致；管理台后端+前端接口走通；实例目录授权（User=root）；MCSManager key 已接
- [x] 中英文 README（README.md + README.en.md）
- [x] 重启按钮打通 MCSManager：走**面板** API（默认 80 端口）+ `apikey` 走 URL 参数 + 必带 `X-Requested-With: XMLHttpRequest`、`Content-Type: application/json; charset=utf-8`；只认 `status===200`
- [x] 整轮验收：E3 并排试跑全通；台式机已切到新服务端（38081）。**笔记本仍指旧 38080**——老服务保留到笔记本换过来为止

## M7 下一批（2026-10-18 用户提出，待开工）

- [ ] **Config 页性能**：`GET /api/config/tree` 现在一次性递归读全树（1.5 万文件），实测一轮 **40 多秒**。改成**按目录懒加载**（点开一级读一级），首次进页只读顶层。
- [ ] **config 的“统一 vs 各人各样”策略**：现在 `config/` 整棵树都是“全服统一”（客户端改动会被服务端那份覆盖回去）。但 `config/` 里混着两类文件：模组行为开关（必须统一）与 HUD/界面/布局（本来就该各人各样，如 Waila、Game Info XML）。要做成**可按目录/文件选策略**：全服统一 / 每人一份（union）/ 按项合并（line-union）——后两种模式清单协议里已有（`dirs` 规则），只是没用在 config 上。管理台里可点选。
- [ ] **管理台补「Agent 包」页**：上传带版本号的新 jar + 列出每台客户端的版本/档案/最后在线时间。**现在只有后端接口（`/api/agent*`），前端没有这一页**（README 已超前写成有这页，需对齐）。
- [ ] 笔记本换壳 + 指向新服务端（待其开机）；之后老 Python 服务端 38080 可下线。
