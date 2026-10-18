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
- [ ] 元数据存储（mods 字段；预标 28 个纯客户端 mod）
- [ ] 上传（mods 自动读 jar 名字/简介）、删除→回收站、类型三档
- [ ] 刷新清单、同步到 MC 服务端（mods+config，先 diff）
- [ ] config 在线编辑（留旧版）
- [ ] 其它目录浏览/上传/删除（大小/时间/谁动的）
- [ ] 回收站（按时间 + 按大小配置化）、版本历史/还原
- [ ] agent 包管理（B 方案：自更新壳）+ 客户端版本上报
- [ ] 口令登录（cookie）
- [ ] 重启 MC 服务端实例（MCSManager API）

## M4 管理台前端（Vue 3）
- [ ] 四页（Mods / Config / 其它目录 / 回收站）+ 动作（上传/同步/重启/历史）
- [ ] 组件库 + CodeMirror 编辑器；构建成静态文件由服务端发

## M5 客户端 Java agent
- [ ] 迁入 `packages/agent`（从旧仓库 build-agent）
- [ ] 自更新壳（B 方案，断网兜底）+ 版本上报
- [ ] 构建 + 真机冒烟

## M6 部署与验收
- [ ] Dockerfile + compose + 部署脚本
- [ ] E3 上线（Node 20 + systemd）、装正式 Node、实例目录授权、MCSManager API key
- [ ] 中英文 README
- [ ] 整轮验收：改 mod/config → 同步 → 重启 → 客户端更新，不碰 SSH/SFTP/MCS 面板
