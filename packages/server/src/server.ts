import * as fs from "node:fs";
import * as path from "node:path";
import { createReadStream } from "node:fs";
import { pathToFileURL } from "node:url";
import Fastify, { FastifyInstance } from "fastify";

import {
  MAX_SUBMIT_BYTES,
  ServerConfig,
  loadConfig,
  normalizeProfile,
  resolveWithin,
} from "./config.js";
import { SyncCore } from "./core.js";

export function buildApp(core: SyncCore, opts: { logger?: boolean } = {}): FastifyInstance {
  const app = Fastify({
    logger: opts.logger ?? false,
    bodyLimit: MAX_SUBMIT_BYTES + 1,
  });

  // 上交接口的原始字节体：认 gzip / octet-stream 等，不做 JSON 解析。
  app.addContentTypeParser(
    ["application/gzip", "application/x-gzip", "application/octet-stream", "application/x-tar"],
    { parseAs: "buffer" },
    (_req, body, done) => done(null, body as Buffer)
  );

  app.get("/health", async () => ({ ok: true, version: core.version }));

  app.get("/manifest", async (request, reply) => {
    const q = request.query as Record<string, unknown>;
    let profile: string;
    try {
      profile = normalizeProfile(q.profile);
    } catch {
      return reply.code(400).send({ error: "invalid profile" });
    }
    const rawRefresh = String(q.refresh ?? "0").toLowerCase();
    const force = rawRefresh !== "" && rawRefresh !== "0" && rawRefresh !== "false" && rawRefresh !== "no";
    return core.getManifest(profile, force);
  });

  app.post("/submit", {
    onRequest: (request, reply, done) => {
      const expected = core.token;
      const provided = request.headers["x-sync-token"] ?? "";
      if (!expected || provided !== expected) {
        reply.code(403).send({ error: "invalid or missing token" });
        return;
      }
      done();
    },
  }, async (request, reply) => {
    const q = request.query as Record<string, unknown>;
    let profile: string;
    try {
      profile = normalizeProfile(q.profile);
    } catch {
      return reply.code(400).send({ error: "invalid profile" });
    }
    const body = request.body as Buffer;
    if (!Buffer.isBuffer(body) || body.length === 0) {
      return reply.code(400).send({ error: "missing or empty body" });
    }
    if (body.length > MAX_SUBMIT_BYTES) {
      return reply.code(413).send({ error: "payload too large" });
    }
    try {
      const result = core.submit(body, profile);
      return reply.code(200).send(result);
    } catch (e: any) {
      request.log.error(e);
      if (e && /tar\.gz|gunzip/i.test(String(e.message))) {
        return reply.code(400).send({ error: String(e.message) });
      }
      return reply.code(500).send({ error: "internal server error" });
    }
  });

  // 取文件 + 兜底 404：/f/<path> 只服务清单里出现过的路径。
  app.get("/*", async (request, reply) => {
    const url = new URL(request.raw.url ?? "/", "http://localhost");
    const pathname = decodeURIComponent(url.pathname);
    if (!pathname.startsWith("/f/")) {
      return reply.code(404).send({ error: "not found" });
    }
    const rel = pathname.slice("/f/".length);
    if (!core.anyManifestPaths().has(rel.replace(/\\/g, "/"))) {
      return reply.code(404).send({ error: "not found" });
    }
    const target = resolveWithin(core.repoRoot, rel);
    if (target === null) {
      return reply.code(403).send({ error: "forbidden: path outside repo_root" });
    }
    if (!fs.existsSync(target) || !fs.statSync(target).isFile()) {
      return reply.code(404).send({ error: "not found" });
    }
    reply.header("Content-Type", "application/octet-stream");
    return reply.send(createReadStream(target));
  });

  app.setNotFoundHandler((_request, reply) => {
    reply.code(404).send({ error: "not found" });
  });

  return app;
}

function parseArgs(argv: string[]): { config?: string; host?: string; port?: number } {
  const out: { config?: string; host?: string; port?: number } = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--config" && argv[i + 1]) out.config = argv[++i];
    else if (a === "--host" && argv[i + 1]) out.host = argv[++i];
    else if (a === "--port" && argv[i + 1]) out.port = Number(argv[++i]);
  }
  return out;
}

export async function main(argv: string[] = process.argv.slice(2)): Promise<number> {
  const args = parseArgs(argv);
  if (!args.config) {
    console.error("缺少 --config 参数");
    return 2;
  }
  let cfg: ServerConfig;
  try {
    cfg = loadConfig(args.config);
  } catch (e: any) {
    console.error(`无法读取配置文件 ${args.config}：${e.message}`);
    return 2;
  }
  if (!cfg.repo_root) {
    console.error("配置缺少 repo_root，无法启动。");
    return 2;
  }
  if (!fs.existsSync(cfg.repo_root) || !fs.statSync(cfg.repo_root).isDirectory()) {
    console.error(`仓库目录不存在：${cfg.repo_root}\n请先创建该目录（例如 mkdir -p ${cfg.repo_root}）后再启动服务。`);
    return 2;
  }

  const host = args.host ?? cfg.listen?.host ?? "0.0.0.0";
  const port = args.port ?? cfg.listen?.port ?? 38080;

  const core = new SyncCore(cfg);
  const app = buildApp(core);

  try {
    await app.listen({ host, port });
  } catch (e: any) {
    console.error(`启动失败：${e.message}`);
    return 1;
  }
  const addr = app.server.address();
  const boundPort = addr && typeof addr === "object" ? addr.port : port;
  console.log(
    `mc_sync_server 已启动：http://${host}:${boundPort}  仓库=${core.repoRoot}  协议=${2}`
  );
  return 0;
}

// 直接运行时入口：成功启动后不要退出（服务本身撑着事件循环），只有失败才退。
const entry = process.argv[1] ? pathToFileURL(path.resolve(process.argv[1])).href : "";
if (entry && import.meta.url === entry) {
  main()
    .then((code) => {
      if (code !== 0) process.exit(code);
    })
    .catch((err) => {
      console.error(err);
      process.exit(1);
    });
}
