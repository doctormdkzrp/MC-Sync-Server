import * as crypto from "node:crypto";
import * as fs from "node:fs";
import * as path from "node:path";
import type { FastifyInstance, FastifyReply, FastifyRequest } from "fastify";

import { ServerConfig, normPosix, resolveWithin } from "../config.js";
import { SyncCore } from "../core.js";
import { AdminStore, ModType } from "./store.js";
import { readJarMeta, nameFromFile } from "./jarmeta.js";
import { McInstance } from "./mcinstance.js";

const COOKIE = "mcsync_admin";
const MAX_UPLOAD = 512 * 1024 * 1024;
const TEXT_EDIT_MAX = 2 * 1024 * 1024;

function cookieValueFor(token: string): string {
  return crypto.createHmac("sha256", token).update("mcsync-admin-v1").digest("hex");
}

function safeEqual(a: string, b: string): boolean {
  const ba = Buffer.from(a);
  const bb = Buffer.from(b);
  if (ba.length !== bb.length) return false;
  return crypto.timingSafeEqual(ba, bb);
}

function sha256(buf: Buffer): string {
  return crypto.createHash("sha256").update(buf).digest("hex");
}

function listFiles(absDir: string): string[] {
  if (!fs.existsSync(absDir)) return [];
  const out: string[] = [];
  const walk = (rel: string) => {
    const dir = rel ? path.join(absDir, rel) : absDir;
    let entries: fs.Dirent[] = [];
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const de of entries) {
      if (de.name.startsWith(".")) continue;
      const child = rel ? `${rel}/${de.name}` : de.name;
      if (de.isDirectory()) walk(child);
      else if (de.isFile()) out.push(child);
    }
  };
  walk("");
  return out;
}

export interface AdminDeps {
  cfg: ServerConfig;
  core: SyncCore;
  store: AdminStore;
  instance: McInstance;
  webDist?: string;
}

