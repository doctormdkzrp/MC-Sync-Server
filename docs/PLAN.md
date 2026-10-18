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
- [ ] 真机冒烟比对（用真实 GTNH 仓库 + 真 agent，与旧 Python 服务逐条比对 /manifest、/f/、/submit）

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
- [ ] 真机冒烟：把新 agent 壳挂到台式/笔记本跑一次真同步（笔记本离线，待开机）

## M6 部署与验收
- [x] Dockerfile + compose + systemd unit + 配置样例（`deploy/`）
- [x] E3 并排试跑（38081）：真机比对清单逐条一致；管理台后端+前端接口走通；实例目录授权（User=root）；MCSManager key 已接
- [x] 中英文 README（README.md + README.en.md）
- [ ] 重启按钮打通 MCSManager（daemon 打包版 API 路径未对上，先手动重启）
- [ ] 整轮验收 + 切换到 38080（旧 Python 服务保留，一句话可回退）
