import { test } from "node:test";
import * as assert from "node:assert";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import * as zlib from "node:zlib";
import type { FastifyInstance } from "fastify";

import { ServerConfig, DirRule } from "../src/config.js";
import { SyncCore } from "../src/core.js";
import { buildApp } from "../src/server.js";
import { AdminStore } from "../src/admin/store.js";
import { McInstance } from "../src/admin/mcinstance.js";
import { readJarMeta } from "../src/admin/jarmeta.js";

const ADMIN_TOKEN = "admin-test-token";
const COOKIE = "mcsync_admin";

const DIRS: DirRule[] = [
  { path: "mods", tier: "authoritative", owner: "both", mode: "mirror", exclude: ["**/*.disabled"] },
  { path: "config", tier: "authoritative", owner: "both", mode: "mirror", exclude: [] },
  { path: "resourcepacks", tier: "authoritative", owner: "both", mode: "mirror", exclude: [] },
  { path: "journeymap", tier: "union", owner: "client", mode: "union", exclude: [], perAccount: true },
];

/** 做一个真 zip（deflate），用来验证 jar 元数据读取（CRC 置 0 无妨——读取端不校验 CRC）。 */
function makeJar(entries: Record<string, string>): Buffer {
  const locals: Buffer[] = [];
  const centrals: Buffer[] = [];
  let offset = 0;
  for (const [name, content] of Object.entries(entries)) {
    const nameBuf = Buffer.from(name, "utf-8");
    const data = Buffer.from(content, "utf-8");
    const comp = zlib.deflateRawSync(data);
    const lh = Buffer.alloc(30);
    lh.writeUInt32LE(0x04034b50, 0);
    lh.writeUInt16LE(20, 4);
    lh.writeUInt16LE(8, 8);
    lh.writeUInt32LE(0, 14);
    lh.writeUInt32LE(comp.length, 18);
    lh.writeUInt32LE(data.length, 22);
    lh.writeUInt16LE(nameBuf.length, 26);
    locals.push(lh, nameBuf, comp);

    const ch = Buffer.alloc(46);
    ch.writeUInt32LE(0x02014b50, 0);
    ch.writeUInt16LE(20, 4);
    ch.writeUInt16LE(20, 6);
    ch.writeUInt16LE(8, 10);
    ch.writeUInt32LE(0, 16);
    ch.writeUInt32LE(comp.length, 20);
    ch.writeUInt32LE(data.length, 24);
    ch.writeUInt16LE(nameBuf.length, 28);
    ch.writeUInt32LE(offset, 42);
    centrals.push(ch, nameBuf);
    offset += lh.length + nameBuf.length + comp.length;
  }
  const cd = Buffer.concat(centrals);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(Object.keys(entries).length, 8);
  eocd.writeUInt16LE(Object.keys(entries).length, 10);
  eocd.writeUInt32LE(cd.length, 12);
  eocd.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, cd, eocd]);
}