export function registerAdmin(app: FastifyInstance, deps: AdminDeps): void {
  const { cfg, core, store, instance } = deps;
  const adminToken =
    cfg.admin?.token_file && fs.existsSync(cfg.admin.token_file)
      ? fs.readFileSync(cfg.admin.token_file, "utf-8").split(/\r?\n/)[0].trim()
      : cfg.admin?.token ?? "admin";
  const expectedCookie = cookieValueFor(adminToken);
  const configDirs = ((cfg.admin as any)?.config_dirs as string[]) ?? ["config"];

  app.register(async (scope) => {
    // ---- 认证 ----
    scope.addHook("onRequest", async (request: FastifyRequest, reply: FastifyReply) => {
      const url = request.raw.url ?? "/";
      if (!url.startsWith("/api/")) return;
      if (url.startsWith("/api/login") || url.startsWith("/api/session")) return;
      const c = (request as any).cookies?.[COOKIE];
      if (!c || !safeEqual(c, expectedCookie)) {
        reply.code(401).send({ error: "未登录" });
        return reply;
      }
      return;
    });

    scope.post("/api/login", async (request, reply) => {
      const body = (request.body ?? {}) as { token?: string };
      if (!body.token || !safeEqual(String(body.token), adminToken)) {
        return reply.code(401).send({ error: "口令不对" });
      }
      reply.setCookie(COOKIE, expectedCookie, {
        path: "/",
        httpOnly: true,
        sameSite: "lax",
        maxAge: 60 * 60 * 24 * 30,
      });
      return { ok: true };
    });

    scope.post("/api/logout", async (_request, reply) => {
      reply.clearCookie(COOKIE, { path: "/" });
      return { ok: true };
    });

    scope.get("/api/session", async (request) => {
      const c = (request as any).cookies?.[COOKIE];
      return { authed: !!c && safeEqual(c, expectedCookie) };
    });

    // ---- 总览 ----
    scope.get("/api/overview", async () => {
      const repoRoot = core.repoRoot;
      const modsDir = path.join(repoRoot, "mods");
      const configDir = path.join(repoRoot, "config");
      const count = (d: string) => listFiles(d).length;
      const bytes = (d: string) => listFiles(d).reduce((s, r) => s + fs.statSync(path.join(d, ...r.split("/"))).size, 0);
      let running = false;
      try {
        running = (await instance.status()).running;
      } catch {
        running = false;
      }
      const lastManifest = core.getManifest("default");
      return {
        version: core.version,
        mods: count(modsDir),
        configFiles: count(configDir),
        repoBytes: bytes(modsDir) + bytes(configDir),
        lastSubmit: { at: lastManifest.generated, profile: lastManifest.profile },
        recycle: { count: store.recycleList().length, bytes: store.recycleUsedBytes() },
        instance: { running },
      };
    });

    // ---- Mods ----
    const modsDirAbs = () => path.join(core.repoRoot, "mods");

    const buildModItem = (file: string, histCounts: Map<string, number>) => {
      const abs = path.join(modsDirAbs(), file);
      const st = fs.statSync(abs);
      const meta = store.modMeta(file) ?? {};
      return {
        file,
        dir: "mods",
        name: meta.name ?? nameFromFile(file),
        desc: meta.desc ?? "",
        type: store.resolveType(file),
        size: st.size,
        sha256: "",
        mtime: st.mtime.toISOString(),
        uploadedAt: meta.uploadedAt ?? st.mtime.toISOString(),
        hasHistory: (histCounts.get(`mods/${file}`) ?? 0) > 0,
      };
    };

    scope.get("/api/mods", async (request) => {
      const q = (request.query ?? {}) as { q?: string; type?: string };
      const histCounts = store.historyCountMap();
      let items = fs.existsSync(modsDirAbs())
        ? fs
            .readdirSync(modsDirAbs(), { withFileTypes: true })
            .filter((d) => d.isFile())
            .map((d) => buildModItem(d.name, histCounts))
        : [];
      if (q.q) {
        const needle = q.q.toLowerCase();
        items = items.filter((i) => i.file.toLowerCase().includes(needle) || i.name.toLowerCase().includes(needle));
      }
      if (q.type) items = items.filter((i) => i.type === q.type);
      items.sort((a, b) => (a.file.toLowerCase() < b.file.toLowerCase() ? -1 : 1));
      return { items };
    });

    scope.patch("/api/mods/:file", async (request, reply) => {
      const file = decodeURIComponent((request.params as any).file);
      if (!fs.existsSync(path.join(modsDirAbs(), file))) return reply.code(404).send({ error: "没有这个 mod" });
      const body = (request.body ?? {}) as { name?: string; desc?: string; type?: ModType };
      const patch: any = {};
      if (typeof body.name === "string") patch.name = body.name;
      if (typeof body.desc === "string") patch.desc = body.desc;
      if (body.type && ["both", "server", "client"].includes(body.type)) patch.type = body.type;
      store.setModMeta(file, patch);
      return { item: buildModItem(file, store.historyCountMap()) };
    });

    scope.delete("/api/mods/:file", async (request, reply) => {
      const file = decodeURIComponent((request.params as any).file);
      const item = store.deleteRepoFile(`mods/${file}`, "mod", "管理台删除", "admin");
      if (!item) return reply.code(404).send({ error: "没有这个 mod" });
      store.deleteModMeta(file);
      core.invalidateManifest();
      return { ok: true, recycleId: item.id };
    });

    scope.post("/api/mods/upload", async (request, reply) => {
      const parts = (request as any).parts();
      let fileBuf: Buffer | null = null;
      let fileName = "";
      const fields: Record<string, string> = {};
      for await (const part of parts) {
        if (part.type === "file") {
          fileName = part.filename ?? "";
          fileBuf = await part.toBuffer();
        } else {
          fields[part.fieldname] = String(part.value ?? "");
        }
      }
      if (!fileBuf || !fileName) return reply.code(400).send({ error: "没收到文件" });
      const safeName = path.basename(fileName.replace(/\\/g, "/"));
      if (!safeName.toLowerCase().endsWith(".jar")) return reply.code(400).send({ error: "mods 只收 .jar" });

      const detected = safeName.toLowerCase().endsWith(".jar") ? readJarMeta(fileBuf) : {};
      store.writeRepoFile(`mods/${safeName}`, fileBuf, "上传覆盖前留档", "admin");
      const type = (fields.type as ModType) || store.resolveType(safeName);
      store.setModMeta(safeName, {
        name: fields.name || detected.name || nameFromFile(safeName),
        desc: fields.desc ?? detected.desc ?? "",
        type,
        uploadedAt: new Date().toISOString(),
      });
      core.invalidateManifest();
      return {
        item: buildModItem(safeName, store.historyCountMap()),
        detected: { name: detected.name ?? null, desc: detected.desc ?? null, source: detected.source ?? null },
      };
    });

    // ---- 其它目录 ----
    scope.get("/api/dirs", async () => {
      const dirs = (cfg.dirs ?? []).map((d) => {
        const rel = normPosix(d.path);
        const abs = path.join(core.repoRoot, ...rel.split("/"));
        const files = listFiles(abs);
        return {
          path: rel,
          tier: d.tier ?? "authoritative",
          owner: d.owner ?? "both",
          mode: d.mode ?? "mirror",
          perAccount: !!d.perAccount,
          fileCount: files.length,
          bytes: files.reduce((s, r) => s + fs.statSync(path.join(abs, ...r.split("/"))).size, 0),
        };
      });
      return { dirs };
    });

    scope.get("/api/tree", async (request, reply) => {
      const q = (request.query ?? {}) as { dir?: string };
      const dir = normPosix(q.dir ?? "");
      if (!dir) return reply.code(400).send({ error: "缺 dir" });
      const abs = resolveWithin(core.repoRoot, dir);
      if (abs === null) return reply.code(400).send({ error: "非法目录" });
      const entries = listFiles(abs).map((rel) => {
        const full = path.join(abs, ...rel.split("/"));
        const st = fs.statSync(full);
        return {
          path: `${dir}/${rel}`,
          name: rel,
          size: st.size,
          mtime: st.mtime.toISOString(),
          sha256: st.size <= 8 * 1024 * 1024 ? sha256(fs.readFileSync(full)) : "",
        };
      });
      entries.sort((a, b) => (a.name < b.name ? -1 : 1));
      return { dir, entries };
    });

    scope.post("/api/dirs/upload", async (request, reply) => {
      const parts = (request as any).parts();
      let fileBuf: Buffer | null = null;
      let fileName = "";
      const fields: Record<string, string> = {};
      for await (const part of parts) {
        if (part.type === "file") {
          fileName = part.filename ?? "";
          fileBuf = await part.toBuffer();
        } else {
          fields[part.fieldname] = String(part.value ?? "");
        }
      }
      if (!fileBuf || !fileName) return reply.code(400).send({ error: "没收到文件" });
      const dir = normPosix(fields.dir ?? "");
      if (!dir) return reply.code(400).send({ error: "缺 dir" });
      const sub = normPosix(fields.subdir ?? "");
      const rel = [dir, sub, path.basename(fileName.replace(/\\/g, "/"))].filter(Boolean).join("/");
      store.writeRepoFile(rel, fileBuf, "上传覆盖前留档", "admin");
      core.invalidateManifest();
      return { ok: true, path: rel };
    });

    scope.delete("/api/dirs/file", async (request, reply) => {
      const q = (request.query ?? {}) as { path?: string };
      const rel = normPosix(q.path ?? "");
      if (!rel) return reply.code(400).send({ error: "缺 path" });
      const item = store.deleteRepoFile(rel, "file", "管理台删除", "admin");
      if (!item) return reply.code(404).send({ error: "没有这个文件" });
      core.invalidateManifest();
      return { ok: true, recycleId: item.id };
    });

    // ---- config 在线编辑 ----
    const inConfigDirs = (rel: string): boolean =>
      configDirs.some((d) => rel === d || rel.startsWith(`${d}/`));

    scope.get("/api/config/tree", async () => {
      const entries: any[] = [];
      for (const d of configDirs) {
        const abs = path.join(core.repoRoot, ...d.split("/"));
        for (const rel of listFiles(abs)) {
          const full = path.join(abs, ...rel.split("/"));
          const st = fs.statSync(full);
          const relPath = `${d}/${rel}`;
          entries.push({ path: relPath, name: relPath, size: st.size, mtime: st.mtime.toISOString() });
        }
      }
      entries.sort((a, b) => (a.path < b.path ? -1 : 1));
      return { entries };
    });

    scope.get("/api/config/file", async (request, reply) => {
      const q = (request.query ?? {}) as { path?: string };
      const rel = normPosix(q.path ?? "");
      if (!rel || !inConfigDirs(rel)) return reply.code(400).send({ error: "只允许改 config 目录" });
      const abs = resolveWithin(core.repoRoot, rel);
      if (abs === null || !fs.existsSync(abs)) return reply.code(404).send({ error: "没有这个文件" });
      const st = fs.statSync(abs);
      if (st.size > TEXT_EDIT_MAX) {
        return { path: rel, content: "", size: st.size, mtime: st.mtime.toISOString(), binary: true };
      }
      const buf = fs.readFileSync(abs);
      const isBinary = buf.includes(0);
      return {
        path: rel,
        content: isBinary ? "" : buf.toString("utf-8"),
        size: st.size,
        mtime: st.mtime.toISOString(),
        binary: isBinary,
      };
    });

    scope.put("/api/config/file", async (request, reply) => {
      const body = (request.body ?? {}) as { path?: string; content?: string; note?: string };
      const rel = normPosix(body.path ?? "");
      if (!rel || !inConfigDirs(rel)) return reply.code(400).send({ error: "只允许改 config 目录" });
      if (typeof body.content !== "string") return reply.code(400).send({ error: "缺 content" });
      const { history } = store.writeRepoFile(rel, Buffer.from(body.content, "utf-8"), body.note ?? "在线编辑前留档", "admin");
      core.invalidateManifest();
      return { ok: true, historyId: history?.id ?? null };
    });

    // ---- 回收站 ----
    scope.get("/api/recycle", async () => ({
      items: store.recycleList(),
      settings: { ...store.settings(), usedBytes: store.recycleUsedBytes() },
    }));

    scope.post("/api/recycle/:id/restore", async (request, reply) => {
      const id = (request.params as any).id;
      const r = store.recycleRestore(id);
      if (!r) return reply.code(404).send({ error: "找不到或已失效" });
      core.invalidateManifest();
      return { ok: true, path: r.path };
    });

    scope.delete("/api/recycle/:id", async (request, reply) => {
      const id = (request.params as any).id;
      if (!store.recyclePurge(id)) return reply.code(404).send({ error: "找不到" });
      return { ok: true };
    });

    scope.post("/api/recycle/purge", async () => ({ ok: true, removed: store.recyclePurgeAll() }));

    scope.put("/api/recycle/settings", async (request) => {
      const body = (request.body ?? {}) as { keepDays?: number; keepBytes?: number };
      const s = store.setSettings({
        recycleKeepDays: typeof body.keepDays === "number" ? body.keepDays : undefined,
        recycleKeepBytes: typeof body.keepBytes === "number" ? body.keepBytes : undefined,
      });
      store.recycleEnforce();
      store.historyEnforce();
      return { settings: s };
    });

    // ---- 版本历史 ----
    scope.get("/api/history", async (request) => {
      const q = (request.query ?? {}) as { path?: string };
      const rel = normPosix(q.path ?? "");
      return { items: store.historyList(rel) };
    });

    scope.get("/api/history/:id/content", async (request, reply) => {
      const got = store.historyGet((request.params as any).id);
      if (!got) return reply.code(404).send({ error: "找不到" });
      reply.header("Content-Type", "application/octet-stream");
      return reply.send(got.buf);
    });

    scope.post("/api/history/:id/restore", async (request, reply) => {
      const r = store.historyRestore((request.params as any).id);
      if (!r) return reply.code(404).send({ error: "找不到" });
      core.invalidateManifest();
      return { ok: true, path: r.path };
    });

    // ---- 清单 / 同步到服务端 / 实例 ----
    scope.post("/api/manifest/refresh", async () => {
      core.invalidateManifest();
      const m = core.getManifest("default", true);
      return { ok: true, version: m.version };
    });

    scope.get("/api/sync/preview", async () => {
      if (!instance.configured) {
        return { configured: false, target: { modsDir: "", configDir: "" }, adds: [], updates: [], removes: [], unchanged: 0 };
      }
      const plan = instance.plan();
      return { configured: true, ...plan };
    });

    scope.post("/api/sync/apply", async () => {
      if (!instance.configured) return { ok: false, message: "没配置服务端实例路径" };
      const r = instance.apply();
      return { ok: true, ...r, version: core.version };
    });

    scope.get("/api/instance/status", async () => {
      const st = await instance.status();
      return { running: st.running, configured: instance.configured, raw: st.raw };
    });

    scope.post("/api/instance/restart", async () => {
      const r = await instance.restart();
      return { ok: r.ok, message: r.message };
    });

    // ---- agent 包 ----
    const agentJarPath = () => store.agentJarPath();

    scope.get("/api/agent", async () => {
      return { latest: store.agentMeta(), clients: store.clients() };
    });

    scope.post("/api/agent/upload", async (request, reply) => {
      const parts = (request as any).parts();
      let buf: Buffer | null = null;
      const fields: Record<string, string> = {};
      for await (const part of parts) {
        if (part.type === "file") buf = await part.toBuffer();
        else fields[part.fieldname] = String(part.value ?? "");
      }
      if (!buf) return reply.code(400).send({ error: "没收到 jar" });
      return { ok: true, latest: store.setAgent(buf, fields.version ?? "0.0.0") };
    });

    scope.get("/api/agent/download", async (_request, reply) => {
      if (!fs.existsSync(agentJarPath())) return reply.code(404).send({ error: "还没有 agent 包" });
      reply.header("Content-Type", "application/java-archive");
      return reply.send(fs.readFileSync(agentJarPath()));
    });
  });
}

export { MAX_UPLOAD, TEXT_EDIT_MAX };