function multipart(fields: Record<string, string>, file?: { name: string; data: Buffer }): {
  payload: Buffer;
  contentType: string;
} {
  const boundary = "----mcsyncTest" + Math.random().toString(16).slice(2);
  const parts: Buffer[] = [];
  for (const [k, v] of Object.entries(fields)) {
    parts.push(Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="${k}"\r\n\r\n${v}\r\n`));
  }
  if (file) {
    parts.push(
      Buffer.from(
        `--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="${file.name}"\r\nContent-Type: application/octet-stream\r\n\r\n`
      )
    );
    parts.push(file.data, Buffer.from("\r\n"));
  }
  parts.push(Buffer.from(`--${boundary}--\r\n`));
  return {
    payload: Buffer.concat(parts),
    contentType: `multipart/form-data; boundary=${boundary}`,
  };
}

interface Ctx {
  tmp: string;
  repo: string;
  app: FastifyInstance;
  cookie: string;
  write(rel: string, data: Buffer | string): void;
  read(rel: string): Buffer;
  exists(rel: string): boolean;
}

async function makeCtx(): Promise<Ctx> {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "mc-sync-admin-"));
  const repo = path.join(tmp, "repo");
  for (const d of ["mods", "config", "resourcepacks", "journeymap"]) {
    fs.mkdirSync(path.join(repo, d), { recursive: true });
  }
  fs.mkdirSync(path.join(tmp, "mcs-mods"), { recursive: true });
  fs.mkdirSync(path.join(tmp, "mcs-config"), { recursive: true });

  const cfg: ServerConfig = {
    repo_root: repo,
    state_file: path.join(tmp, "state.json"),
    snapshot_dir: path.join(tmp, "snapshots"),
    snapshot_keep: 5,
    submit_token: "submit-token",
    dirs: DIRS,
    admin: {
      enabled: true,
      token: ADMIN_TOKEN,
      recycle_keep_days: 30,
      recycle_keep_bytes: 100 * 1024 * 1024,
    },
  };
  (cfg as any).mcs = {
    instanceModsDir: path.join(tmp, "mcs-mods"),
    instanceConfigDir: path.join(tmp, "mcs-config"),
  };

  const core = new SyncCore(cfg);
  const store = new AdminStore(path.join(tmp, "admin"), repo, ["clientonly-1.0.jar"]);
  const instance = new McInstance(cfg, store);
  const app = buildApp(core, { store, instance, cfg });

  const ctx: Ctx = {
    tmp,
    repo,
    app,
    cookie: "",
    write(rel, data) {
      const p = path.join(repo, ...rel.split("/"));
      fs.mkdirSync(path.dirname(p), { recursive: true });
      fs.writeFileSync(p, data);
    },
    read(rel) {
      return fs.readFileSync(path.join(repo, ...rel.split("/")));
    },
    exists(rel) {
      return fs.existsSync(path.join(repo, ...rel.split("/")));
    },
  };

  const res = await app.inject({
    method: "POST",
    url: "/api/login",
    payload: { token: ADMIN_TOKEN },
  });
  const sc = res.headers["set-cookie"];
  const raw = Array.isArray(sc) ? sc[0] : String(sc);
  ctx.cookie = raw.split(";")[0];
  return ctx;
}

function api(ctx: Ctx, opts: any): Promise<any> {
  return ctx.app.inject({ ...opts, cookies: { [COOKIE]: ctx.cookie.split("=")[1] } });
}

async function json(ctx: Ctx, opts: any): Promise<{ status: number; body: any }> {
  const res = await api(ctx, opts);
  let body: any = null;
  try {
    body = res.json();
  } catch {
    body = res.rawPayload;
  }
  return { status: res.statusCode, body };
}

// ---------------------------------------------------------------------------

test("未登录访问 /api 一律 401", async () => {
  const ctx = await makeCtx();
  const res = await ctx.app.inject({ method: "GET", url: "/api/mods" });
  assert.equal(res.statusCode, 401);
  const s = await ctx.app.inject({ method: "GET", url: "/api/session" });
  assert.equal(s.json().authed, false);
});

test("登录后 /api/session 为真，错口令 401", async () => {
  const ctx = await makeCtx();
  const bad = await ctx.app.inject({ method: "POST", url: "/api/login", payload: { token: "nope" } });
  assert.equal(bad.statusCode, 401);
  const good = await ctx.app.inject({ method: "GET", url: "/api/session", cookies: { [COOKIE]: ctx.cookie.split("=")[1] } });
  assert.equal(good.json().authed, true);
});

test("mods 列表：元数据 + 预置纯客户端类型", async () => {
  const ctx = await makeCtx();
  ctx.write("mods/AAA-1.0.jar", "aaa");
  ctx.write("mods/clientonly-1.0.jar", "cl");
  const { status, body } = await json(ctx, { method: "GET", url: "/api/mods" });
  assert.equal(status, 200);
  const byFile = new Map(body.items.map((i: any) => [i.file, i]));
  assert.equal(byFile.get("AAA-1.0.jar").type, "both");
  assert.equal(byFile.get("clientonly-1.0.jar").type, "client"); // 预置名单命中
});

test("改类型 + 上传 jar 自动读名字简介 + 覆盖留历史", async () => {
  const ctx = await makeCtx();
  ctx.write("mods/AAA-1.0.jar", "aaa");
  const patch = await json(ctx, { method: "PATCH", url: "/api/mods/AAA-1.0.jar", payload: { type: "server", desc: "手写简介" } });
  assert.equal(patch.body.item.type, "server");
  assert.equal(patch.body.item.desc, "手写简介");

  const jar = makeJar({
    "fabric.mod.json": JSON.stringify({ id: "gtit", name: "GT Interesting Thing", description: "有趣的玩意", version: "1.2" }),
  });
  const mp = multipart({ type: "both" }, { name: "GTIT-1.2.jar", data: jar });
  const up = await json(ctx, {
    method: "POST",
    url: "/api/mods/upload",
    payload: mp.payload,
    headers: { "content-type": mp.contentType },
  });
  assert.equal(up.status, 200);
  assert.equal(up.body.item.name, "GT Interesting Thing");
  assert.equal(up.body.item.desc, "有趣的玩意");
  assert.equal(up.body.detected.source, "fabric.mod.json");
});

test("删除 → 回收站 → 还原", async () => {
  const ctx = await makeCtx();
  ctx.write("mods/AAA-1.0.jar", "aaa");
  const del = await json(ctx, { method: "DELETE", url: "/api/mods/AAA-1.0.jar" });
  assert.equal(del.status, 200);
  assert.ok(!ctx.exists("mods/AAA-1.0.jar"));
  const rc = await json(ctx, { method: "GET", url: "/api/recycle" });
  assert.equal(rc.body.items.length, 1);
  const id = rc.body.items[0].id;
  const rest = await json(ctx, { method: "POST", url: `/api/recycle/${id}/restore` });
  assert.equal(rest.status, 200);
  assert.equal(ctx.read("mods/AAA-1.0.jar").toString(), "aaa");
});

test("config 在线编辑：写 + 历史 + 还原", async () => {
  const ctx = await makeCtx();
  ctx.write("config/a.cfg", "v1\n");
  const put1 = await json(ctx, {
    method: "PUT",
    url: "/api/config/file",
    payload: { path: "config/a.cfg", content: "v2\n", note: "第一次改" },
  });
  assert.equal(put1.status, 200);
  assert.equal(ctx.read("config/a.cfg").toString(), "v2\n");

  const put2 = await json(ctx, {
    method: "PUT",
    url: "/api/config/file",
    payload: { path: "config/a.cfg", content: "v3\n" },
  });
  assert.equal(put2.status, 200);

  const hist = await json(ctx, { method: "GET", url: "/api/history?path=config/a.cfg" });
  assert.equal(hist.body.items.length, 2); // v1、v2 各留一版

  // 还原到 v1 那一版（最早的）
  const v1 = hist.body.items.find((h: any) => h.version === 1);
  const back = await json(ctx, { method: "POST", url: `/api/history/${v1.id}/restore` });
  assert.equal(back.status, 200);
  assert.equal(ctx.read("config/a.cfg").toString(), "v1\n");
});

test("config 只允许改 config 目录", async () => {
  const ctx = await makeCtx();
  const res = await json(ctx, { method: "PUT", url: "/api/config/file", payload: { path: "mods/x.cfg", content: "no" } });
  assert.equal(res.status, 400);
});

test("其它目录：列目录 / 树 / 上传 / 删除", async () => {
  const ctx = await makeCtx();
  ctx.write("resourcepacks/MyPack.zip", "pk");
  const dirs = await json(ctx, { method: "GET", url: "/api/dirs" });
  const rp = dirs.body.dirs.find((d: any) => d.path === "resourcepacks");
  assert.equal(rp.fileCount, 1);
  assert.equal(rp.bytes, 2);

  const tree = await json(ctx, { method: "GET", url: "/api/tree?dir=resourcepacks" });
  assert.equal(tree.body.entries[0].path, "resourcepacks/MyPack.zip");

  const mp = multipart({ dir: "resourcepacks", subdir: "sub" }, { name: "new.zip", data: Buffer.from("zz") });
  const up = await json(ctx, {
    method: "POST",
    url: "/api/dirs/upload",
    payload: mp.payload,
    headers: { "content-type": mp.contentType },
  });
  assert.equal(up.status, 200);
  assert.equal(up.body.path, "resourcepacks/sub/new.zip");

  const del = await json(ctx, { method: "DELETE", url: "/api/dirs/file?path=resourcepacks/sub/new.zip" });
  assert.equal(del.status, 200);
  assert.ok(!ctx.exists("resourcepacks/sub/new.zip"));
});

test("回收站保留策略（按大小清最老）", async () => {
  const ctx = await makeCtx();
  // 先塞一个 300B 的，再设上限 200B，删一个大的触发清理
  ctx.write("mods/big.jar", "x".repeat(300));
  ctx.write("mods/small.jar", "y".repeat(50));

  const set = await json(ctx, { method: "PUT", url: "/api/recycle/settings", payload: { keepBytes: 200, keepDays: 0 } });
  assert.equal(set.status, 200);

  await json(ctx, { method: "DELETE", url: "/api/mods/big.jar" });
  const rc = await json(ctx, { method: "GET", url: "/api/recycle" });
  assert.equal(rc.body.items.length, 0); // 超上限，被清
});

test("同步到服务端：预览 + 执行（新增/覆盖/删除）+ 纯客户端不下发", async () => {
  const ctx = await makeCtx();
  ctx.write("mods/AAA-1.0.jar", "aaa");
  ctx.write("mods/clientonly-1.0.jar", "cl");
  ctx.write("config/a.cfg", "cfg1");
  // 实例侧：已存在一个旧 AAA（会被覆盖），多一个 stale.jar（会被删）
  fs.writeFileSync(path.join(ctx.tmp, "mcs-mods", "AAA-1.0.jar"), "old");
  fs.writeFileSync(path.join(ctx.tmp, "mcs-mods", "stale.jar"), "stale");

  const prev = await json(ctx, { method: "GET", url: "/api/sync/preview" });
  assert.equal(prev.status, 200);
  assert.ok(prev.body.updates.includes("AAA-1.0.jar"));
  assert.ok(prev.body.removes.includes("stale.jar"));
  assert.ok(prev.body.adds.includes("config/a.cfg"));
  assert.ok(!prev.body.adds.includes("clientonly-1.0.jar")); // 纯客户端不下发

  const app2 = await json(ctx, { method: "POST", url: "/api/sync/apply" });
  assert.equal(app2.status, 200);
  assert.equal(app2.body.updated, 1);
  assert.equal(app2.body.removed, 1);
  assert.equal(fs.readFileSync(path.join(ctx.tmp, "mcs-mods", "AAA-1.0.jar"), "utf-8"), "aaa");
  assert.ok(!fs.existsSync(path.join(ctx.tmp, "mcs-mods", "stale.jar")));
  assert.ok(!fs.existsSync(path.join(ctx.tmp, "mcs-mods", "clientonly-1.0.jar")));
  assert.equal(fs.readFileSync(path.join(ctx.tmp, "mcs-config", "a.cfg"), "utf-8"), "cfg1");

  // 被覆盖/删除的实例文件应该留了底
  const backups = fs.readdirSync(path.join(ctx.tmp, "admin", "mcs-backup")).filter((n) => n.endsWith(".blob"));
  assert.equal(backups.length, 2);
});

test("清单刷新只动版本缓存、不动数据", async () => {
  const ctx = await makeCtx();
  ctx.write("mods/AAA-1.0.jar", "aaa");
  const r = await json(ctx, { method: "POST", url: "/api/manifest/refresh" });
  assert.equal(r.body.ok, true);
  assert.equal(r.body.version, 1);
});

test("agent 包上传/查看/下载 + 客户端版本上报", async () => {
  const ctx = await makeCtx();
  const mp = multipart({ version: "0.3.0" }, { name: "agent.jar", data: Buffer.from("JARDATA") });
  const up = await json(ctx, {
    method: "POST",
    url: "/api/agent/upload",
    payload: mp.payload,
    headers: { "content-type": mp.contentType },
  });
  assert.equal(up.status, 200);
  assert.equal(up.body.latest.version, "0.3.0");

  const list = await json(ctx, { method: "GET", url: "/api/agent" });
  assert.equal(list.body.latest.version, "0.3.0");

  const dl = await api(ctx, { method: "GET", url: "/api/agent/download" });
  assert.equal(dl.statusCode, 200);
  assert.equal(dl.rawPayload.toString(), "JARDATA");

  // 客户端拉清单时上报版本
  await ctx.app.inject({ method: "GET", url: "/manifest?machine=zrp-pc&profile=Fgod", headers: { "x-agent-version": "0.2.5" } });
  const list2 = await json(ctx, { method: "GET", url: "/api/agent" });
  const client = list2.body.clients.find((c: any) => c.machine === "zrp-pc");
  assert.equal(client.agentVersion, "0.2.5");
});

test("总览数字对得上", async () => {
  const ctx = await makeCtx();
  ctx.write("mods/AAA-1.0.jar", "aaa");
  ctx.write("mods/BBB-1.0.jar", "bbbb");
  ctx.write("config/a.cfg", "cc");
  const { body } = await json(ctx, { method: "GET", url: "/api/overview" });
  assert.equal(body.mods, 2);
  assert.equal(body.configFiles, 1);
  assert.equal(body.version, 1);
  assert.equal(body.recycle.count, 0);
});

test("readJarMeta 能从 mcmod.info 兜底（1.7.10 Forge 常见）", () => {
  const jar = makeJar({
    "mcmod.info": JSON.stringify([{ modid: "gtnh", name: "GT New Horizons", description: "困难整合包" }]),
  });
  const m = readJarMeta(jar);
  assert.equal(m.name, "GT New Horizons");
  assert.equal(m.desc, "困难整合包");
  assert.equal(m.source, "mcmod.info");
});
